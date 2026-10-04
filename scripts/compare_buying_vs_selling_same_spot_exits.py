import sys
import io
import math
import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from collections import defaultdict

# Ensure UTF-8 output
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_vwap_from_df(df):
    vol = df['Volume'].replace(0, 1)
    typical = (df['High'] + df['Low'] + df['Close']) / 3.0
    return (typical * vol).cumsum() / vol.cumsum()

def round_strike(spot_price, step=50):
    return round(spot_price / step) * step

def estimate_option_premium(spot_price, strike_price, opt_type, iv=0.13, dte_days=3.5):
    dte_years = max(0.5, dte_days) / 365.0
    sigma_sqrt_t = iv * math.sqrt(dte_years)
    d1 = (math.log(spot_price / strike_price) + (0.5 * iv * iv) * dte_years) / sigma_sqrt_t
    d2 = d1 - sigma_sqrt_t

    def norm_cdf(x):
        return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))

    if opt_type == 'CE':
        prem = (spot_price * norm_cdf(d1)) - (strike_price * norm_cdf(d2))
    else:
        prem = (strike_price * norm_cdf(-d2)) - (spot_price * norm_cdf(-d1))

    return max(1.50, prem)

def run_identical_spot_trades_comparison():
    print("=========================================================================================================")
    print(" 🔬 CONTROLLED TEST: IDENTICAL SIGNALS & EXITS ACROSS ALL 3 OPTION STRUCTURES")
    print(" • Entry & Exit are governed strictly by NIFTY Index Spot (+30 pts TP / -40 pts SL / 15:10 EOD)")
    print(" • Total Trades are 100% IDENTICAL across all 3 structures to isolate the true pricing & Greek edge")
    print("=========================================================================================================\n")

    df_nifty = yf.download('^NSEI', interval='5m', period='60d', progress=False)
    if isinstance(df_nifty.columns, pd.MultiIndex):
        df_nifty.columns = df_nifty.columns.get_level_values(0)
    df_nifty.index = pd.to_datetime(df_nifty.index).tz_convert('Asia/Kolkata')
    df_5m = df_nifty.copy()
    df_5m['Volume'] = 10000

    all_dates = sorted(list(set(df_5m.index.date)))
    lot_size = 75

    target_pts = 30.0
    sl_pts = 40.0

    structures = [
        ("1. NIFTY Futures (Baseline)", "FUTURES", 0, "BUY_SELL"),
        ("2. ATM Option Selling", "ATM_SELL", 0, "SELL"),
        ("3. 100-Pts OTM Option Selling", "100_OTM_SELL", 100, "SELL"),
        ("4. 200-Pts OTM Option Buying", "200_OTM_BUY", 200, "BUY"),
        ("5. 400-Pts OTM Option Selling", "400_OTM_SELL", 400, "SELL")
    ]

    all_results = defaultdict(list)

    for trade_date in all_dates:
        day_df_5m = df_5m[df_5m.index.date == trade_date].copy()
        if len(day_df_5m) < 15: continue

        day_df_5m['VWAP_5m'] = calculate_vwap_from_df(day_df_5m)
        day_df_15m = day_df_5m.resample('15min', closed='left', label='left').agg({
            'Open': 'first', 'High': 'max', 'Low': 'min', 'Close': 'last', 'Volume': 'sum'
        }).dropna()

        if len(day_df_15m) < 5: continue

        day_df_15m['VWAP_15m'] = calculate_vwap_from_df(day_df_15m)
        day_df_15m['VWAP_15m_prev'] = day_df_15m['VWAP_15m'].shift(1)
        day_df_15m['1Hr_Mom_Pct'] = (day_df_15m['Close'] - day_df_15m['Close'].shift(4)) / day_df_15m['Close'].shift(4) * 100.0

        daily_trades_count = 0
        daily_losses_count = 0
        in_position = False
        active_pos = None

        day_of_week = trade_date.weekday()
        dte = (3 - day_of_week) if day_of_week <= 3 else (10 - day_of_week)

        for i in range(12, len(day_df_5m)):
            candle_5m = day_df_5m.iloc[i]
            c_time = candle_5m.name.time()

            # 1. Manage Active Position based on SPOT INDEX prices
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

                # A. Hard Exit at 15:10 IST
                if c_time >= datetime.time(15, 10):
                    exit_reason = "15:10_EOD_EXIT"
                    exit_spot = curr_spot
                # B. Spot Target or Stop Loss Hit
                elif pos_dir == 'LONG':
                    if b_low <= (entry_spot - sl_pts):
                        exit_reason = "STOP_LOSS_HIT"
                        exit_spot = entry_spot - sl_pts
                    elif b_high >= (entry_spot + target_pts):
                        exit_reason = "TARGET_HIT"
                        exit_spot = entry_spot + target_pts
                else: # SHORT
                    if b_high >= (entry_spot + sl_pts):
                        exit_reason = "STOP_LOSS_HIT"
                        exit_spot = entry_spot + sl_pts
                    elif b_low <= (entry_spot - target_pts):
                        exit_reason = "TARGET_HIT"
                        exit_spot = entry_spot - target_pts

                if exit_reason is not None:
                    # Compute exact P&L for each structure on this exact trade!
                    for struct_name, struct_key, offset_pts, action in structures:
                        if struct_key == "FUTURES":
                            pts = (exit_spot - entry_spot) if pos_dir == 'LONG' else (entry_spot - exit_spot)
                            pnl = pts * lot_size
                        else:
                            # Options pricing
                            opt_type = 'PE' if pos_dir == 'LONG' else 'CE'
                            if action == "SELL":
                                strike = round_strike(entry_spot - offset_pts, 50) if pos_dir == 'LONG' else round_strike(entry_spot + offset_pts, 50)
                            else: # BUY
                                strike = round_strike(entry_spot + offset_pts, 50) if pos_dir == 'LONG' else round_strike(entry_spot - offset_pts, 50)

                            entry_prem = estimate_option_premium(entry_spot, strike, opt_type, iv=0.13, dte_days=dte)
                            exit_prem = estimate_option_premium(exit_spot, strike, opt_type, iv=0.13, dte_days=curr_dte)

                            if action == "SELL":
                                pts = entry_prem - exit_prem
                            else: # BUY
                                pts = exit_prem - entry_prem
                            pnl = pts * lot_size

                        all_results[struct_name].append({
                            'date': trade_date, 'direction': pos_dir, 'entry_spot': entry_spot, 'exit_spot': exit_spot,
                            'pnl': pnl, 'is_win': pnl > 0, 'exit_reason': exit_reason
                        })

                    if (exit_spot - entry_spot < 0 if pos_dir == 'LONG' else entry_spot - exit_spot < 0):
                        daily_losses_count += 1
                    in_position = False
                    active_pos = None

                    if exit_reason == "15:10_EOD_EXIT":
                        break

            # 2. Check Entry Trigger on 5m candle
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

                long_drift = (close_15m > vwap_15m) and (vwap_15m > vwap_15m_prev) and (mom_1hr >= 0.10)
                short_drift = (close_15m < vwap_15m) and (vwap_15m < vwap_15m_prev) and (mom_1hr <= -0.10)

                c_open = float(candle_5m['Open'])
                c_close = float(candle_5m['Close'])
                is_red_pullback = c_close < c_open
                is_green_pullback = c_close > c_open

                if i + 1 < len(day_df_5m):
                    next_candle = day_df_5m.iloc[i + 1]
                    next_open = float(next_candle['Open'])

                    if long_drift and is_red_pullback:
                        in_position = True
                        active_pos = {'direction': 'LONG', 'entry_spot': next_open, 'entry_time': next_candle.name}
                        daily_trades_count += 1
                    elif short_drift and is_green_pullback:
                        in_position = True
                        active_pos = {'direction': 'SHORT', 'entry_spot': next_open, 'entry_time': next_candle.name}
                        daily_trades_count += 1

    print("="*115)
    print(f"{'Option Structure':<30} | {'Trades':<6} | {'Win %':<6} | {'PF':<5} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12} | {'Avg Trade':<10}")
    print("-" * 115)
    for struct_name, _, _, _ in structures:
        trades_list = all_results[struct_name]
        df_t = pd.DataFrame(trades_list)
        wins = df_t[df_t['is_win'] == True]
        losses = df_t[df_t['is_win'] == False]
        gp = wins['pnl'].sum()
        gl = abs(losses['pnl'].sum())
        pf = (gp / gl) if gl > 0 else float('inf')
        net = df_t['pnl'].sum()
        wr = (len(wins) / len(df_t)) * 100.0

        cum = df_t.groupby('date')['pnl'].sum().cumsum()
        dd = (cum.cummax() - cum).max() if not cum.empty else 0.0
        avg_ret = df_t['pnl'].mean()
        pnl_str = f"+₹{net:,.0f}" if net >= 0 else f"-₹{abs(net):,.0f}"
        print(f"{struct_name:<30} | {len(df_t):<6d} | {wr:<5.1f}% | {pf:<5.2f} | {pnl_str:<14} | ₹{dd:<11,.0f} | ₹{avg_ret:<9,.0f}")
    print("="*115)

if __name__ == '__main__':
    run_identical_spot_trades_comparison()
