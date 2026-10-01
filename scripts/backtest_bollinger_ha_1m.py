import sys
import io
import math
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 encoding for output
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# ==============================================================================
# Option Pricing Helper (Black-Scholes & ATM Approximation)
# ==============================================================================
def black_scholes_call(S, K, T, r, sigma):
    if T <= 0:
        return max(0.0, S - K)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    c = S * 0.5 * (1.0 + math.erf(d1 / math.sqrt(2.0))) - K * math.exp(-r * T) * 0.5 * (1.0 + math.erf(d2 / math.sqrt(2.0)))
    return max(0.05, c)

def black_scholes_put(S, K, T, r, sigma):
    if T <= 0:
        return max(0.0, K - S)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    p = K * math.exp(-r * T) * 0.5 * (1.0 - math.erf(d2 / math.sqrt(2.0))) - S * 0.5 * (1.0 - math.erf(d1 / math.sqrt(2.0)))
    return max(0.05, p)

# ==============================================================================
# Heikin-Ashi Transformation
# ==============================================================================
def compute_heikin_ashi(df):
    ha_df = pd.DataFrame(index=df.index)
    ha_close = (df['Open'] + df['High'] + df['Low'] + df['Close']) / 4.0
    
    ha_open = np.zeros(len(df))
    ha_open[0] = (df['Open'].iloc[0] + df['Close'].iloc[0]) / 2.0
    for i in range(1, len(df)):
        ha_open[i] = (ha_open[i-1] + ha_close.iloc[i-1]) / 2.0
    
    ha_df['HA_Open'] = ha_open
    ha_df['HA_Close'] = ha_close.values
    ha_df['HA_High'] = np.maximum(df['High'].values, np.maximum(ha_open, ha_close.values))
    ha_df['HA_Low'] = np.minimum(df['Low'].values, np.minimum(ha_open, ha_close.values))
    ha_df['is_green'] = ha_df['HA_Close'] >= ha_df['HA_Open']
    
    # Original prices for execution
    ha_df['Orig_Open'] = df['Open'].values
    ha_df['Orig_High'] = df['High'].values
    ha_df['Orig_Low'] = df['Low'].values
    ha_df['Orig_Close'] = df['Close'].values
    return ha_df

# ==============================================================================
# Bollinger Bands on Heikin-Ashi
# ==============================================================================
def compute_bollinger_bands(series, period=20, std_dev=2.0):
    sma = series.rolling(window=period).mean()
    rolling_std = series.rolling(window=period).std()
    upper = sma + (std_dev * rolling_std)
    lower = sma - (std_dev * rolling_std)
    return upper, sma, lower

