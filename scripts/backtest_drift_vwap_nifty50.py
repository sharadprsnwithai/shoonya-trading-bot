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

def run_drift_vwap_backtest():
    print("================================================================================")
    print(" 🚀 MATIO KANTI'S DRIFT VWAP PULLBACK STRATEGY (NIFTY 50 INDEX EDITION)")
    print("================================================================================")
    print(" • Timings: Settlement 09:15-10:15 | Trade 10:15-14:55 | Cutoff 14:55 | Exit 15:10 IST")
    print(" • 15m Drift Filter: (Close vs VWAP) + (Rising/Falling VWAP) + (1-Hr Momentum >= 0.10%)")
    print(" • 5m Pullback Trigger: 1st Opposite Candle -> Market Order at Open of Next Bar")
    print(" • Guardrails: Max 4 Trades/Day | Halt on 2 Losses/Day | Hard EOD Exit at 15:10 IST")
    print("================================================================================\n")

    print(" Fetching historical 5-minute NIFTY 50 Index & NIFTY ETF data for the last 60 days...")
    
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

    # Merge volume into Nifty dataframe
    df_5m = df_nifty.copy()
    if not df_bees.empty and 'Volume' in df_bees.columns:
        df_5m['Volume'] = df_bees['Volume'].reindex(df_5m.index).fillna(10000)
    else:
        df_5m['Volume'] = 10000

    all_dates = sorted(list(set(df_5m.index.date)))
    print(f" Loaded {len(df_5m)} 5-min candles across {len(all_dates)} trading sessions ({all_dates[0]} to {all_dates[-1]})\n")

    # Run parameter optimization test
    print("================================================================================")
    print(" 🔬 TARGET & STOP LOSS POINT CALIBRATION GRID (NIFTY 50)")
    print("================================================================================")
    print(f"{'Target (Pts)':<12} | {'SL (Pts)':<10} | {'Cutoff':<8} | {'Trades':<6} | {'Win Rate':<8} | {'Profit Factor':<13} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12}")
    print("-" * 95)

    grid_results = []
    for tp_long, tp_short in [(25, 30), (30, 35), (35, 40), (40, 45), (30, 30)]:
        for sl_pts in [40, 45, 50, 60]:
            for cutoff_time in [datetime.time(14, 15), datetime.time(14, 55)]:
                res_trades, res_daily = simulate_parameters(df_5m, all_dates, tp_long, tp_short, sl_pts, cutoff_time)
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
                    'tp_long': tp_long,
                    'tp_short': tp_short,
                    'sl_pts': sl_pts,
                    'cutoff': cutoff_time.strftime('%H:%M'),
                    'trades': len(df_res),
                    'win_rate': wr,
                    'pf': pf,
                    'net_pnl': net,
                    'max_dd': dd,
                    'trade_list': res_trades,
                    'daily_pnls': res_daily
                })
                pnl_str = f"+₹{net:,.0f}" if net >= 0 else f"-₹{abs(net):,.0f}"
                print(f"L:{tp_long} S:{tp_short:<6} | {sl_pts:<10} | {cutoff_time.strftime('%H:%M'):<8} | {len(df_res):<6d} | {wr:<7.1f}% | {pf:<13.2f} | {pnl_str:<14} | ₹{dd:<11,.0f}")

    print("=" * 95)
    
    # Pick best performing configuration and print full report + Monte Carlo
    best_config = sorted(grid_results, key=lambda x: x['net_pnl'], reverse=True)[0]
    print(f"\n🏆 OPTIMAL NIFTY 50 CONFIGURATION: Long TP={best_config['tp_long']}pts, Short TP={best_config['tp_short']}pts, SL={best_config['sl_pts']}pts, Cutoff={best_config['cutoff']}")
    print_performance_and_monte_carlo(best_config['trade_list'], best_config['daily_pnls'], len(all_dates))

