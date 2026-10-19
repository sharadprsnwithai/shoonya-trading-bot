import sqlite3
import datetime
import math
import numpy as np
import pandas as pd
from scipy.stats import norm

def get_call_strike_for_delta(S, T, r, sigma, target_delta):
    # target_delta between 0 and 1 (e.g. 0.20)
    # Call delta = norm.cdf(d1). Higher strike -> lower delta.
    # We want delta = target_delta.
    # d1 = norm.ppf(target_delta)
    # d1 = (ln(S/K) + (r + 0.5*sigma^2)T) / (sigma*sqrt(T))
    # ln(S/K) = d1 * sigma * sqrt(T) - (r + 0.5*sigma^2)T
    # S/K = exp(...)
    # K = S / exp(...)
    if T <= 0:
        return round(S / 50) * 50
    d1 = norm.ppf(target_delta)
    val = d1 * sigma * math.sqrt(T) + (r + 0.5 * sigma ** 2) * T
    K = S / math.exp(val)
    return round(K / 50) * 50

def get_put_strike_for_delta(S, T, r, sigma, target_delta):
    # target_delta between -1 and 0 (e.g. -0.20)
    # Put delta = -norm.cdf(-d1) = target_delta => norm.cdf(-d1) = -target_delta
    # -d1 = norm.ppf(-target_delta) => d1 = -norm.ppf(-target_delta)
    if T <= 0:
        return round(S / 50) * 50
    d1 = -norm.ppf(-target_delta)
    val = d1 * sigma * math.sqrt(T) + (r + 0.5 * sigma ** 2) * T
    K = S / math.exp(val)
    return round(K / 50) * 50

def black_scholes_call(S, K, T, r, sigma):
    if T <= 0:
        return max(0.0, S - K)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return S * norm.cdf(d1) - K * math.exp(-r * T) * norm.cdf(d2)

def black_scholes_put(S, K, T, r, sigma):
    if T <= 0:
        return max(0.0, K - S)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return K * math.exp(-r * T) * norm.cdf(-d2) - S * norm.cdf(-d1)

def run_backtest():
    conn = sqlite3.connect('data/trading_bot.db')
    query = "SELECT timestamp, open, high, low, close FROM historical_candles WHERE symbol = 'NIFTY 50' ORDER BY timestamp ASC"
    df = pd.read_sql(query, conn)
    conn.close()

    if df.empty:
        print("No historical data found for NIFTY 50.")
        return

    df['datetime'] = pd.to_datetime(df['timestamp'], unit='s', utc=True).dt.tz_convert('Asia/Kolkata')
    df['date'] = df['datetime'].dt.date
    df['weekday'] = df['datetime'].dt.weekday

    df = df[df['date'] >= datetime.date(2025, 10, 1)].copy()

    mon_tues = df[df['weekday'].isin([0, 1])].sort_values('datetime').copy()
    mondays = mon_tues[mon_tues['weekday'] == 0]

    trades = []
    r = 0.06
    sigma = 0.14
    margin_per_lot_set = 110000.0
    lot_size = 25

    sl_inr = margin_per_lot_set * 0.02
    sl_pts = sl_inr / lot_size

    for idx, mon_row in mondays.iterrows():
        entry_date = mon_row['date']
        S_entry = mon_row['close']

        tues_rows = mon_tues[(mon_tues['date'] >= entry_date) & (mon_tues['weekday'] == 1)]
        if tues_rows.empty:
            continue
        tues_row = tues_rows.iloc[0]
        tues_date = tues_row['date']
        S_exit = tues_row['close']

        T_entry = 1.0 / 365.25
        T_exit = 0.0

        # Delta strikes:
        # Short: 0.20 Delta (2 lots)
        # Wings: 0.10 Delta and 0.05 Delta (1 lot each)
        k_call_sell  = get_call_strike_for_delta(S_entry, T_entry, r, sigma, 0.20)
        k_call_buy1  = get_call_strike_for_delta(S_entry, T_entry, r, sigma, 0.10)
        k_call_buy2  = get_call_strike_for_delta(S_entry, T_entry, r, sigma, 0.05)

        k_put_sell   = get_put_strike_for_delta(S_entry, T_entry, r, sigma, -0.20)
        k_put_buy1   = get_put_strike_for_delta(S_entry, T_entry, r, sigma, -0.10)
        k_put_buy2   = get_put_strike_for_delta(S_entry, T_entry, r, sigma, -0.05)

        c_s_init  = black_scholes_call(S_entry, k_call_sell, T_entry, r, sigma) * 2
        c_b1_init = black_scholes_call(S_entry, k_call_buy1, T_entry, r, sigma) * 1
        c_b2_init = black_scholes_call(S_entry, k_call_buy2, T_entry, r, sigma) * 1

        p_s_init  = black_scholes_put(S_entry, k_put_sell, T_entry, r, sigma) * 2
        p_b1_init = black_scholes_put(S_entry, k_put_buy1, T_entry, r, sigma) * 1
        p_b2_init = black_scholes_put(S_entry, k_put_buy2, T_entry, r, sigma) * 1

        cost_calls = c_s_init - (c_b1_init + c_b2_init)
        cost_puts  = p_s_init - (p_b1_init + p_b2_init)
        net_initial_premium = cost_calls + cost_puts

        c_s_exit  = black_scholes_call(S_exit, k_call_sell, T_exit, r, sigma) * 2
        c_b1_exit = black_scholes_call(S_exit, k_call_buy1, T_exit, r, sigma) * 1
        c_b2_exit = black_scholes_call(S_exit, k_call_buy2, T_exit, r, sigma) * 1

        p_s_exit  = black_scholes_put(S_exit, k_put_sell, T_exit, r, sigma) * 2
        p_b1_exit = black_scholes_put(S_exit, k_put_buy1, T_exit, r, sigma) * 1
        p_b2_exit = black_scholes_put(S_exit, k_put_buy2, T_exit, r, sigma) * 1

        exit_cost_calls = c_s_exit - (c_b1_exit + c_b2_exit)
        exit_cost_puts  = p_s_exit - (p_b1_exit + p_b2_exit)
        net_exit_value  = exit_cost_calls + exit_cost_puts

        pnl_pts_actual = net_initial_premium - net_exit_value

        if pnl_pts_actual <= -sl_pts:
            final_pnl_pts = -sl_pts
            result = "SL_HIT"
        else:
            final_pnl_pts = pnl_pts_actual
            result = "EXPIRED"

        pnl_inr = final_pnl_pts * lot_size

        trades.append({
            'entry_date': entry_date,
            'initial_premium': net_initial_premium,
            'pnl_inr': pnl_inr,
            'result': result
        })

    df_trades = pd.DataFrame(trades)
    if df_trades.empty:
        return

    total_pnl_inr = df_trades['pnl_inr'].sum()
    roi_pct = (total_pnl_inr / margin_per_lot_set) * 100.0

    print("\n==========================================================================")
    print(" CORRECTED DELTA-BASED BATMAN (0.20 Short, 0.10 & 0.05 Wings)            ")
    print("==========================================================================")
    print(f"Total Weekly Expiries Tested: {len(df_trades)}")
    print(f"Avg Net Initial Value: Rs. {df_trades['initial_premium'].mean() * lot_size:,.2f}")
    print(f"Total Cumulative P&L (INR): Rs. {total_pnl_inr:,.2f}")
    print(f"Total Return on Capital (ROI): {roi_pct:.2f}%")
    print(f"Annualized ROI: {roi_pct:.2f}% over ~1 year")
    print(df_trades['result'].value_counts())

if __name__ == '__main__':
    run_backtest()
