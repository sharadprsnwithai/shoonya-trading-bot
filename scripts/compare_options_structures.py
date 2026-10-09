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
    # Black-Scholes analytical approximation for index options
    dte_years = max(0.5, dte_days) / 365.0
    sigma_sqrt_t = iv * math.sqrt(dte_years)
    d1 = (math.log(spot_price / strike_price) + (0.5 * iv * iv) * dte_years) / sigma_sqrt_t
    d2 = d1 - sigma_sqrt_t

    # Normal CDF approximation
    def norm_cdf(x):
        return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))

    if opt_type == 'CE':
        prem = (spot_price * norm_cdf(d1)) - (strike_price * norm_cdf(d2))
    else: # 'PE'
        prem = (strike_price * norm_cdf(-d2)) - (spot_price * norm_cdf(-d1))

    return max(1.50, prem)

def run_comparison():
    print("=========================================================================================================")
    print(" 🚀 DRIFT VWAP STRATEGY: OPTIONS STRUCTURE COMPARISON (NIFTY 50)")
    print(" 1. ATM Option Selling (Sell ATM PE on Bullish, Sell ATM CE on Bearish)")
    print(" 2. 200-Points OTM Option Buying (Buy 200 OTM CE on Bullish, Buy 200 OTM PE on Bearish)")
    print(" 3. 400-Points OTM Option Selling (Sell 400 OTM PE on Bullish, Sell 400 OTM CE on Bearish)")
    print("=========================================================================================================\n")

    print(" Fetching historical 5-minute NIFTY 50 Index & Volume proxy data for 60 days...")
    
    df_nifty = yf.download('^NSEI', interval='5m', period='60d', progress=False)
    if isinstance(df_nifty.columns, pd.MultiIndex):
        df_nifty.columns = df_nifty.columns.get_level_values(0)
    df_nifty.index = pd.to_datetime(df_nifty.index).tz_convert('Asia/Kolkata')

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

    modes = [
        ("1. ATM Option Selling (Baseline)", "ATM_SELLING", 0, "SELL", 0.70, 0.60),
        ("2. 200-Pts OTM Option Buying", "200_OTM_BUYING", 200, "BUY", 0.60, 0.40), # Target +60% profit, SL -40% loss
        ("3. 400-Pts OTM Option Selling", "400_OTM_SELLING", 400, "SELL", 0.80, 1.00) # Target 80% decay, SL 100% expansion
    ]

    results = []
    for label, mode_key, offset_pts, action, target_pct, sl_pct in modes:
        trades, daily_pnls = simulate_mode(df_5m, all_dates, mode_key, offset_pts, action, target_pct, sl_pct)
        if not trades: continue
        df_t = pd.DataFrame(trades)
        wins = df_t[df_t['is_win'] == True]
        losses = df_t[df_t['is_win'] == False]
        gp = wins['realized_pnl'].sum()
        gl = abs(losses['realized_pnl'].sum())
        pf = (gp / gl) if gl > 0 else float('inf')
        net = df_t['realized_pnl'].sum()
        wr = (len(wins) / len(df_t)) * 100.0

        pnl_series = pd.Series(list(daily_pnls.values()))
        cum = pnl_series.cumsum()
        dd = (cum.cummax() - cum).max() if not cum.empty else 0.0
        avg_ret = df_t['realized_pnl'].mean()

        results.append({
            'label': label,
            'trades': len(df_t),
            'wins': len(wins),
            'losses': len(losses),
            'win_rate': wr,
            'gp': gp,
            'gl': gl,
            'net_pnl': net,
            'pf': pf,
            'avg_ret': avg_ret,
            'max_dd': dd,
            'df_trades': df_t
        })

    print("="*115)
    print(f"{'Option Execution Structure':<36} | {'Trades':<6} | {'Win %':<6} | {'PF':<5} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12} | {'Avg Trade':<10}")
    print("-" * 115)
    for r in results:
        pnl_str = f"+₹{r['net_pnl']:,.0f}" if r['net_pnl'] >= 0 else f"-₹{abs(r['net_pnl']):,.0f}"
        print(f"{r['label']:<36} | {r['trades']:<6d} | {r['win_rate']:<5.1f}% | {r['pf']:<5.2f} | {pnl_str:<14} | ₹{r['max_dd']:<11,.0f} | ₹{r['avg_ret']:<9,.0f}")
    print("="*115)

    for r in results:
        print(f"\n--- Detailed Breakdown: {r['label']} ---")
        df_sub = r['df_trades']
        for opt, grp in df_sub.groupby('option_type'):
            owins = len(grp[grp['is_win'] == True])
            owr = (owins / len(grp)) * 100.0
            print(f"  • {opt} Contracts: {len(grp):2d} trades | Win Rate: {owr:5.1f}% | Realized P&L: ₹{grp['realized_pnl'].sum():+10,.0f}")
        for reason, grp in df_sub.groupby('exit_reason'):
            print(f"  • Exit [{reason}]: {len(grp):2d} trades | Realized P&L: ₹{grp['realized_pnl'].sum():+10,.0f}")