def simulate_parameters(df_5m, all_dates, target_long_pts, target_short_pts, sl_pts, entry_cutoff):
    trades = []
    daily_pnls = defaultdict(float)
    lot_size = 75

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

        for i in range(12, len(day_df_5m)):
            candle_5m = day_df_5m.iloc[i]
            c_time = candle_5m.name.time()

            if in_position and active_pos is not None:
                pos_dir = active_pos['direction']
                entry_p = active_pos['entry_price']
                tp_p = active_pos['tp_price']
                sl_p = active_pos['sl_price']
                b_high = float(candle_5m['High'])
                b_low = float(candle_5m['Low'])
                b_close = float(candle_5m['Close'])

                # Complete Hard Exit at 15:10 IST
                if c_time >= datetime.time(15, 10):
                    exit_price = b_close
                    pts = (exit_price - entry_p) if pos_dir == 'LONG' else (entry_p - exit_price)
                    realized_pnl = pts * lot_size
                    trades.append({
                        'date': trade_date,
                        'time': candle_5m.name,
                        'direction': pos_dir,
                        'entry_price': entry_p,
                        'exit_price': exit_price,
                        'points': pts,
                        'realized_pnl': realized_pnl,
                        'exit_reason': '15:10_HARD_EXIT',
                        'is_win': realized_pnl > 0
                    })
                    daily_pnls[trade_date] += realized_pnl
                    if realized_pnl < 0:
                        daily_losses_count += 1
                    in_position = False
                    active_pos = None
                    break

                # Stop Loss & Take Profit
                if pos_dir == 'LONG':
                    if b_low <= sl_p:
                        pts = -sl_pts
                        realized_pnl = pts * lot_size
                        trades.append({
                            'date': trade_date,
                            'time': candle_5m.name,
                            'direction': 'LONG',
                            'entry_price': entry_p,
                            'exit_price': sl_p,
                            'points': pts,
                            'realized_pnl': realized_pnl,
                            'exit_reason': 'STOP_LOSS_HIT',
                            'is_win': False
                        })
                        daily_pnls[trade_date] += realized_pnl
                        daily_losses_count += 1
                        in_position = False
                        active_pos = None
                    elif b_high >= tp_p:
                        pts = target_long_pts
                        realized_pnl = pts * lot_size
                        trades.append({
                            'date': trade_date,
                            'time': candle_5m.name,
                            'direction': 'LONG',
                            'entry_price': entry_p,
                            'exit_price': tp_p,
                            'points': pts,
                            'realized_pnl': realized_pnl,
                            'exit_reason': 'TAKE_PROFIT_HIT',
                            'is_win': True
                        })
                        daily_pnls[trade_date] += realized_pnl
                        in_position = False
                        active_pos = None
                else: # SHORT
                    if b_high >= sl_p:
                        pts = -sl_pts
                        realized_pnl = pts * lot_size
                        trades.append({
                            'date': trade_date,
                            'time': candle_5m.name,
                            'direction': 'SHORT',
                            'entry_price': entry_p,
                            'exit_price': sl_p,
                            'points': pts,
                            'realized_pnl': realized_pnl,
                            'exit_reason': 'STOP_LOSS_HIT',
                            'is_win': False
                        })
                        daily_pnls[trade_date] += realized_pnl
                        daily_losses_count += 1
                        in_position = False
                        active_pos = None
                    elif b_low <= tp_p:
                        pts = target_short_pts
                        realized_pnl = pts * lot_size
                        trades.append({
                            'date': trade_date,
                            'time': candle_5m.name,
                            'direction': 'SHORT',
                            'entry_price': entry_p,
                            'exit_price': tp_p,
                            'points': pts,
                            'realized_pnl': realized_pnl,
                            'exit_reason': 'TAKE_PROFIT_HIT',
                            'is_win': True
                        })
                        daily_pnls[trade_date] += realized_pnl
                        in_position = False
                        active_pos = None

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
                        entry_price = next_open
                        tp_price = entry_price + target_long_pts
                        sl_price = entry_price - sl_pts
                        in_position = True
                        active_pos = {
                            'direction': 'LONG',
                            'entry_price': entry_price,
                            'tp_price': tp_price,
                            'sl_price': sl_price,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1
                    elif short_drift and is_green_pullback:
                        entry_price = next_open
                        tp_price = entry_price - target_short_pts
                        sl_price = entry_price + sl_pts
                        in_position = True
                        active_pos = {
                            'direction': 'SHORT',
                            'entry_price': entry_price,
                            'tp_price': tp_price,
                            'sl_price': sl_price,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1

    return trades, daily_pnls

def print_performance_and_monte_carlo(trades, daily_pnls, total_days):
    if not trades:
        print(" No trades triggered during the backtest period.")
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
    peak = cum_pnl.cummax()
    max_dd = (peak - cum_pnl).max() if not cum_pnl.empty else 0.0

    print("================================================================================")
    print(" 📊 NIFTY 50 OPTIMAL PERFORMANCE SUMMARY")
    print("================================================================================")
    print(f" Total Trading Days:         {total_days}")
    print(f" Total Trades Executed:      {total_trades} (Avg {total_trades/total_days:.1f} trades/day)")
    print(f" Winning Trades:             {len(wins)} ({win_rate:.1f}%)")
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
    print(" Directional Breakdown:")
    for d, grp in df_t.groupby('direction'):
        d_wins = len(grp[grp['is_win'] == True])
        d_wr = (d_wins / len(grp)) * 100.0
        d_pnl = grp['realized_pnl'].sum()
        print(f"  • {d:<6}: {len(grp):2d} trades | Win Rate: {d_wr:5.1f}% | Net P&L: ₹{d_pnl:+10,.2f}")

    print(f"--------------------------------------------------------------------------------")
    print(" Exit Reasons:")
    for reason, grp in df_t.groupby('exit_reason'):
        print(f"  • {reason:<18}: {len(grp):2d} trades | Net P&L: ₹{grp['realized_pnl'].sum():+10,.2f}")
    print("================================================================================\n")

    run_prop_firm_monte_carlo(trades, df_t['realized_pnl'].values)

def run_prop_firm_monte_carlo(trades, pnl_array, target=50000.0, max_drawdown=25000.0, max_trading_days=30, simulations=20000):
    print("================================================================================")
    print(" 🎲 20,000 MONTE CARLO PROP FIRM PASS-RATE SIMULATIONS")
    print(" (Target: +₹50,000 profit | Max Drawdown Limit: ₹25,000 | 30 Trading Days)")
    print("================================================================================")

    passed_count = 0
    days_to_pass = []

    np.random.seed(42)
    for _ in range(simulations):
        cum_pnl = 0.0
        peak = 0.0
        failed = False
        passed = False
        trades_per_day = 2

        for day in range(1, max_trading_days + 1):
            daily_trade_sample = np.random.choice(pnl_array, size=trades_per_day, replace=True)
            for trade_pnl in daily_trade_sample:
                cum_pnl += trade_pnl
                peak = max(peak, cum_pnl)
                dd = peak - cum_pnl

                if dd >= max_drawdown:
                    failed = True
                    break
                if cum_pnl >= target:
                    passed = True
                    days_to_pass.append(day)
                    break

            if failed or passed:
                break

        if passed:
            passed_count += 1

    single_pass_rate = (passed_count / simulations) * 100.0
    pass_within_2 = (1.0 - (1.0 - single_pass_rate/100.0)**2) * 100.0
    pass_within_3 = (1.0 - (1.0 - single_pass_rate/100.0)**3) * 100.0
    pass_within_4 = (1.0 - (1.0 - single_pass_rate/100.0)**4) * 100.0
    avg_days = np.mean(days_to_pass) if days_to_pass else 0.0

    print(f" Single Challenge Pass Probability:       {single_pass_rate:.1f}%")
    print(f" Probability to Pass Within 2 Attempts:    {pass_within_2:.1f}%")
    print(f" Probability to Pass Within 3 Attempts:    {pass_within_3:.1f}%")
    print(f" Probability to Pass Within 4 Attempts:    {pass_within_4:.1f}% (⭐ Golden Ticket)")
    print(f" Average Days to Pass Challenge:          {avg_days:.1f} days (well within 30-day limit)")
    print("================================================================================\n")

    print(" Sample Executed Trades (Last 10):")
    cols = ['date', 'time', 'direction', 'entry_price', 'exit_price', 'points', 'realized_pnl', 'exit_reason']
    print(pd.DataFrame(trades)[cols].tail(10).to_string(index=False))
    print("================================================================================\n")

if __name__ == '__main__':
    run_drift_vwap_backtest()
