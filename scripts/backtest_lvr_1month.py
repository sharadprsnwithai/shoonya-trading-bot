import sys
import io
import math
import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from collections import defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# ==============================================================================
# Universe of Top Liquid F&O Stocks
# ==============================================================================
UNIVERSE = [
    "RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "ICICIBANK.NS", 
    "SBIN.NS", "TATASTEEL.NS", "MARUTI.NS", "SUNPHARMA.NS", "BHARTIARTL.NS", 
    "AXISBANK.NS", "KOTAKBANK.NS", "HINDALCO.NS", "BHEL.NS", "ITC.NS", 
    "LT.NS", "BAJFINANCE.NS", "M&M.NS", "NTPC.NS", "COALINDIA.NS",
    "TATAMOTORS.NS", "JINDALSTEL.NS", "VEDL.NS", "CIPLA.NS", "DRREDDY.NS",
    "WIPRO.NS", "TECHM.NS", "HEROMOTOCO.NS", "EICHERMOT.NS", "BAJAJ-AUTO.NS"
]

LOT_SIZES = {
    "RELIANCE": 250, "TCS": 175, "INFY": 400, "HDFCBANK": 550, "ICICIBANK": 700,
    "SBIN": 750, "TATASTEEL": 5500, "MARUTI": 50, "SUNPHARMA": 350, "BHARTIARTL": 475,
    "AXISBANK": 625, "KOTAKBANK": 400, "HINDALCO": 1400, "BHEL": 2625, "ITC": 1600,
    "LT": 175, "BAJFINANCE": 125, "M&M": 350, "NTPC": 1500, "COALINDIA": 2100,
    "TATAMOTORS": 700, "JINDALSTEL": 625, "VEDL": 1150, "CIPLA": 650, "DRREDDY": 125,
    "WIPRO": 1500, "TECHM": 600, "HEROMOTOCO": 150, "EICHERMOT": 175, "BAJAJ-AUTO": 75
}

def calculate_vwap(df):
    vol = df['Volume'].replace(0, 1)
    typical = (df['High'] + df['Low'] + df['Close']) / 3.0
    return (typical * vol).cumsum() / vol.cumsum()

