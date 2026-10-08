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

UNIVERSE = [
    "RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "ICICIBANK.NS", 
    "SBIN.NS", "TATASTEEL.NS", "MARUTI.NS", "SUNPHARMA.NS", "BHARTIARTL.NS", 
    "AXISBANK.NS", "KOTAKBANK.NS", "HINDALCO.NS", "BHEL.NS", "ITC.NS", 
    "LT.NS", "BAJFINANCE.NS", "M&M.NS", "NTPC.NS", "COALINDIA.NS",
    "JINDALSTEL.NS", "VEDL.NS", "CIPLA.NS", "DRREDDY.NS",
    "WIPRO.NS", "TECHM.NS", "HEROMOTOCO.NS", "EICHERMOT.NS", "BAJAJ-AUTO.NS",
    "VOLTAS.NS", "RADICO.NS", "RVNL.NS", "PERSISTENT.NS", "COFORGE.NS"
]

LOT_SIZES = {
    "RELIANCE": 250, "TCS": 175, "INFY": 400, "HDFCBANK": 550, "ICICIBANK": 700,
    "SBIN": 750, "TATASTEEL": 5500, "MARUTI": 50, "SUNPHARMA": 350, "BHARTIARTL": 475,
    "AXISBANK": 625, "KOTAKBANK": 400, "HINDALCO": 1400, "BHEL": 2625, "ITC": 1600,
    "LT": 175, "BAJFINANCE": 125, "M&M": 350, "NTPC": 1500, "COALINDIA": 2100,
    "JINDALSTEL": 625, "VEDL": 1150, "CIPLA": 650, "DRREDDY": 125,
    "WIPRO": 1500, "TECHM": 600, "HEROMOTOCO": 150, "EICHERMOT": 175, "BAJAJ-AUTO": 75,
    "VOLTAS": 600, "RADICO": 300, "RVNL": 2500, "PERSISTENT": 100, "COFORGE": 150
}

def round_to_tick(val):
    return round(val * 20.0) / 20.0

def calculate_vwap(df):
    vol = df['Volume'].replace(0, 1)
    typical = (df['High'] + df['Low'] + df['Close']) / 3.0
    return (typical * vol).cumsum() / vol.cumsum()

def calculate_ema(series, span=10):
    return series.ewm(span=span, adjust=False).mean()

