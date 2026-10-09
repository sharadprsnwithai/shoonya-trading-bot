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

def estimate_atm_entry_premium(spot_price, iv=0.13, dte_days=3.5):
    # Standard Black-Scholes ATM approximation: Premium ≈ Spot * IV * sqrt(DTE/365) * 0.40
    return max(40.0, spot_price * iv * math.sqrt(max(0.5, dte_days) / 365.0) * 0.3989)

def run_option_selling_backtest():
    print("================================================================================")
    print(" 🚀 DRIFT VWAP PULLBACK: NIFTY 50 ATM OPTION SELLING BACKTEST")
    print("================================================================================")
    print(" • LONG Signal (Bullish Drift)  ──► SELL ATM PUT (PE)  [Benefits: Delta + Theta]")
    print(" • SHORT Signal (Bearish Drift) ──► SELL ATM CALL (CE) [Benefits: Delta + Theta]")
    print(" • Timings: Settlement 09:15-10:15 | Trade 10:15-14:55 | Cutoff 14:55 | Exit 15:10 IST")
    print(" • Exits: Target +50% Premium Decay | Stop Loss -60% Premium Expansion | 15:10 EOD")
    print(" • Guardrails: Max 4 Trades/Day | Halt on 2 Losses/Day | Lot Size: 75 Qty (1 Lot)")
    print("================================================================================\n")

    print(" Fetching historical 5-minute NIFTY 50 Index & Volume proxy data for 60 days...")
    
    # 1. Fetch Nifty 50 Index price data
    df_nifty = yf.download('^NSEI', interval='5m', period='60d', progress=False)
    if isinstance(df_nifty.columns, pd.MultiIndex):
        df_nifty.columns = df_nifty.columns.get_level_values(0)
    df_nifty.index = pd.to_datetime(df_nifty.index).tz_convert('Asia/Kolkata')

    # 2. Fetch NIFTYBEES for traded volume proxy
    df_bees = yf.download('NIFTYBEES.NS', interval='5m', period='60d', progress=False)
    if isinstance(df_bees.columns, pd.MultiIndex):
        df_bees.columns = df_bees.columns.get_level_values(0)
    df_bees.index = pd.to_datetime(df_bees.index).tz_convert('Asia/Kolkata')

    df_5m = df_nifty.copy()
    if not df_bees.empty and 'Volume' in df_bees.columns:
        df_5m['Volume'] = df_bees['Volume'].reindex(df_5m.index).fillna(10000)
    else:
        df_5m['Volume'] = 10000

    all_dates = sorted(list(set(df_5m.index.date)))
    print(f" Loaded {len(df_5m)} 5-min candles across {len(all_dates)} trading sessions ({all_dates[0]} to {all_dates[-1]})\n")

    # Run parameter grid comparison for Option Selling
    print("================================================================================")
    print(" 🔬 OPTION SELLING SL & TARGET CALIBRATION GRID (NIFTY 50 ATM OPTIONS)")
    print("================================================================================")
    print(f"{'Target (% Decay)':<18} | {'SL (% Expansion)':<18} | {'Cutoff':<8} | {'Trades':<6} | {'Win Rate':<8} | {'Profit Factor':<13} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12}")
    print("-" * 110)

    grid_results = []
    for target_pct in [0.40, 0.50, 0.60, 0.70]: # Target profit: 40% to 70% of premium decayed
        for sl_pct in [0.40, 0.50, 0.60, 0.75]:     # Stop Loss: 40% to 75% premium expansion
            for cutoff in [datetime.time(14, 15), datetime.time(14, 55)]:
                res_trades, res_daily = simulate_option_selling_run(df_5m, all_dates, target_pct, sl_pct, cutoff)
                if not res_trades: continue
                df_res = pd.DataFrame(res_trades)
                wins = df_res[df_res['is_win'] == True]
                losses = df_res[df_res['is_win'] == False]
                gp = wins['realized_pnl'].sum()
                gl = abs(losses['realized_pnl'].sum())
                pf = (gp / gl) if gl > 0 else float('inf')
                net = df_res['realized_pnl'].sum()
                wr = (len(wins) / len(df_res)) * 100.0

                pnl_series = pd.Series(list(res_daily.values()))
                cum = pnl_series.cumsum()
                dd = (cum.cummax() - cum).max() if not cum.empty else 0.0

                grid_results.append({
                    'target_pct': target_pct,
                    'sl_pct': sl_pct,
                    'cutoff': cutoff.strftime('%H:%M'),
                    'trades': len(df_res),
                    'win_rate': wr,
                    'pf': pf,
                    'net_pnl': net,
                    'max_dd': dd,
                    'trade_list': res_trades,
                    'daily_pnls': res_daily
                })
                pnl_str = f"+₹{net:,.0f}" if net >= 0 else f"-₹{abs(net):,.0f}"
                print(f"Target {int(target_pct*100)}% Decay      | SL {int(sl_pct*100):<2}% Expansion    | {cutoff.strftime('%H:%M'):<8} | {len(df_res):<6d} | {wr:<7.1f}% | {pf:<13.2f} | {pnl_str:<14} | ₹{dd:<11,.0f}")

    print("=" * 110)

    best_config = sorted(grid_results, key=lambda x: x['net_pnl'], reverse=True)[0]
    print(f"\n🏆 OPTIMAL OPTION SELLING CONFIGURATION: Target={int(best_config['target_pct']*100)}% Decay, SL={int(best_config['sl_pct']*100)}% Expansion, Cutoff={best_config['cutoff']}")
    print_option_selling_summary(best_config['trade_list'], best_config['daily_pnls'], len(all_dates))

