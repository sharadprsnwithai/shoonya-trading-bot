import sys
import io
import math
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

UNIVERSE = [
    "RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "ICICIBANK.NS", 
    "SBIN.NS", "TATASTEEL.NS", "MARUTI.NS", "SUNPHARMA.NS", "BHARTIARTL.NS", 
    "AXISBANK.NS", "KOTAKBANK.NS", "HINDALCO.NS", "BHEL.NS", "ITC.NS", 
    "LT.NS", "BAJFINANCE.NS", "M&M.NS", "NTPC.NS", "COALINDIA.NS",
    "JINDALSTEL.NS", "VEDL.NS", "CIPLA.NS", "DRREDDY.NS",
    "WIPRO.NS", "TECHM.NS", "HEROMOTOCO.NS", "EICHERMOT.NS", "BAJAJ-AUTO.NS"
]

LOT_SIZES = {
    "RELIANCE": 250, "TCS": 175, "INFY": 400, "HDFCBANK": 550, "ICICIBANK": 700,
    "SBIN": 750, "TATASTEEL": 5500, "MARUTI": 50, "SUNPHARMA": 350, "BHARTIARTL": 475,
    "AXISBANK": 625, "KOTAKBANK": 400, "HINDALCO": 1400, "BHEL": 2625, "ITC": 1600,
    "LT": 175, "BAJFINANCE": 125, "M&M": 350, "NTPC": 1500, "COALINDIA": 2100,
    "JINDALSTEL": 625, "VEDL": 1150, "CIPLA": 650, "DRREDDY": 125,
    "WIPRO": 1500, "TECHM": 600, "HEROMOTOCO": 150, "EICHERMOT": 175, "BAJAJ-AUTO": 75
}

def calculate_vwap(df):
    vol = df['Volume'].replace(0, 1)
    typical = (df['High'] + df['Low'] + df['Close']) / 3.0
    return (typical * vol).cumsum() / vol.cumsum()