# ==============================================================================
# Main Backtest Runner
# ==============================================================================
def run_backtest():
    print("================================================================================")
    print(" 🚀 KUSHAL VARSHNEY 1-MINUTE BOLLINGER HA OPTION BUYING STRATEGY BACKTEST")
    print("================================================================================")
    print(" Downloading actual 1-minute Nifty 50 historical data from Yahoo Finance...")

    nifty_df = yf.download('^NSEI', interval='1m', period='7d', progress=False)
    if nifty_df.empty:
        print("❌ Error: No historical data returned for ^NSEI.")
        return

    # Clean multi-index columns if present
    if isinstance(nifty_df.columns, pd.MultiIndex):
        nifty_df.columns = nifty_df.columns.get_level_values(0)

    nifty_df.index = pd.to_datetime(nifty_df.index).tz_convert('Asia/Kolkata')
    print(f" Loaded {len(nifty_df)} 1-minute candles from {nifty_df.index[0]} to {nifty_df.index[-1]}\n")

    trade_log = []
    daily_summaries = []

    # Parameters
    BB_PERIOD = 20
    BB_STDDEV = 2.0
    MIN_SL = 2.0
    MAX_SL = 20.0
    BUFFER = 1.0
    LOT_SIZE = 65
    NUM_LOTS = 2
    TOTAL_QTY = NUM_LOTS * LOT_SIZE # 130 qty
    HALF_QTY = TOTAL_QTY // 2       # 65 qty

    # Group by trading date
    days = nifty_df.groupby(nifty_df.index.date)

    for trade_date, day_df in days:
        if len(day_df) < 30:
            continue

        spot_open = day_df['Open'].iloc[0]
        atm_strike = round(spot_open / 50.0) * 50.0

        # Construct Call and Put 1m price series
        # Using 3 days to expiry (T = 3/365), IV = 14% (0.14), r = 6.5%
        T = 3.0 / 365.0
        r = 0.065
        sigma = 0.14

        ce_prices = []
        pe_prices = []

        for idx, row in day_df.iterrows():
            c_open = black_scholes_call(row['Open'], atm_strike, T, r, sigma)
            c_high = black_scholes_call(row['High'], atm_strike, T, r, sigma)
            c_low = black_scholes_call(row['Low'], atm_strike, T, r, sigma)
            c_close = black_scholes_call(row['Close'], atm_strike, T, r, sigma)

            p_open = black_scholes_put(row['Open'], atm_strike, T, r, sigma)
            p_high = black_scholes_put(row['Low'], atm_strike, T, r, sigma) # Inverse high/low for put
            p_low = black_scholes_put(row['High'], atm_strike, T, r, sigma)
            p_close = black_scholes_put(row['Close'], atm_strike, T, r, sigma)

            ce_prices.append({'Open': c_open, 'High': max(c_open, c_high, c_close), 'Low': min(c_open, c_low, c_close), 'Close': c_close})
            pe_prices.append({'Open': p_open, 'High': max(p_open, p_high, p_close), 'Low': min(p_open, p_low, p_close), 'Close': p_close})

        ce_df = pd.DataFrame(ce_prices, index=day_df.index)
        pe_df = pd.DataFrame(pe_prices, index=day_df.index)

        # Compute Heikin-Ashi
        ha_ce = compute_heikin_ashi(ce_df)
        ha_pe = compute_heikin_ashi(pe_df)

        # Compute Bollinger Bands on HA
        ha_ce['BB_Upper'], ha_ce['BB_Middle'], ha_ce['BB_Lower'] = compute_bollinger_bands(ha_ce['HA_Close'], BB_PERIOD, BB_STDDEV)
        ha_pe['BB_Upper'], ha_pe['BB_Middle'], ha_pe['BB_Lower'] = compute_bollinger_bands(ha_pe['HA_Close'], BB_PERIOD, BB_STDDEV)

        # Intraday Simulation per day
        daily_trades_count = 0
        active_position = None
        ce_touch_armed = False
        pe_touch_armed = False
        ce_touch_age = 0
        pe_touch_age = 0

        day_pnl = 0.0

        for i in range(BB_PERIOD, len(day_df)):
            t = day_df.index[i]
            t_str = t.strftime('%H:%M')

            # 1. Manage Active Position
            if active_position is not None:
                pos_strike_type = active_position['type']
                curr_bar = ce_df.iloc[i] if pos_strike_type == 'CE' else pe_df.iloc[i]

                # Check Target 1 (1:2 R:R) -> Book 50% & Move SL to Cost
                if not active_position['t1_hit'] and curr_bar['High'] >= active_position['target_price']:
                    active_position['t1_hit'] = True
                    active_position['stop_loss'] = active_position['entry_price'] # Trailed to Cost (CSL)
                    partial_pnl = HALF_QTY * (active_position['target_price'] - active_position['entry_price'])
                    active_position['booked_pnl'] += partial_pnl
                    active_position['remaining_qty'] = HALF_QTY
                    trade_log.append({
                        'Date': trade_date,
                        'Time': t_str,
                        'Type': pos_strike_type,
                        'Action': 'T1_BOOK_50%',
                        'Price': active_position['target_price'],
                        'Qty': HALF_QTY,
                        'PnL': partial_pnl,
                        'Reason': f"Target 1 Hit (1:2 R:R) -> Cost SL set to ₹{active_position['entry_price']:.2f}"
                    })

                # Check Stop Loss Hit
                if curr_bar['Low'] <= active_position['stop_loss']:
                    exit_price = active_position['stop_loss']
                    rem_qty = active_position['remaining_qty']
                    final_pnl = rem_qty * (exit_price - active_position['entry_price'])
                    total_trade_pnl = active_position['booked_pnl'] + final_pnl
                    day_pnl += total_trade_pnl

                    reason = "Cost SL Hit" if active_position['t1_hit'] else "Initial SL Hit"
                    trade_log.append({
                        'Date': trade_date,
                        'Time': t_str,
                        'Type': pos_strike_type,
                        'Action': 'EXIT_ALL',
                        'Price': exit_price,
                        'Qty': rem_qty,
                        'PnL': final_pnl,
                        'Total_Trade_PnL': total_trade_pnl,
                        'Reason': reason
                    })

                    stopped_type = active_position['type']
                    active_position = None

                    # If Trade #1 stopped out, arm the OPPOSITE contract for Trade #2
                    if daily_trades_count < 2:
                        if stopped_type == 'CE':
                            pe_touch_armed = False
                            pe_touch_age = 0
                        else:
                            ce_touch_armed = False
                            ce_touch_age = 0
                    continue

                # Auto square-off at 15:15
                if t_str >= "15:15":
                    exit_price = curr_bar['Close']
                    rem_qty = active_position['remaining_qty']
                    final_pnl = rem_qty * (exit_price - active_position['entry_price'])
                    total_trade_pnl = active_position['booked_pnl'] + final_pnl
                    day_pnl += total_trade_pnl

                    trade_log.append({
                        'Date': trade_date,
                        'Time': t_str,
                        'Type': pos_strike_type,
                        'Action': 'EOD_SQUAREOFF',
                        'Price': exit_price,
                        'Qty': rem_qty,
                        'PnL': final_pnl,
                        'Total_Trade_PnL': total_trade_pnl,
                        'Reason': "Intraday Auto Square-Off at 15:15"
                    })
                    active_position = None
                    break

                continue # In active trade, do not evaluate new entries

            # 2. Check Entry Window (09:15 to 10:30) & Daily Trade Cap (Max 2 trades)
            if t_str > "10:30" or daily_trades_count >= 2:
                continue

            # Evaluate CE & PE Setups
            ce_row = ha_ce.iloc[i]
            pe_row = ha_pe.iloc[i]

            # CE Band Touch Check
            if ce_row['HA_Low'] <= ce_row['BB_Lower']:
                ce_touch_armed = True
                ce_touch_age = 0
            elif ce_touch_armed:
                ce_touch_age += 1
                if ce_touch_age > 3:
                    ce_touch_armed = False

            # PE Band Touch Check
            if pe_row['HA_Low'] <= pe_row['BB_Lower']:
                pe_touch_armed = True
                pe_touch_age = 0
            elif pe_touch_armed:
                pe_touch_age += 1
                if pe_touch_age > 3:
                    pe_touch_armed = False

            # Check Entry Triggers (Priority to whichever triggers first)
            ce_triggered = ce_touch_armed and ce_row['is_green']
            pe_triggered = pe_touch_armed and pe_row['is_green']

            selected_type = None
            sel_row = None
            if ce_triggered:
                selected_type = 'CE'
                sel_row = ce_row
            elif pe_triggered:
                selected_type = 'PE'
                sel_row = pe_row

            if selected_type is not None:
                entry_price = round(sel_row['HA_High'] + BUFFER, 2)
                stop_loss = round(sel_row['HA_Low'] - BUFFER, 2)
                risk = round(entry_price - stop_loss, 2)

                # Validate Risk Filter: 2.0 <= Risk <= 20.0 pts
                if MIN_SL <= risk <= MAX_SL:
                    target_price = round(entry_price + (2.0 * risk), 2)
                    daily_trades_count += 1
                    active_position = {
                        'type': selected_type,
                        'entry_time': t_str,
                        'entry_price': entry_price,
                        'stop_loss': stop_loss,
                        'target_price': target_price,
                        'risk': risk,
                        'remaining_qty': TOTAL_QTY,
                        'booked_pnl': 0.0,
                        't1_hit': False
                    }

                    trade_log.append({
                        'Date': trade_date,
                        'Time': t_str,
                        'Type': selected_type,
                        'Action': f'BUY_{selected_type} (Trade #{daily_trades_count})',
                        'Price': entry_price,
                        'Qty': TOTAL_QTY,
                        'SL': stop_loss,
                        'T1': target_price,
                        'Risk': risk,
                        'Reason': f"HA Lower Band Bounce (Risk: ₹{risk:.2f}, T1: ₹{target_price:.2f})"
                    })

                    # Reset touch
                    if selected_type == 'CE':
                        ce_touch_armed = False
                    else:
                        pe_touch_armed = False

        daily_summaries.append({
            'Date': trade_date,
            'ATM_Strike': atm_strike,
            'Trades_Taken': daily_trades_count,
            'Daily_PnL': day_pnl
        })

    # ==============================================================================
    # Performance Report
    # ==============================================================================
    print("================================================================================")
    print("                             DETAILED TRADE LOG")
    print("================================================================================")
    log_df = pd.DataFrame(trade_log)
    if not log_df.empty:
        for _, row in log_df.iterrows():
            if 'BUY' in row['Action']:
                print(f" [{row['Date']}] {row['Time']} | 🟢 {row['Action']:<16} @ ₹{row['Price']:<6.2f} | SL: ₹{row['SL']:<6.2f} | T1: ₹{row['T1']:<6.2f} | Qty: {row['Qty']} | {row['Reason']}")
            elif 'T1_BOOK' in row['Action']:
                print(f" [{row['Date']}] {row['Time']} | 🎯 {row['Action']:<16} @ ₹{row['Price']:<6.2f} | PnL: +₹{row['PnL']:<8.2f} | {row['Reason']}")
            elif 'EXIT' in row['Action']:
                pnl_str = f"+₹{row['Total_Trade_PnL']:.2f}" if row['Total_Trade_PnL'] >= 0 else f"-₹{abs(row['Total_Trade_PnL']):.2f}"
                print(f" [{row['Date']}] {row['Time']} | 🔴 {row['Action']:<16} @ ₹{row['Price']:<6.2f} | Trade PnL: {pnl_str} ({row['Reason']})")
            elif 'EOD' in row['Action']:
                pnl_str = f"+₹{row['Total_Trade_PnL']:.2f}" if row['Total_Trade_PnL'] >= 0 else f"-₹{abs(row['Total_Trade_PnL']):.2f}"
                print(f" [{row['Date']}] {row['Time']} | ⏱️ {row['Action']:<16} @ ₹{row['Price']:<6.2f} | Trade PnL: {pnl_str} ({row['Reason']})")
    else:
        print(" No trades triggered within risk and band criteria.")

    print("\n================================================================================")
    print("                           DAILY PERFORMANCE SUMMARY")
    print("================================================================================")
    daily_df = pd.DataFrame(daily_summaries)
    print(daily_df.to_string(index=False))

    total_pnl = daily_df['Daily_PnL'].sum()
    total_trades = daily_df['Trades_Taken'].sum()
    win_days = len(daily_df[daily_df['Daily_PnL'] > 0])
    loss_days = len(daily_df[daily_df['Daily_PnL'] < 0])
    win_rate = (win_days / len(daily_df) * 100.0) if len(daily_df) > 0 else 0.0

    print("\n================================================================================")
    print("                              FINAL METRICS")
    print("================================================================================")
    print(f" Total Trading Days Tested:  {len(daily_df)}")
    print(f" Total Trades Executed:      {total_trades}")
    print(f" Profitable Days:            {win_days} / {len(daily_df)} ({win_rate:.1f}%)")
    print(f" Total Net PnL (2 Lots):     {'₹{:,.2f}'.format(total_pnl)}")
    print("================================================================================\n")

if __name__ == '__main__':
    run_backtest()