def simulate_option_selling_run(df_5m, all_dates, target_profit_pct, stop_loss_pct, entry_cutoff):
    trades = []
    daily_pnls = defaultdict(float)
    lot_size = 75 # Nifty Lot Size

    for trade_date in all_dates:
        day_df_5m = df_5m[df_5m.index.date == trade_date].copy()
        if len(day_df_5m) < 15:
            continue

        day_df_5m['VWAP_5m'] = calculate_vwap_from_df(day_df_5m)

        day_df_15m = day_df_5m.resample('15min', closed='left', label='left').agg({
            'Open': 'first',
            'High': 'max',
            'Low': 'min',
            'Close': 'last',
            'Volume': 'sum'
        }).dropna()

        if len(day_df_15m) < 5:
            continue

        day_df_15m['VWAP_15m'] = calculate_vwap_from_df(day_df_15m)
        day_df_15m['VWAP_15m_prev'] = day_df_15m['VWAP_15m'].shift(1)
        day_df_15m['1Hr_Mom_Pct'] = (day_df_15m['Close'] - day_df_15m['Close'].shift(4)) / day_df_15m['Close'].shift(4) * 100.0

        daily_trades_count = 0
        daily_losses_count = 0
        in_position = False
        active_pos = None

        # Determine Days to Expiry (Weekly expiry every Thursday = Day 3)
        day_of_week = trade_date.weekday() # 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri
        dte = (3 - day_of_week) if day_of_week <= 3 else (10 - day_of_week)

        for i in range(12, len(day_df_5m)):
            candle_5m = day_df_5m.iloc[i]
            c_time = candle_5m.name.time()

            # 1. Manage Active Option Selling Position
            if in_position and active_pos is not None:
                option_type = active_pos['option_type']
                entry_spot = active_pos['entry_spot']
                entry_prem = active_pos['entry_premium']
                entry_time = active_pos['entry_time']

                curr_spot = float(candle_5m['Close'])
                b_high = float(candle_5m['High'])
                b_low = float(candle_5m['Low'])

                # Delta & Theta pricing simulation
                # Intraday Theta decay: ~12% per 6 hours (0.10% per 5-min bar held)
                bars_held = (candle_5m.name - entry_time).total_seconds() / 300.0
                theta_decay_pts = min(entry_prem * 0.40, entry_prem * 0.0035 * bars_held)

                # Spot move effect on Sold Option Premium
                if option_type == 'PE':
                    # Sold PE: Spot UP is favorable (PE falls), Spot DOWN is adverse (PE rises)
                    spot_diff_favorable = b_high - entry_spot
                    spot_diff_adverse = b_low - entry_spot
                    
                    min_prem_reached = max(0.50, entry_prem - (0.50 * spot_diff_favorable) - theta_decay_pts)
                    max_prem_reached = max(0.50, entry_prem - (0.50 * spot_diff_adverse) - (theta_decay_pts * 0.5))
                    curr_prem = max(0.50, entry_prem - (0.50 * (curr_spot - entry_spot)) - theta_decay_pts)
                else: # 'CE'
                    # Sold CE: Spot DOWN is favorable (CE falls), Spot UP is adverse (CE rises)
                    spot_diff_favorable = entry_spot - b_low
                    spot_diff_adverse = entry_spot - b_high
                    
                    min_prem_reached = max(0.50, entry_prem - (0.50 * spot_diff_favorable) - theta_decay_pts)
                    max_prem_reached = max(0.50, entry_prem - (0.50 * spot_diff_adverse) - (theta_decay_pts * 0.5))
                    curr_prem = max(0.50, entry_prem - (0.50 * (entry_spot - curr_spot)) - theta_decay_pts)

                # Target & Stop Loss Levels on Premium:
                target_prem = entry_prem * (1.0 - target_profit_pct) # Buy back cheaper
                sl_prem = entry_prem * (1.0 + stop_loss_pct)         # Stop if premium expands

                # A. Hard EOD Exit at 15:10 IST
                if c_time >= datetime.time(15, 10):
                    pts = entry_prem - curr_prem # Profit on sold option = Entry - Exit
                    realized_pnl = pts * lot_size
                    trades.append({
                        'date': trade_date,
                        'time': candle_5m.name,
                        'option_type': option_type,
                        'strike': active_pos['strike'],
                        'entry_premium': entry_prem,
                        'exit_premium': curr_prem,
                        'points': pts,
                        'realized_pnl': realized_pnl,
                        'exit_reason': '15:10_THETA_EOD_EXIT',
                        'is_win': realized_pnl > 0
                    })
                    daily_pnls[trade_date] += realized_pnl
                    if realized_pnl < 0:
                        daily_losses_count += 1
                    in_position = False
                    active_pos = None
                    break

                # B. Check Stop Loss on Option Expansion
                if max_prem_reached >= sl_prem:
                    pts = entry_prem - sl_prem # Negative P&L
                    realized_pnl = pts * lot_size
                    trades.append({
                        'date': trade_date,
                        'time': candle_5m.name,
                        'option_type': option_type,
                        'strike': active_pos['strike'],
                        'entry_premium': entry_prem,
                        'exit_premium': sl_prem,
                        'points': pts,
                        'realized_pnl': realized_pnl,
                        'exit_reason': 'OPTION_SL_EXPANSION',
                        'is_win': False
                    })
                    daily_pnls[trade_date] += realized_pnl
                    daily_losses_count += 1
                    in_position = False
                    active_pos = None
                # C. Check Target on Option Decay
                elif min_prem_reached <= target_prem:
                    pts = entry_prem - target_prem # Positive P&L
                    realized_pnl = pts * lot_size
                    trades.append({
                        'date': trade_date,
                        'time': candle_5m.name,
                        'option_type': option_type,
                        'strike': active_pos['strike'],
                        'entry_premium': entry_prem,
                        'exit_premium': target_prem,
                        'points': pts,
                        'realized_pnl': realized_pnl,
                        'exit_reason': 'OPTION_TARGET_DECAY',
                        'is_win': True
                    })
                    daily_pnls[trade_date] += realized_pnl
                    in_position = False
                    active_pos = None

            # 2. Check Entry Conditions
            if not in_position:
                if daily_trades_count >= 4 or daily_losses_count >= 2:
                    continue
                if c_time >= entry_cutoff:
                    continue

                curr_5m_timestamp = candle_5m.name
                past_15m_bars = day_df_15m[day_df_15m.index <= (curr_5m_timestamp - pd.Timedelta(minutes=5))]
                if len(past_15m_bars) < 4:
                    continue
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
                        # Bullish Drift -> SELL ATM PUT (PE)
                        strike = round_strike(next_open, step=50)
                        entry_prem = round(estimate_atm_entry_premium(next_open, iv=0.13, dte_days=dte), 2)
                        in_position = True
                        active_pos = {
                            'option_type': 'PE',
                            'strike': strike,
                            'entry_spot': next_open,
                            'entry_premium': entry_prem,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1
                    elif short_drift and is_green_pullback:
                        # Bearish Drift -> SELL ATM CALL (CE)
                        strike = round_strike(next_open, step=50)
                        entry_prem = round(estimate_atm_entry_premium(next_open, iv=0.13, dte_days=dte), 2)
                        in_position = True
                        active_pos = {
                            'option_type': 'CE',
                            'strike': strike,
                            'entry_spot': next_open,
                            'entry_premium': entry_prem,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1

    return trades, daily_pnls

def print_option_selling_summary(trades, daily_pnls, total_days):
    if not trades:
        print(" No trades executed.")
        return

    df_t = pd.DataFrame(trades)
    total_trades = len(df_t)
    wins = df_t[df_t['is_win'] == True]
    losses = df_t[df_t['is_win'] == False]
    win_rate = (len(wins) / total_trades) * 100.0

    gross_profit = wins['realized_pnl'].sum()
    gross_loss = abs(losses['realized_pnl'].sum())
    net_pnl = df_t['realized_pnl'].sum()
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')

    avg_win = wins['realized_pnl'].mean() if len(wins) > 0 else 0.0
    avg_loss = abs(losses['realized_pnl'].mean()) if len(losses) > 0 else 0.0
    rr_actual = (avg_win / avg_loss) if avg_loss > 0 else float('inf')

    pnl_series = pd.Series(list(daily_pnls.values()))
    cum_pnl = pnl_series.cumsum()
    max_dd = (cum_pnl.cummax() - cum_pnl).max() if not cum_pnl.empty else 0.0

    print("================================================================================")
    print(" 📊 OPTIMAL ATM OPTION SELLING PERFORMANCE SUMMARY")
    print("================================================================================")
    print(f" Total Trading Days:         {total_days}")
    print(f" Total Trades Executed:      {total_trades} (Avg {total_trades/total_days:.1f} trades/day)")
    print(f" Winning Trades:             {len(wins)} ({win_rate:.1f}%) ⭐")
    print(f" Losing Trades:              {len(losses)} ({len(losses)/total_trades*100.0:.1f}%)")
    print(f"--------------------------------------------------------------------------------")
    print(f" Gross Profit:               +₹{gross_profit:,.2f}")
    print(f" Gross Loss:                 -₹{gross_loss:,.2f}")
    print(f" Net Realized P&L:           +₹{net_pnl:,.2f}" if net_pnl >= 0 else f" Net Realized P&L:           -₹{abs(net_pnl):,.2f}")
    print(f" Profit Factor:              {profit_factor:.2f}")
    print(f" Realized RR (Avg Win/Loss): {rr_actual:.2f} : 1")
    print(f" Average Trade Return:       ₹{df_t['realized_pnl'].mean():,.2f}")
    print(f" Max Strategy Drawdown:      ₹{max_dd:,.2f}")
    print(f"--------------------------------------------------------------------------------")
    print(" Option Type Breakdown:")
    for opt, grp in df_t.groupby('option_type'):
        opt_wins = len(grp[grp['is_win'] == True])
        opt_wr = (opt_wins / len(grp)) * 100.0
        opt_pnl = grp['realized_pnl'].sum()
        desc = "SELL ATM PUT (PE)  [Bullish]" if opt == "PE" else "SELL ATM CALL (CE) [Bearish]"
        print(f"  • {desc:<30}: {len(grp):2d} trades | Win Rate: {opt_wr:5.1f}% | Net P&L: ₹{opt_pnl:+10,.2f}")

    print(f"--------------------------------------------------------------------------------")
    print(" Exit Reasons:")
    for reason, grp in df_t.groupby('exit_reason'):
        print(f"  • {reason:<22}: {len(grp):2d} trades | Net P&L: ₹{grp['realized_pnl'].sum():+10,.2f}")
    print("================================================================================\n")

    print(" Sample Executed Option Selling Trades (Last 12):")
    cols = ['date', 'time', 'option_type', 'strike', 'entry_premium', 'exit_premium', 'points', 'realized_pnl', 'exit_reason']
    print(df_t[cols].tail(12).to_string(index=False))
    print("================================================================================\n")

if __name__ == '__main__':
    run_option_selling_backtest()