def run_experiment(data_5m, daily_data, trading_dates, 
                   use_vande_bharat=True, 
                   max_consecutive_losses=2, 
                   max_sl_pct=1.0, 
                   daily_ema_filter=False,
                   max_daily_trades=2):
    
    trades = []
    daily_pnls = defaultdict(float)

    for trade_date in trading_dates:
        candidates_pool = []
        for sym, df in data_5m.items():
            day_df = df[df.index.date == trade_date]
            if len(day_df) < 5: continue
            
            d_df = daily_data.get(sym)
            if d_df is None or d_df.empty: continue
            past_days = d_df[d_df.index.date < trade_date]
            if past_days.empty: continue
            prev_day = past_days.iloc[-1]
            pdh = float(prev_day['High'])
            pdl = float(prev_day['Low'])

            # Daily 20 EMA calculation
            daily_ema20 = None
            if len(past_days) >= 20:
                daily_ema20 = float(calculate_ema(past_days['Close'], span=20).iloc[-1])

            c1 = day_df.iloc[0]
            c2 = day_df.iloc[1]
            open_p = float(c1['Open'])
            ltp_0925 = float(c2['Close'])
            pct_chg = ((ltp_0925 - open_p) / open_p) * 100.0

            candidates_pool.append({
                'symbol': sym,
                'pct_chg': pct_chg,
                'day_df': day_df,
                'pdh': pdh,
                'pdl': pdl,
                'daily_ema20': daily_ema20,
                'lot_size': LOT_SIZES.get(sym, 250)
            })

        if not candidates_pool: continue

        gainers = sorted([c for c in candidates_pool if c['pct_chg'] > 0.0], key=lambda x: x['pct_chg'], reverse=True)[:5]
        losers = sorted([c for c in candidates_pool if c['pct_chg'] < 0.0], key=lambda x: x['pct_chg'])[:5]
        watchlist = [(c, 'LONG') for c in gainers] + [(c, 'SHORT') for c in losers]

        session_trades = 0
        consecutive_losses_today = 0

        for candidate, direction in watchlist:
            if session_trades >= max_daily_trades: break
            if consecutive_losses_today >= max_consecutive_losses: break

            sym = candidate['symbol']
            day_df = candidate['day_df']
            pdh = candidate['pdh']
            pdl = candidate['pdl']
            daily_ema20 = candidate['daily_ema20']
            lot_size = candidate['lot_size']
            lots = 2
            total_qty = lots * lot_size

            # Macro Trend Filter: Long only if above Daily 20 EMA, Short only if below Daily 20 EMA
            if daily_ema_filter and daily_ema20 is not None:
                c_open = float(day_df.iloc[0]['Open'])
                if direction == 'LONG' and c_open < daily_ema20:
                    continue
                elif direction == 'SHORT' and c_open > daily_ema20:
                    continue

            day_df = day_df.copy()
            day_df['VWAP'] = calculate_vwap(day_df)
            day_df['EMA10'] = calculate_ema(day_df['Close'], span=10)

            c1_3 = day_df.iloc[:3]
            rolling_lowest_vol = float(c1_3['Volume'].replace(0, np.nan).min())
            if np.isnan(rolling_lowest_vol):
                rolling_lowest_vol = float(c1_3['Volume'].iloc[-1])

            armed_setup = None

            for i in range(3, len(day_df)):
                candle = day_df.iloc[i]
                c_time = candle.name.time()
                if c_time >= datetime.time(13, 0): break

                c_open = float(candle['Open'])
                c_high = float(candle['High'])
                c_low = float(candle['Low'])
                c_close = float(candle['Close'])
                c_vol = float(candle['Volume'])
                c_vwap = float(candle['VWAP'])
                is_green = c_close > c_open
                is_red = c_close < c_open

                if armed_setup is not None:
                    trigger_p = armed_setup['trigger_price']
                    sl_p = armed_setup['sl_price']
                    t1_p = armed_setup['t1_price']
                    setup_dir = armed_setup['direction']

                    # Check max SL % filter
                    sl_dist_pct = (abs(trigger_p - sl_p) / trigger_p) * 100.0
                    if sl_dist_pct > max_sl_pct:
                        armed_setup = None
                        continue

                    if setup_dir == 'LONG' and c_low <= sl_p:
                        armed_setup = None
                    elif setup_dir == 'SHORT' and c_high >= sl_p:
                        armed_setup = None
                    elif setup_dir == 'LONG' and c_high >= trigger_p:
                        entry_price = trigger_p
                        if entry_price > c_vwap and entry_price > pdh:
                            trade_res = simulate_trade(day_df, i, 'LONG', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, armed_setup['pattern'])
                            trades.append(trade_res)
                            pnl = trade_res['realized_pnl']
                            daily_pnls[trade_date] += pnl
                            session_trades += 1
                            if pnl < 0:
                                consecutive_losses_today += 1
                            else:
                                consecutive_losses_today = 0
                            armed_setup = None
                            break
                        else:
                            armed_setup = None
                    elif setup_dir == 'SHORT' and c_low <= trigger_p:
                        entry_price = trigger_p
                        if entry_price < c_vwap and entry_price < pdl:
                            trade_res = simulate_trade(day_df, i, 'SHORT', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, armed_setup['pattern'])
                            trades.append(trade_res)
                            pnl = trade_res['realized_pnl']
                            daily_pnls[trade_date] += pnl
                            session_trades += 1
                            if pnl < 0:
                                consecutive_losses_today += 1
                            else:
                                consecutive_losses_today = 0
                            armed_setup = None
                            break
                        else:
                            armed_setup = None

                is_opposite = is_red if direction == 'LONG' else is_green

                lvr_matched = False
                if is_opposite and c_vol > 0 and c_vol <= rolling_lowest_vol:
                    if direction == 'LONG':
                        trg = round_to_tick(c_high + 0.05)
                        raw_sl = round_to_tick(c_low - 0.05)
                        risk = max(trg - raw_sl, trg * 0.0035)
                        sl = round_to_tick(trg - risk)
                        t1 = round_to_tick(trg + (2.0 * risk))
                    else:
                        trg = round_to_tick(c_low - 0.05)
                        raw_sl = round_to_tick(c_high + 0.05)
                        risk = max(raw_sl - trg, trg * 0.0035)
                        sl = round_to_tick(trg + risk)
                        t1 = round_to_tick(trg - (2.0 * risk))

                    armed_setup = {
                        'direction': direction,
                        'trigger_price': trg,
                        'sl_price': sl,
                        't1_price': t1,
                        'pattern': 'SETUP_2_LVR_PULLBACK',
                        'armed_index': i
                    }
                    rolling_lowest_vol = c_vol
                    lvr_matched = True
                elif c_vol > 0 and c_vol < rolling_lowest_vol:
                    rolling_lowest_vol = c_vol

                if use_vande_bharat and not lvr_matched and i >= 1:
                    prev = day_df.iloc[i - 1]
                    prev_open = float(prev['Open'])
                    prev_close = float(prev['Close'])
                    prev_high = float(prev['High'])
                    prev_low = float(prev['Low'])
                    prev_vol = float(prev['Volume'])

                    is_inside = (c_high <= prev_high and c_low >= prev_low)
                    vb_vol_floor = (c_vol <= prev_vol and (rolling_lowest_vol == 0 or c_vol <= rolling_lowest_vol * 2.0))

                    if direction == 'LONG' and (prev_close > prev_open) and is_red and is_inside and vb_vol_floor:
                        trg = round_to_tick(prev_high + 0.05)
                        raw_sl = round_to_tick(c_low - 0.05)
                        risk = max(trg - raw_sl, trg * 0.0035)
                        sl = round_to_tick(trg - risk)
                        t1 = round_to_tick(trg + (2.0 * risk))
                        armed_setup = {
                            'direction': direction,
                            'trigger_price': trg,
                            'sl_price': sl,
                            't1_price': t1,
                            'pattern': 'SETUP_3_VANDE_BHARAT',
                            'armed_index': i
                        }
                    elif direction == 'SHORT' and (prev_close < prev_open) and is_green and is_inside and vb_vol_floor:
                        trg = round_to_tick(prev_low - 0.05)
                        raw_sl = round_to_tick(c_high + 0.05)
                        risk = max(raw_sl - trg, trg * 0.0035)
                        sl = round_to_tick(trg + risk)
                        t1 = round_to_tick(trg - (2.0 * risk))
                        armed_setup = {
                            'direction': direction,
                            'trigger_price': trg,
                            'sl_price': sl,
                            't1_price': t1,
                            'pattern': 'SETUP_3_VANDE_BHARAT',
                            'armed_index': i
                        }

    return calculate_metrics(trades, daily_pnls)