def run_lvr_backtest():
    print("================================================================================")
    print(" 🚀 KUSHAL VARSHNEY LOWEST VOLUME REVERSAL (LVR) 1-MONTH BACKTEST")
    print("================================================================================")
    print(f" Downloading 1-month 5-minute historical data for {len(UNIVERSE)} F&O stocks...")

    data = {}
    for ticker in UNIVERSE:
        sym = ticker.replace(".NS", "")
        df = yf.download(ticker, interval='5m', period='1mo', progress=False)
        if df.empty:
            continue
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.get_level_values(0)
        df.index = pd.to_datetime(df.index).tz_convert('Asia/Kolkata')
        data[sym] = df

    print(f" Loaded 5-min historical data for {len(data)} stocks.\n")

    # Group all data by date
    all_dates = sorted(list(set(d for df in data.values() for d in df.index.date)))
    print(f" Backtest Date Range: {all_dates[0]} to {all_dates[-1]} ({len(all_dates)} trading sessions)\n")

    all_trades = []
    daily_summaries = []

    for trade_date in all_dates:
        # 1. Morning 09:25 Scan: Calculate % change from open at 09:25 (Candle 2 close)
        stock_moves = []
        for sym, df in data.items():
            day_data = df[df.index.date == trade_date]
            if len(day_data) < 4: continue

            c1 = day_data.iloc[0] # 09:15-09:20
            c2 = day_data.iloc[1] # 09:20-09:25
            open_p = c1['Open']
            close_p = c2['Close']
            pct_chg = ((close_p - open_p) / open_p) * 100.0
            stock_moves.append((sym, pct_chg, day_data))

        if not stock_moves:
            continue

        # Sort gainers (Long candidates >= +0.8%) & losers (Short candidates <= -0.8%)
        gainers = sorted([s for s in stock_moves if s[1] >= 0.8], key=lambda x: x[1], reverse=True)[:5]
        losers = sorted([s for s in stock_moves if s[1] <= -0.8], key=lambda x: x[1])[:5]

        candidates = [(s[0], 'LONG', s[2]) for s in gainers] + [(s[0], 'SHORT', s[2]) for s in losers]

        day_trades = []
        day_pnl = 0.0

        for sym, direction, day_df in candidates:
            if len(day_trades) >= 2: # Max 2 concurrent/daily trades
                break

            # Calculate 15-minute range (Candles 0, 1, 2 = 09:15-09:30)
            c1_3 = day_df.iloc[:3]
            range_15m_high = c1_3['High'].max()
            range_15m_low = c1_3['Low'].min()

            # Baseline lowest volume from first 3 candles
            baseline_lowest_vol = c1_3['Volume'].replace(0, np.nan).min()
            if pd.isna(baseline_lowest_vol):
                continue

            # Calculate VWAP series
            vwap_series = calculate_vwap(day_df)

            # Sizing: 2 lots
            lot_size = LOT_SIZES.get(sym, 250)
            total_qty = 2 * lot_size
            half_qty = lot_size

            # Scan from Candle 3 (09:30 onwards)
            trigger_armed = False
            trigger_price = None
            sl_price = None
            target_price = None
            armed_time = None
            armed_idx = 0
            rolling_lowest_vol = baseline_lowest_vol

            for i in range(3, len(day_df)):
                c = day_df.iloc[i]
                t_str = day_df.index[i].strftime('%H:%M')

                if t_str > "11:00" and not trigger_armed:
                    break # Entry cutoff at 11:00 AM

                is_opposite_candle = (c['Close'] < c['Open']) if direction == 'LONG' else (c['Close'] > c['Open'])

                # Arming condition: Opposite color candle with volume < rolling_lowest_vol
                if is_opposite_candle and c['Volume'] > 0 and c['Volume'] < rolling_lowest_vol:
                    rolling_lowest_vol = c['Volume']
                    trigger_armed = True
                    armed_time = t_str
                    armed_idx = i
                    if direction == 'LONG':
                        trigger_price = round(c['High'] + 0.05, 2)
                        sl_price = round(c['Low'] - 0.05, 2)
                        risk = trigger_price - sl_price
                        target_price = round(trigger_price + (2.0 * risk), 2)
                    else:
                        trigger_price = round(c['Low'] - 0.05, 2)
                        sl_price = round(c['High'] + 0.05, 2)
                        risk = sl_price - trigger_price
                        target_price = round(trigger_price - (2.0 * risk), 2)
                    continue

                # Check Trigger Breach on subsequent candles
                if trigger_armed:
                    # Timeout after 6 candles (30 mins)
                    if (i - armed_idx) > 6:
                        trigger_armed = False
                        continue

                    vwap = vwap_series.iloc[i]
                    breached = False

                    if direction == 'LONG':
                        # Breach check: High >= trigger_price
                        if c['High'] >= trigger_price:
                            # Filters: Spot > VWAP AND Spot > 15m High
                            if trigger_price > vwap and trigger_price > range_15m_high:
                                breached = True
                            else:
                                trigger_armed = False # Filter rejected
                                continue
                    else:
                        # Breach check: Low <= trigger_price
                        if c['Low'] <= trigger_price:
                            # Filters: Spot < VWAP AND Spot < 15m Low
                            if trigger_price < vwap and trigger_price < range_15m_low:
                                breached = True
                            else:
                                trigger_armed = False # Filter rejected
                                continue

                    if breached:
                        # Trade Entered!
                        entry_price = trigger_price
                        entry_time = t_str
                        pos_sl = sl_price
                        pos_target = target_price
                        pos_risk = abs(entry_price - pos_sl)

                        t1_booked = False
                        cost_sl_active = False
                        booked_pnl = 0.0
                        exit_price = None
                        exit_reason = None
                        exit_time = None

                        # Simulate Trade from candle i onwards
                        for j in range(i, len(day_df)):
                            sim_bar = day_df.iloc[j]
                            sim_time = day_df.index[j].strftime('%H:%M')

                            if direction == 'LONG':
                                # Target 1 Check
                                if not t1_booked and sim_bar['High'] >= pos_target:
                                    t1_booked = True
                                    cost_sl_active = True
                                    pos_sl = entry_price # Cost SL
                                    booked_pnl = half_qty * (pos_target - entry_price)

                                # SL Hit Check
                                if sim_bar['Low'] <= pos_sl:
                                    exit_price = pos_sl
                                    exit_time = sim_time
                                    exit_reason = "Cost SL Hit" if cost_sl_active else "Initial SL Hit"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    rem_pnl = rem_qty * (exit_price - entry_price)
                                    trade_total_pnl = booked_pnl + rem_pnl
                                    break

                                # EOD 15:00 Close
                                if sim_time >= "15:00":
                                    exit_price = sim_bar['Close']
                                    exit_time = sim_time
                                    exit_reason = "EOD Square-Off at 15:00"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    rem_pnl = rem_qty * (exit_price - entry_price)
                                    trade_total_pnl = booked_pnl + rem_pnl
                                    break
                            else: # SHORT
                                # Target 1 Check
                                if not t1_booked and sim_bar['Low'] <= pos_target:
                                    t1_booked = True
                                    cost_sl_active = True
                                    pos_sl = entry_price # Cost SL
                                    booked_pnl = half_qty * (entry_price - pos_target)

                                # SL Hit Check
                                if sim_bar['High'] >= pos_sl:
                                    exit_price = pos_sl
                                    exit_time = sim_time
                                    exit_reason = "Cost SL Hit" if cost_sl_active else "Initial SL Hit"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    rem_pnl = rem_qty * (entry_price - exit_price)
                                    trade_total_pnl = booked_pnl + rem_pnl
                                    break

                                # EOD 15:00 Close
                                if sim_time >= "15:00":
                                    exit_price = sim_bar['Close']
                                    exit_time = sim_time
                                    exit_reason = "EOD Square-Off at 15:00"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    rem_pnl = rem_qty * (entry_price - exit_price)
                                    trade_total_pnl = booked_pnl + rem_pnl
                                    break

                        day_pnl += trade_total_pnl
                        day_trades.append({
                            'Date': trade_date,
                            'Symbol': sym,
                            'Direction': direction,
                            'Entry_Time': entry_time,
                            'Entry_Price': entry_price,
                            'SL': sl_price,
                            'Target1': target_price,
                            'Exit_Time': exit_time,
                            'Exit_Price': exit_price,
                            'Exit_Reason': exit_reason,
                            'Total_Qty': total_qty,
                            'PnL': trade_total_pnl,
                            'Won': trade_total_pnl > 0
                        })
                        all_trades.append(day_trades[-1])
                        break # Symbol trade complete

        daily_summaries.append({
            'Date': trade_date,
            'Trades_Taken': len(day_trades),
            'Daily_PnL': day_pnl
        })

    # ==============================================================================
    # Performance Reporting
    # ==============================================================================
    trades_df = pd.DataFrame(all_trades)
    print("================================================================================")
    print("                             DETAILED LVR TRADE LOG")
    print("================================================================================")
    if not trades_df.empty:
        for _, r in trades_df.iterrows():
            dir_icon = "🟢 LONG " if r['Direction'] == 'LONG' else "🔴 SHORT"
            pnl_str = f"+₹{r['PnL']:,.2f}" if r['PnL'] >= 0 else f"-₹{abs(r['PnL']):,.2f}"
            print(f" [{r['Date']}] {r['Entry_Time']} | {dir_icon} {r['Symbol']:<12} | Entry: ₹{r['Entry_Price']:<7.2f} | SL: ₹{r['SL']:<7.2f} | Exit: ₹{r['Exit_Price']:<7.2f} ({r['Exit_Time']}) | PnL: {pnl_str:<11} | {r['Exit_Reason']}")
    else:
        print(" No trades met the full LVR + VWAP + 15M Range criteria.")

    print("\n================================================================================")
    print("                           DAILY PERFORMANCE SUMMARY")
    print("================================================================================")
    daily_df = pd.DataFrame(daily_summaries)
    print(daily_df.to_string(index=False))

    total_pnl = trades_df['PnL'].sum() if not trades_df.empty else 0.0
    total_trades = len(trades_df)
    win_trades = len(trades_df[trades_df['Won']]) if not trades_df.empty else 0
    loss_trades = total_trades - win_trades
    win_rate = (win_trades / total_trades * 100.0) if total_trades > 0 else 0.0

    gross_profit = trades_df[trades_df['PnL'] > 0]['PnL'].sum() if not trades_df.empty else 0.0
    gross_loss = abs(trades_df[trades_df['PnL'] < 0]['PnL'].sum()) if not trades_df.empty else 0.0
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else 999.0

    print("\n================================================================================")
    print("                           1-MONTH FINAL METRICS")
    print("================================================================================")
    print(f" Total Trading Days:          {len(daily_df)}")
    print(f" Total Trades Executed:       {total_trades}")
    print(f" Winning Trades:              {win_trades} ({win_rate:.1f}%)")
    print(f" Losing Trades:               {loss_trades}")
    print(f" Profit Factor:               {profit_factor:.2f}")
    print(f" Gross Profit:                ₹{gross_profit:,.2f}")
    print(f" Gross Loss:                  ₹{gross_loss:,.2f}")
    print(f" Total Net PnL (2 Lots):      {'₹{:,.2f}'.format(total_pnl)}")
    print("================================================================================\n")

if __name__ == '__main__':
    run_lvr_backtest()
