import sys
import io
import math
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def black_scholes_call(S, K, T, r, sigma):
    if T <= 0: return max(0.0, S - K)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    c = S * 0.5 * (1.0 + math.erf(d1 / math.sqrt(2.0))) - K * math.exp(-r * T) * 0.5 * (1.0 + math.erf(d2 / math.sqrt(2.0)))
    return max(0.05, c)

def black_scholes_put(S, K, T, r, sigma):
    if T <= 0: return max(0.0, K - S)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    p = K * math.exp(-r * T) * 0.5 * (1.0 - math.erf(d2 / math.sqrt(2.0))) - S * 0.5 * (1.0 - math.erf(d1 / math.sqrt(2.0)))
    return max(0.05, p)

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
    return ha_df

def compute_bollinger_bands(series, period=20, std_dev=2.0):
    sma = series.rolling(window=period).mean()
    rolling_std = series.rolling(window=period).std()
    upper = sma + (std_dev * rolling_std)
    lower = sma - (std_dev * rolling_std)
    return upper, sma, lower

def simulate_variation(nifty_df, rr_ratio=2.0, max_sl=20.0, min_sl=2.0, buffer_pts=1.0, trend_filter=False):
    TOTAL_QTY = 130
    HALF_QTY = 65
    BB_PERIOD = 20
    BB_STDDEV = 2.0
    T = 3.0 / 365.0
    r = 0.065
    sigma = 0.14

    days = nifty_df.groupby(nifty_df.index.date)
    all_trades = []

    for trade_date, day_df in days:
        if len(day_df) < 30: continue
        spot_open = day_df['Open'].iloc[0]
        atm_strike = round(spot_open / 50.0) * 50.0

        # Nifty Spot EMA 20 for trend filter
        nifty_ema20 = day_df['Close'].ewm(span=20, adjust=False).mean()

        ce_prices = []
        pe_prices = []
        for idx, row in day_df.iterrows():
            c_open = black_scholes_call(row['Open'], atm_strike, T, r, sigma)
            c_high = black_scholes_call(row['High'], atm_strike, T, r, sigma)
            c_low = black_scholes_call(row['Low'], atm_strike, T, r, sigma)
            c_close = black_scholes_call(row['Close'], atm_strike, T, r, sigma)

            p_open = black_scholes_put(row['Open'], atm_strike, T, r, sigma)
            p_high = black_scholes_put(row['Low'], atm_strike, T, r, sigma)
            p_low = black_scholes_put(row['High'], atm_strike, T, r, sigma)
            p_close = black_scholes_put(row['Close'], atm_strike, T, r, sigma)

            ce_prices.append({'Open': c_open, 'High': max(c_open, c_high, c_close), 'Low': min(c_open, c_low, c_close), 'Close': c_close})
            pe_prices.append({'Open': p_open, 'High': max(p_open, p_high, p_close), 'Low': min(p_open, p_low, p_close), 'Close': p_close})

        ce_df = pd.DataFrame(ce_prices, index=day_df.index)
        pe_df = pd.DataFrame(pe_prices, index=day_df.index)

        ha_ce = compute_heikin_ashi(ce_df)
        ha_pe = compute_heikin_ashi(pe_df)

        ha_ce['BB_Upper'], ha_ce['BB_Middle'], ha_ce['BB_Lower'] = compute_bollinger_bands(ha_ce['HA_Close'], BB_PERIOD, BB_STDDEV)
        ha_pe['BB_Upper'], ha_pe['BB_Middle'], ha_pe['BB_Lower'] = compute_bollinger_bands(ha_pe['HA_Close'], BB_PERIOD, BB_STDDEV)

        daily_trades_count = 0
        active_position = None
        ce_touch_armed = False
        pe_touch_armed = False
        ce_touch_age = 0
        pe_touch_age = 0

        for i in range(BB_PERIOD, len(day_df)):
            t = day_df.index[i]
            t_str = t.strftime('%H:%M')

            if active_position is not None:
                pos_type = active_position['type']
                curr_bar = ce_df.iloc[i] if pos_type == 'CE' else pe_df.iloc[i]

                if not active_position['t1_hit'] and curr_bar['High'] >= active_position['target_price']:
                    active_position['t1_hit'] = True
                    active_position['stop_loss'] = active_position['entry_price']
                    partial_pnl = HALF_QTY * (active_position['target_price'] - active_position['entry_price'])
                    active_position['booked_pnl'] += partial_pnl
                    active_position['remaining_qty'] = HALF_QTY

                if curr_bar['Low'] <= active_position['stop_loss']:
                    exit_price = active_position['stop_loss']
                    final_pnl = active_position['remaining_qty'] * (exit_price - active_position['entry_price'])
                    total_pnl = active_position['booked_pnl'] + final_pnl
                    all_trades.append({'Date': trade_date, 'PnL': total_pnl, 'Type': pos_type, 'Won': total_pnl > 0})
                    active_position = None
                    continue

                if t_str >= "15:15":
                    exit_price = curr_bar['Close']
                    final_pnl = active_position['remaining_qty'] * (exit_price - active_position['entry_price'])
                    total_pnl = active_position['booked_pnl'] + final_pnl
                    all_trades.append({'Date': trade_date, 'PnL': total_pnl, 'Type': pos_type, 'Won': total_pnl > 0})
                    active_position = None
                    break
                continue

            if t_str > "10:30" or daily_trades_count >= 2:
                continue

            ce_row = ha_ce.iloc[i]
            pe_row = ha_pe.iloc[i]

            if ce_row['HA_Low'] <= ce_row['BB_Lower']:
                ce_touch_armed = True
                ce_touch_age = 0
            elif ce_touch_armed:
                ce_touch_age += 1
                if ce_touch_age > 3: ce_touch_armed = False

            if pe_row['HA_Low'] <= pe_row['BB_Lower']:
                pe_touch_armed = True
                pe_touch_age = 0
            elif pe_touch_armed:
                pe_touch_age += 1
                if pe_touch_age > 3: pe_touch_armed = False

            ce_triggered = ce_touch_armed and ce_row['is_green']
            pe_triggered = pe_touch_armed and pe_row['is_green']

            # Optional Trend Filter: Only Buy CE if Nifty Close >= EMA20, Only Buy PE if Nifty Close <= EMA20
            if trend_filter:
                spot_close = day_df['Close'].iloc[i]
                spot_ema = nifty_ema20.iloc[i]
                if ce_triggered and spot_close < spot_ema:
                    ce_triggered = False
                if pe_triggered and spot_close > spot_ema:
                    pe_triggered = False

            selected_type = 'CE' if ce_triggered else ('PE' if pe_triggered else None)
            sel_row = ce_row if ce_triggered else (pe_row if pe_triggered else None)

            if selected_type is not None:
                entry_price = round(sel_row['HA_High'] + buffer_pts, 2)
                stop_loss = round(sel_row['HA_Low'] - buffer_pts, 2)
                risk = round(entry_price - stop_loss, 2)

                if min_sl <= risk <= max_sl:
                    target_price = round(entry_price + (rr_ratio * risk), 2)
                    daily_trades_count += 1
                    active_position = {
                        'type': selected_type,
                        'entry_price': entry_price,
                        'stop_loss': stop_loss,
                        'target_price': target_price,
                        'remaining_qty': TOTAL_QTY,
                        'booked_pnl': 0.0,
                        't1_hit': False
                    }
                    if selected_type == 'CE': ce_touch_armed = False
                    else: pe_touch_armed = False

    trades_df = pd.DataFrame(all_trades)
    if trades_df.empty:
        return {'Total_PnL': 0, 'Trades': 0, 'WinRate': 0, 'ProfitFactor': 0}

    total_pnl = trades_df['PnL'].sum()
    total_trades = len(trades_df)
    win_trades = len(trades_df[trades_df['Won']])
    win_rate = (win_trades / total_trades) * 100.0 if total_trades > 0 else 0

    gross_profit = trades_df[trades_df['PnL'] > 0]['PnL'].sum()
    gross_loss = abs(trades_df[trades_df['PnL'] < 0]['PnL'].sum())
    pf = (gross_profit / gross_loss) if gross_loss > 0 else 999.0

    return {
        'Total_PnL': total_pnl,
        'Trades': total_trades,
        'WinRate': win_rate,
        'ProfitFactor': pf,
        'GrossProfit': gross_profit,
        'GrossLoss': gross_loss
    }

