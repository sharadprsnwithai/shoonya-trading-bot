import sys
import os
sys.path.insert(0, os.path.abspath('.'))
import math
import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from collections import defaultdict
import scripts.compare_options_structures as cos

# Fetch historical 5m Nifty data
df_nifty = yf.download('^NSEI', interval='5m', period='60d', progress=False)
if isinstance(df_nifty.columns, pd.MultiIndex):
    df_nifty.columns = df_nifty.columns.get_level_values(0)
df_nifty.index = pd.to_datetime(df_nifty.index).tz_convert('Asia/Kolkata')
df_5m = df_nifty.copy()
df_5m['Volume'] = 10000
all_dates = sorted(list(set(df_5m.index.date)))

print("=========================================================================================================")
print(" 🚀 CAPITAL EFFICIENCY & RETURN ON CAPITAL (ROC) COMPARISON (Capital: ₹3,50,000)")
print("=========================================================================================================")
print(f"{'Strategy & Structure':<36} | {'Lots':<5} | {'Margin (₹)':<11} | {'Net P&L (₹)':<14} | {'Monthly ROC (%)':<15} | {'Max DD (₹)'}")
print("-" * 105)

# Structure 1: Naked ATM Option Selling (2 Lots) -> Margin ~₹3,40,000
# Structure 2: Hedged Credit Spread (Sell ATM + Buy 200 OTM Hedge) -> Margin ₹42,000/lot -> 8 Lots on ₹3.5L
# Structure 3: ATM Option Buying (0.50 Delta) -> Capital ₹15,000/lot -> 4 Lots
# Structure 4: LVR Stock Futures Strategy (2 Lots on Stock Futures)

# Simulation
trades_naked = []
trades_spread = []
trades_buy = []

lot_size = 75