def simulate_mode(df_5m, all_dates, mode_key, offset_pts, action, target_pct, sl_pct):
    trades = []
    daily_pnls = defaultdict(float)
    lot_size = 75 # Nifty Lot Size
    entry_cutoff = datetime.time(14, 55)

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

            if in_position and active_pos is not None:
                opt_type = active_pos['option_type']
                strike = active_pos['strike']
                entry_prem = active_pos['entry_premium']
                entry_time = active_pos['entry_time']
                entry_spot = active_pos['entry_spot']

                curr_spot = float(candle_5m['Close'])
                b_high = float(candle_5m['High'])
                b_low = float(candle_5m['Low'])

                bars_held = (candle_5m.name - entry_time).total_seconds() / 300.0
                curr_dte_days = max(0.2, dte - (bars_held / 75.0))

                # Compute current option price via Black-Scholes with decaying DTE
                prem_at_high = estimate_option_premium(b_high, strike, opt_type, iv=0.13, dte_days=curr_dte_days)
                prem_at_low = estimate_option_premium(b_low, strike, opt_type, iv=0.13, dte_days=curr_dte_days)
                curr_prem = estimate_option_premium(curr_spot, strike, opt_type, iv=0.13, dte_days=curr_dte_days)

                if action == "SELL":
                    # Option Selling (Capture Decay)
                    min_prem = min(prem_at_high, prem_at_low, curr_prem)
                    max_prem = max(prem_at_high, prem_at_low, curr_prem)
                    target_prem = entry_prem * (1.0 - target_pct)
                    sl_prem = entry_prem * (1.0 + sl_pct)

                    if c_time >= datetime.time(15, 10):
                        pts = entry_prem - curr_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': curr_prem, 'realized_pnl': pnl, 'exit_reason': '15:10_EOD_EXIT', 'is_win': pnl > 0})
                        daily_pnls[trade_date] += pnl
                        if pnl < 0: daily_losses_count += 1
                        in_position = False
                        active_pos = None
                        break
                    elif max_prem >= sl_prem:
                        pts = entry_prem - sl_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': sl_prem, 'realized_pnl': pnl, 'exit_reason': 'SL_EXPANSION_HIT', 'is_win': False})
                        daily_pnls[trade_date] += pnl
                        daily_losses_count += 1
                        in_position = False
                        active_pos = None
                    elif min_prem <= target_prem:
                        pts = entry_prem - target_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': target_prem, 'realized_pnl': pnl, 'exit_reason': 'TARGET_DECAY_HIT', 'is_win': True})
                        daily_pnls[trade_date] += pnl
                        in_position = False
                        active_pos = None
                else: # "BUY" (200 OTM Buying)
                    max_prem = max(prem_at_high, prem_at_low, curr_prem)
                    min_prem = min(prem_at_high, prem_at_low, curr_prem)
                    target_prem = entry_prem * (1.0 + target_pct)
                    sl_prem = entry_prem * (1.0 - sl_pct)

                    if c_time >= datetime.time(15, 10):
                        pts = curr_prem - entry_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': curr_prem, 'realized_pnl': pnl, 'exit_reason': '15:10_EOD_EXIT', 'is_win': pnl > 0})
                        daily_pnls[trade_date] += pnl
                        if pnl < 0: daily_losses_count += 1
                        in_position = False
                        active_pos = None
                        break
                    elif min_prem <= sl_prem:
                        pts = sl_prem - entry_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': sl_prem, 'realized_pnl': pnl, 'exit_reason': 'BUY_SL_HIT', 'is_win': False})
                        daily_pnls[trade_date] += pnl
                        daily_losses_count += 1
                        in_position = False
                        active_pos = None
                    elif max_prem >= target_prem:
                        pts = target_prem - entry_prem
                        pnl = pts * lot_size
                        trades.append({'date': trade_date, 'time': candle_5m.name, 'option_type': opt_type, 'strike': strike, 'action': action, 'entry_prem': entry_prem, 'exit_prem': target_prem, 'realized_pnl': pnl, 'exit_reason': 'BUY_TARGET_HIT', 'is_win': True})
                        daily_pnls[trade_date] += pnl
                        in_position = False
                        active_pos = None

            if not in_position:
                if daily_trades_count >= 4 or daily_losses_count >= 2: continue
                if c_time >= entry_cutoff: continue

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
                        if action == "SELL":
                            # Bullish Drift -> SELL PUT (ATM or 400 OTM)
                            strike = round_strike(next_open - offset_pts, step=50)
                            opt_type = 'PE'
                        else:
                            # Bullish Drift -> BUY 200 OTM CALL
                            strike = round_strike(next_open + offset_pts, step=50)
                            opt_type = 'CE'

                        prem = estimate_option_premium(next_open, strike, opt_type, iv=0.13, dte_days=dte)
                        in_position = True
                        active_pos = {
                            'option_type': opt_type,
                            'strike': strike,
                            'entry_spot': next_open,
                            'entry_premium': prem,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1
                    elif short_drift and is_green_pullback:
                        if action == "SELL":
                            # Bearish Drift -> SELL CALL (ATM or 400 OTM)
                            strike = round_strike(next_open + offset_pts, step=50)
                            opt_type = 'CE'
                        else:
                            # Bearish Drift -> BUY 200 OTM PUT
                            strike = round_strike(next_open - offset_pts, step=50)
                            opt_type = 'PE'

                        prem = estimate_option_premium(next_open, strike, opt_type, iv=0.13, dte_days=dte)
                        in_position = True
                        active_pos = {
                            'option_type': opt_type,
                            'strike': strike,
                            'entry_spot': next_open,
                            'entry_premium': prem,
                            'entry_time': next_candle.name
                        }
                        daily_trades_count += 1

    return trades, daily_pnls

if __name__ == '__main__':
    run_comparison()