def main():
    print("================================================================================")
    print(" 🔍 BOLLINGER HA STRATEGY PARAMETER OPTIMIZATION & BACKTEST COMPARISON")
    print("================================================================================")

    nifty_df = yf.download('^NSEI', interval='1m', period='7d', progress=False)
    if isinstance(nifty_df.columns, pd.MultiIndex):
        nifty_df.columns = nifty_df.columns.get_level_values(0)
    nifty_df.index = pd.to_datetime(nifty_df.index).tz_convert('Asia/Kolkata')

    variations = [
        ("Base Setup (1:2 R:R, Pure HA Bounce)", {'rr_ratio': 2.0, 'max_sl': 20.0, 'trend_filter': False}),
        ("Higher R:R (1:2.5 R:R, Pure HA Bounce)", {'rr_ratio': 2.5, 'max_sl': 20.0, 'trend_filter': False}),
        ("Tight SL Filter (Max SL <= 12 pts)", {'rr_ratio': 2.0, 'max_sl': 12.0, 'trend_filter': False}),
        ("Trend-Filtered (Nifty Spot EMA20 Filter)", {'rr_ratio': 2.0, 'max_sl': 20.0, 'trend_filter': True}),
        ("Trend-Filtered + 1:2.5 R:R", {'rr_ratio': 2.5, 'max_sl': 20.0, 'trend_filter': True}),
    ]

    results = []
    for name, params in variations:
        res = simulate_variation(nifty_df, **params)
        results.append({
            'Variation': name,
            'Total PnL (₹)': f"₹{res['Total_PnL']:,.2f}",
            'Trades': res['Trades'],
            'Win Rate %': f"{res['WinRate']:.1f}%",
            'Profit Factor': f"{res['ProfitFactor']:.2f}",
            'Gross Win': f"₹{res['GrossProfit']:,.2f}",
            'Gross Loss': f"₹{res['GrossLoss']:,.2f}"
        })

    res_df = pd.DataFrame(results)
    print(res_df.to_string(index=False))
    print("================================================================================\n")

if __name__ == '__main__':
    main()