for trade_date in all_dates:
    day_df_5m = df_5m[df_5m.index.date == trade_date].copy()
    if len(day_df_5m) < 15: continue

    day_df_5m['VWAP_5m'] = cos.calculate_vwap_from_df(day_df_5m)
    day_df_15m = day_df_5m.resample('15min', closed='left', label='left').agg({'Open':'first','High':'max','Low':'min','Close':'last','Volume':'sum'}).dropna()
    if len(day_df_15m) < 5: continue
    day_df_15m['VWAP_15m'] = cos.calculate_vwap_from_df(day_df_15m)
    day_df_15m['VWAP_15m_prev'] = day_df_15m['VWAP_15m'].shift(1)
    day_df_15m['1Hr_Mom_Pct'] = (day_df_15m['Close'] - day_df_15m['Close'].shift(4)) / day_df_15m['Close'].shift(4) * 100.0

    daily_trades_count = 0; daily_losses_count = 0; in_position = False; active_pos = None
    day_of_week = trade_date.weekday()
    dte = (3 - day_of_week) if day_of_week <= 3 else (10 - day_of_week)

    for i in range(12, len(day_df_5m)):
        candle_5m = day_df_5m.iloc[i]
        c_time = candle_5m.name.time()

        if in_position and active_pos is not None:
            pos_dir = active_pos['direction']
            entry_spot = active_pos['entry_spot']
            entry_time = active_pos['entry_time']
            curr_spot = float(candle_5m['Close'])
            b_high = float(candle_5m['High'])
            b_low = float(candle_5m['Low'])
            bars_held = (candle_5m.name - entry_time).total_seconds() / 300.0
            curr_dte = max(0.2, dte - (bars_held / 75.0))

            exit_reason = None
            exit_spot = None
            if c_time >= datetime.time(15, 10):
                exit_reason = '15:10_EOD'; exit_spot = curr_spot
            elif pos_dir == 'LONG':
                if b_low <= (entry_spot - 40): exit_reason = 'SL'; exit_spot = entry_spot - 40
                elif b_high >= (entry_spot + 35): exit_reason = 'TP'; exit_spot = entry_spot + 35
            else:
                if b_high >= (entry_spot + 40): exit_reason = 'SL'; exit_spot = entry_spot + 40
                elif b_low <= (entry_spot - 35): exit_reason = 'TP'; exit_spot = entry_spot - 35

            if exit_reason is not None:
                opt_type = 'PE' if pos_dir == 'LONG' else 'CE'
                atm_strike = cos.round_strike(entry_spot, 50)
                hedge_strike = cos.round_strike(entry_spot - 200, 50) if pos_dir == 'LONG' else cos.round_strike(entry_spot + 200, 50)

                # ATM Option Premium
                atm_entry_p = cos.estimate_option_premium(entry_spot, atm_strike, opt_type, iv=0.13, dte_days=dte)
                atm_exit_p = cos.estimate_option_premium(exit_spot, atm_strike, opt_type, iv=0.13, dte_days=curr_dte)

                # Hedge Option Premium (200 OTM)
                hedge_entry_p = cos.estimate_option_premium(entry_spot, hedge_strike, opt_type, iv=0.13, dte_days=dte)
                hedge_exit_p = cos.estimate_option_premium(exit_spot, hedge_strike, opt_type, iv=0.13, dte_days=curr_dte)

                # P&L 1: Naked ATM Selling (2 Lots)
                pts_naked = (atm_entry_p - atm_exit_p)
                pnl_naked = (pts_naked * 75 * 2) - (75.0 * 2)
                trades_naked.append({'pnl': pnl_naked, 'date': trade_date})

                # P&L 2: Hedged Credit Spread (8 Lots on ₹3.5L)
                # Spread Net Premium = Sold ATM - Bought Hedge
                spread_pts = (atm_entry_p - atm_exit_p) - (hedge_entry_p - hedge_exit_p)
                pnl_spread = (spread_pts * 75 * 8) - (110.0 * 8)
                trades_spread.append({'pnl': pnl_spread, 'date': trade_date})

                # P&L 3: ATM Option Buying (4 Lots on ₹3.5L)
                pts_buy = (atm_exit_p - atm_entry_p)
                pnl_buy = (pts_buy * 75 * 4) - (85.0 * 4)
                trades_buy.append({'pnl': pnl_buy, 'date': trade_date})

                in_position = False
                active_pos = None
                if exit_reason == '15:10_EOD': break

        if not in_position:
            if daily_trades_count >= 4 or daily_losses_count >= 2: continue
            if c_time >= datetime.time(14, 15): continue
            curr_5m_timestamp = candle_5m.name
            past_15m_bars = day_df_15m[day_df_15m.index <= (curr_5m_timestamp - pd.Timedelta(minutes=5))]
            if len(past_15m_bars) < 4: continue
            latest_15m = past_15m_bars.iloc[-1]
            close_15m = float(latest_15m['Close'])
            vwap_15m = float(latest_15m['VWAP_15m'])
            vwap_15m_prev = float(latest_15m['VWAP_15m_prev']) if not np.isnan(latest_15m['VWAP_15m_prev']) else vwap_15m
            mom_1hr = float(latest_15m['1Hr_Mom_Pct']) if not np.isnan(latest_15m['1Hr_Mom_Pct']) else 0.0
            long_drift = (close_15m > vwap_15m) and (vwap_15m > vwap_15m_prev) and (mom_1hr >= 0.12)
            short_drift = (close_15m < vwap_15m) and (vwap_15m < vwap_15m_prev) and (mom_1hr <= -0.12)
            c_open = float(candle_5m['Open']); c_close = float(candle_5m['Close'])
            is_red_pullback = c_close < c_open; is_green_pullback = c_close > c_open

            if i + 1 < len(day_df_5m):
                next_candle = day_df_5m.iloc[i + 1]
                next_open = float(next_candle['Open'])
                if long_drift and is_red_pullback:
                    in_position = True; active_pos = {'direction': 'LONG', 'entry_spot': next_open, 'entry_time': next_candle.name}; daily_trades_count += 1
                elif short_drift and is_green_pullback:
                    in_position = True; active_pos = {'direction': 'SHORT', 'entry_spot': next_open, 'entry_time': next_candle.name}; daily_trades_count += 1

# Calculate comparison
structures_data = [
    ("1. Naked ATM Selling (2 Lots)", trades_naked, 2, 340000),
    ("2. Hedged Credit Spread (8 Lots)", trades_spread, 8, 336000),
    ("3. ATM Option Buying (4 Lots)", trades_buy, 4, 60000)
]

for name, t_list, lots, margin in structures_data:
    df_t = pd.DataFrame(t_list)
    net_pnl = df_t['pnl'].sum()
    monthly_roc = ((net_pnl / 2.0) / 350000.0) * 100.0 # ~2 months backtest period
    cum = df_t.groupby('date')['pnl'].sum().cumsum()
    max_dd = (cum.cummax() - cum).max() if not cum.empty else 0.0
    pnl_str = f"+₹{net_pnl:,.0f}" if net_pnl >= 0 else f"-₹{abs(net_pnl):,.0f}"
    print(f"{name:<36} | {lots:<5d} | ₹{margin:<10,d} | {pnl_str:<14} | {monthly_roc:+6.2f}% / month | ₹{max_dd:<10,.0f}")

print("=" * 105)