def run_dynamic_lvr_backtest(fixed_risk_per_trade=2500.0):
    print("================================================================================")
    print(f" 🚀 LVR BACKTEST WITH DYNAMIC POSITION SIZING (Fixed Risk = ₹{fixed_risk_per_trade:,.0f}/Trade)")
    print("================================================================================")

    data = {}
    for ticker in UNIVERSE:
        sym = ticker.replace(".NS", "")
        df = yf.download(ticker, interval='5m', period='1mo', progress=False)
        if df.empty: continue
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.get_level_values(0)
        df.index = pd.to_datetime(df.index).tz_convert('Asia/Kolkata')
        data[sym] = df

    all_dates = sorted(list(set(d for df in data.values() for d in df.index.date)))
    all_trades = []

    for trade_date in all_dates:
        stock_moves = []
        for sym, df in data.items():
            day_data = df[df.index.date == trade_date]
            if len(day_data) < 4: continue
            c1 = day_data.iloc[0]
            c2 = day_data.iloc[1]
            pct_chg = ((c2['Close'] - c1['Open']) / c1['Open']) * 100.0
            stock_moves.append((sym, pct_chg, day_data))

        if not stock_moves: continue
        gainers = sorted([s for s in stock_moves if s[1] >= 0.8], key=lambda x: x[1], reverse=True)[:5]
        losers = sorted([s for s in stock_moves if s[1] <= -0.8], key=lambda x: x[1])[:5]
        candidates = [(s[0], 'LONG', s[2]) for s in gainers] + [(s[0], 'SHORT', s[2]) for s in losers]

        day_trades_count = 0
        for sym, direction, day_df in candidates:
            if day_trades_count >= 2: break
            c1_3 = day_df.iloc[:3]
            range_15m_high = c1_3['High'].max()
            range_15m_low = c1_3['Low'].min()
            baseline_lowest_vol = c1_3['Volume'].replace(0, np.nan).min()
            if pd.isna(baseline_lowest_vol): continue
            vwap_series = calculate_vwap(day_df)

            trigger_armed = False
            trigger_price = None
            sl_price = None
            target_price = None
            armed_idx = 0
            rolling_lowest_vol = baseline_lowest_vol

            for i in range(3, len(day_df)):
                c = day_df.iloc[i]
                t_str = day_df.index[i].strftime('%H:%M')
                if t_str > "11:00" and not trigger_armed: break

                is_opposite = (c['Close'] < c['Open']) if direction == 'LONG' else (c['Close'] > c['Open'])
                if is_opposite and c['Volume'] > 0 and c['Volume'] < rolling_lowest_vol:
                    rolling_lowest_vol = c['Volume']
                    trigger_armed = True
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

                if trigger_armed:
                    if (i - armed_idx) > 6:
                        trigger_armed = False
                        continue
                    vwap = vwap_series.iloc[i]
                    breached = False
                    if direction == 'LONG':
                        if c['High'] >= trigger_price:
                            if trigger_price > vwap and trigger_price > range_15m_high: breached = True
                            else: trigger_armed = False; continue
                    else:
                        if c['Low'] <= trigger_price:
                            if trigger_price < vwap and trigger_price < range_15m_low: breached = True
                            else: trigger_armed = False; continue

                    if breached:
                        entry_price = trigger_price
                        pos_sl = sl_price
                        pos_target = target_price
                        sl_dist = abs(entry_price - pos_sl)
                        if sl_dist <= 0: continue

                        # Dynamic Sizing: Sized precisely to fixed risk budget
                        total_qty = max(1, int(fixed_risk_per_trade / sl_dist))
                        half_qty = total_qty // 2

                        t1_booked = False
                        cost_sl_active = False
                        booked_pnl = 0.0
                        exit_price = None
                        exit_reason = None

                        for j in range(i, len(day_df)):
                            sim_bar = day_df.iloc[j]
                            sim_time = day_df.index[j].strftime('%H:%M')

                            if direction == 'LONG':
                                if not t1_booked and sim_bar['High'] >= pos_target:
                                    t1_booked = True
                                    cost_sl_active = True
                                    pos_sl = entry_price
                                    booked_pnl = half_qty * (pos_target - entry_price)

                                if sim_bar['Low'] <= pos_sl:
                                    exit_price = pos_sl
                                    exit_reason = "Cost SL Hit" if cost_sl_active else "Initial SL Hit"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    trade_total_pnl = booked_pnl + (rem_qty * (exit_price - entry_price))
                                    break

                                if sim_time >= "15:00":
                                    exit_price = sim_bar['Close']
                                    exit_reason = "EOD 15:00 Close"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    trade_total_pnl = booked_pnl + (rem_qty * (exit_price - entry_price))
                                    break
                            else:
                                if not t1_booked and sim_bar['Low'] <= pos_target:
                                    t1_booked = True
                                    cost_sl_active = True
                                    pos_sl = entry_price
                                    booked_pnl = half_qty * (entry_price - pos_target)

                                if sim_bar['High'] >= pos_sl:
                                    exit_price = pos_sl
                                    exit_reason = "Cost SL Hit" if cost_sl_active else "Initial SL Hit"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    trade_total_pnl = booked_pnl + (rem_qty * (entry_price - exit_price))
                                    break

                                if sim_time >= "15:00":
                                    exit_price = sim_bar['Close']
                                    exit_reason = "EOD 15:00 Close"
                                    rem_qty = half_qty if t1_booked else total_qty
                                    trade_total_pnl = booked_pnl + (rem_qty * (entry_price - exit_price))
                                    break

                        day_trades_count += 1
                        all_trades.append({
                            'Date': trade_date,
                            'Symbol': sym,
                            'Direction': direction,
                            'Entry_Price': entry_price,
                            'SL': sl_price,
                            'Exit_Price': exit_price,
                            'Qty': total_qty,
                            'PnL': trade_total_pnl,
                            'Won': trade_total_pnl > 0,
                            'Reason': exit_reason
                        })
                        break

    trades_df = pd.DataFrame(all_trades)
    print("================================================================================")
    print("                             DETAILED LVR TRADE LOG")
    print("================================================================================")
    for _, r in trades_df.iterrows():
        dir_icon = "🟢 LONG " if r['Direction'] == 'LONG' else "🔴 SHORT"
        pnl_str = f"+₹{r['PnL']:,.2f}" if r['PnL'] >= 0 else f"-₹{abs(r['PnL']):,.2f}"
        print(f" [{r['Date']}] {dir_icon} {r['Symbol']:<12} | Entry: ₹{r['Entry_Price']:<7.2f} | SL: ₹{r['SL']:<7.2f} | Exit: ₹{r['Exit_Price']:<7.2f} | Qty: {r['Qty']:<5} | PnL: {pnl_str:<11} | {r['Reason']}")

    total_pnl = trades_df['PnL'].sum()
    win_trades = len(trades_df[trades_df['Won']])
    total_trades = len(trades_df)
    win_rate = (win_trades / total_trades * 100.0) if total_trades > 0 else 0
    gross_profit = trades_df[trades_df['PnL'] > 0]['PnL'].sum()
    gross_loss = abs(trades_df[trades_df['PnL'] < 0]['PnL'].sum())
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else 999.0

    print("\n================================================================================")
    print("                           DYNAMIC SIZING SUMMARY")
    print("================================================================================")
    print(f" Total Trades Executed:       {total_trades}")
    print(f" Winning Trades:              {win_trades} / {total_trades} ({win_rate:.1f}%)")
    print(f" Profit Factor:               {profit_factor:.2f}")
    print(f" Gross Profit:                ₹{gross_profit:,.2f}")
    print(f" Gross Loss:                  ₹{gross_loss:,.2f}")
    print(f" Total Net PnL:               {'₹{:,.2f}'.format(total_pnl)}")
    print("================================================================================\n")

if __name__ == '__main__':
    run_dynamic_lvr_backtest(2500.0)