def simulate_trade(day_df, entry_idx, direction, entry_p, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, pattern):
    half_qty = (lots // 2) * lot_size if lots > 1 else total_qty // 2
    runner_qty = total_qty - half_qty

    partial_booked = False
    current_sl = sl_p
    realized_pnl = 0.0
    exit_reason = "EOD_1500_EXIT"
    exit_time = None
    exit_price = None

    for j in range(entry_idx + 1, len(day_df)):
        bar = day_df.iloc[j]
        bar_time = bar.name.time()
        b_high = float(bar['High'])
        b_low = float(bar['Low'])
        b_close = float(bar['Close'])
        b_ema10 = float(bar['EMA10'])

        if bar_time >= datetime.time(15, 0):
            exit_price = b_close
            exit_time = bar.name
            exit_reason = "15:00_EOD_HARD_EXIT"
            if partial_booked:
                runner_pts = (exit_price - entry_p) if direction == 'LONG' else (entry_p - exit_price)
                realized_pnl += (runner_pts * runner_qty)
            else:
                pts = (exit_price - entry_p) if direction == 'LONG' else (entry_p - exit_price)
                realized_pnl = pts * total_qty
            break

        if direction == 'LONG':
            if b_low <= current_sl:
                exit_price = current_sl
                exit_time = bar.name
                exit_reason = "COST_SL_HIT" if partial_booked else "STOP_LOSS_HIT"
                if partial_booked:
                    runner_pts = current_sl - entry_p
                    realized_pnl += (runner_pts * runner_qty)
                else:
                    realized_pnl = (current_sl - entry_p) * total_qty
                break
        else:
            if b_high >= current_sl:
                exit_price = current_sl
                exit_time = bar.name
                exit_reason = "COST_SL_HIT" if partial_booked else "STOP_LOSS_HIT"
                if partial_booked:
                    runner_pts = entry_p - current_sl
                    realized_pnl += (runner_pts * runner_qty)
                else:
                    realized_pnl = (entry_p - current_sl) * total_qty
                break

        if not partial_booked:
            if direction == 'LONG' and b_high >= t1_p:
                partial_booked = True
                current_sl = entry_p
                gain_pts = t1_p - entry_p
                realized_pnl += (gain_pts * half_qty)
            elif direction == 'SHORT' and b_low <= t1_p:
                partial_booked = True
                current_sl = entry_p
                gain_pts = entry_p - t1_p
                realized_pnl += (gain_pts * half_qty)

        if partial_booked:
            if direction == 'LONG' and b_close < b_ema10:
                exit_price = b_close
                exit_time = bar.name
                exit_reason = "10_EMA_RUNNER_EXIT"
                runner_pts = exit_price - entry_p
                realized_pnl += (runner_pts * runner_qty)
                break
            elif direction == 'SHORT' and b_close > b_ema10:
                exit_price = b_close
                exit_time = bar.name
                exit_reason = "10_EMA_RUNNER_EXIT"
                runner_pts = entry_p - exit_price
                realized_pnl += (runner_pts * runner_qty)
                break

    return {
        'date': trade_date,
        'symbol': sym,
        'direction': direction,
        'pattern': pattern,
        'entry_price': entry_p,
        'exit_price': exit_price,
        'sl_price': sl_p,
        'target_price': t1_p,
        'lots': lots,
        'total_qty': total_qty,
        'realized_pnl': realized_pnl,
        'exit_reason': exit_reason,
        'partial_booked': partial_booked
    }

def calculate_metrics(trades, daily_pnls):
    if not trades:
        return {'total_trades': 0, 'net_pnl': 0, 'win_rate': 0, 'profit_factor': 0, 'max_dd': 0}

    df_trades = pd.DataFrame(trades)
    total_trades = len(df_trades)
    wins = df_trades[df_trades['realized_pnl'] > 0]
    losses = df_trades[df_trades['realized_pnl'] < 0]

    win_rate = (len(wins) / total_trades) * 100.0
    total_pnl = df_trades['realized_pnl'].sum()
    gross_profit = wins['realized_pnl'].sum()
    gross_loss = abs(losses['realized_pnl'].sum())
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')

    pnl_series = pd.Series(list(daily_pnls.values()))
    cum_pnl = pnl_series.cumsum()
    peak = cum_pnl.cummax()
    drawdown = peak - cum_pnl
    max_dd = drawdown.max() if not drawdown.empty else 0.0

    return {
        'total_trades': total_trades,
        'wins': len(wins),
        'losses': len(losses),
        'win_rate': win_rate,
        'gross_profit': gross_profit,
        'gross_loss': gross_loss,
        'net_pnl': total_pnl,
        'profit_factor': profit_factor,
        'max_dd': max_dd
    }

def main():
    print("================================================================================")
    print(" 🔬 SYSTEMATIC DRAWDOWN REDUCTION OPTIMIZATION EXPERIMENTS")
    print("================================================================================")

    data_5m = {}
    daily_data = {}

    for ticker in UNIVERSE:
        sym = ticker.replace(".NS", "")
        try:
            df_5m = yf.download(ticker, interval='5m', period='60d', progress=False)
            if df_5m.empty or len(df_5m) < 50: continue
            if isinstance(df_5m.columns, pd.MultiIndex): df_5m.columns = df_5m.columns.get_level_values(0)
            df_5m.index = pd.to_datetime(df_5m.index).tz_convert('Asia/Kolkata')
            data_5m[sym] = df_5m

            df_d = yf.download(ticker, interval='1d', period='90d', progress=False)
            if not df_d.empty:
                if isinstance(df_d.columns, pd.MultiIndex): df_d.columns = df_d.columns.get_level_values(0)
                if df_d.index.tz is None:
                    df_d.index = df_d.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
                else:
                    df_d.index = df_d.index.tz_convert('Asia/Kolkata')
                daily_data[sym] = df_d
        except Exception as e:
            print(f"warning: failed loading daily data for {ticker}: {e}")
            continue

    all_dates = sorted(list(set(d for df in data_5m.values() for d in df.index.date)))
    trading_dates = all_dates[1:]

    experiments = [
        ("1. Baseline (Current Fixed 2 Lots, Max 3 Trades)", {
            'use_vande_bharat': True, 'max_consecutive_losses': 3, 'max_sl_pct': 10.0, 'daily_ema_filter': False, 'max_daily_trades': 3
        }),
        ("2. Pure LVR Pullback Only (Disable Setup 3 Vande Bharat)", {
            'use_vande_bharat': False, 'max_consecutive_losses': 3, 'max_sl_pct': 10.0, 'daily_ema_filter': False, 'max_daily_trades': 3
        }),
        ("3. Tight SL Range Filter (Max SL <= 0.75% of stock price)", {
            'use_vande_bharat': False, 'max_consecutive_losses': 3, 'max_sl_pct': 0.75, 'daily_ema_filter': False, 'max_daily_trades': 3
        }),
        ("4. Daily 1-Loss Circuit Breaker (Max 1 Loss per day, Max 2 Trades)", {
            'use_vande_bharat': False, 'max_consecutive_losses': 1, 'max_sl_pct': 0.75, 'daily_ema_filter': False, 'max_daily_trades': 2
        }),
        ("5. Macro Daily 20 EMA Trend Alignment Filter", {
            'use_vande_bharat': False, 'max_consecutive_losses': 1, 'max_sl_pct': 0.75, 'daily_ema_filter': True, 'max_daily_trades': 2
        }),
        ("6. Master Low-Drawdown System (Pure LVR + SL <= 0.60% + Daily Trend + Max 1 Loss/Day)", {
            'use_vande_bharat': False, 'max_consecutive_losses': 1, 'max_sl_pct': 0.60, 'daily_ema_filter': True, 'max_daily_trades': 2
        }),
    ]

    results = []
    for name, params in experiments:
        res = run_experiment(data_5m, daily_data, trading_dates, **params)
        res['name'] = name
        results.append(res)

    print("\n" + "="*105)
    print(f"{'Experiment Configuration':<48} | {'Trades':<6} | {'Win Rate':<8} | {'Profit Factor':<13} | {'Net P&L (₹)':<14} | {'Max Drawdown (₹)':<16}")
    print("="*105)
    for r in results:
        pnl_str = f"+₹{r['net_pnl']:,.0f}" if r['net_pnl'] >= 0 else f"-₹{abs(r['net_pnl']):,.0f}"
        dd_str = f"₹{r['max_dd']:,.0f}"
        print(f"{r['name']:<48} | {r['total_trades']:<6d} | {r['win_rate']:<7.1f}% | {r['profit_factor']:<13.2f} | {pnl_str:<14} | {dd_str:<16}")
    print("="*105)

if __name__ == '__main__':
    main()
