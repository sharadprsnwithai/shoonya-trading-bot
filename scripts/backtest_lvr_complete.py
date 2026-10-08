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

# ==============================================================================
# Universe of Top Liquid F&O Stocks (Broad Nifty 50 / F&O Segment)
# ==============================================================================
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

def run_backtest(dynamic_sizing=False):
    sizing_label = "DYNAMIC POSITION SIZING (1% Risk / Trade on ₹10L Capital)" if dynamic_sizing else "FIXED SIZING (2 Lots per Trade)"
    print("\n" + "="*80)
    print(f" 🚀 BACKTEST MODE: {sizing_label}")
    print("="*80)

    data_5m = {}
    daily_data = {}

    for ticker in UNIVERSE:
        sym = ticker.replace(".NS", "")
        try:
            df_5m = yf.download(ticker, interval='5m', period='60d', progress=False)
            if df_5m.empty or len(df_5m) < 50:
                continue
            if isinstance(df_5m.columns, pd.MultiIndex):
                df_5m.columns = df_5m.columns.get_level_values(0)
            df_5m.index = pd.to_datetime(df_5m.index).tz_convert('Asia/Kolkata')
            data_5m[sym] = df_5m

            df_d = yf.download(ticker, interval='1d', period='90d', progress=False)
            if not df_d.empty:
                if isinstance(df_d.columns, pd.MultiIndex):
                    df_d.columns = df_d.columns.get_level_values(0)
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

    trades = []
    daily_pnls = defaultdict(float)

    for trade_date in trading_dates:
        candidates_pool = []
        for sym, df in data_5m.items():
            day_df = df[df.index.date == trade_date]
            if len(day_df) < 5: 
                continue
            
            d_df = daily_data.get(sym)
            if d_df is None or d_df.empty:
                continue
            past_days = d_df[d_df.index.date < trade_date]
            if past_days.empty:
                continue
            prev_day = past_days.iloc[-1]
            pdh = float(prev_day['High'])
            pdl = float(prev_day['Low'])

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
                'lot_size': LOT_SIZES.get(sym, 250)
            })

        if not candidates_pool:
            continue

        gainers = sorted([c for c in candidates_pool if c['pct_chg'] > 0.0], key=lambda x: x['pct_chg'], reverse=True)[:5]
        losers = sorted([c for c in candidates_pool if c['pct_chg'] < 0.0], key=lambda x: x['pct_chg'])[:5]
        watchlist = [(c, 'LONG') for c in gainers] + [(c, 'SHORT') for c in losers]

        session_trades = 0
        max_daily_trades = 3

        for candidate, direction in watchlist:
            if session_trades >= max_daily_trades:
                break

            sym = candidate['symbol']
            day_df = candidate['day_df']
            pdh = candidate['pdh']
            pdl = candidate['pdl']
            lot_size = candidate['lot_size']

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

                if c_time >= datetime.time(13, 0):
                    break

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

                    if setup_dir == 'LONG' and c_low <= sl_p:
                        armed_setup = None
                    elif setup_dir == 'SHORT' and c_high >= sl_p:
                        armed_setup = None
                    elif setup_dir == 'LONG' and c_high >= trigger_p:
                        entry_price = trigger_p
                        if entry_price > c_vwap and entry_price > pdh:
                            unit_risk = abs(entry_price - sl_p)
                            if dynamic_sizing and unit_risk > 0:
                                lots = max(1, min(10, int(10000.0 / (unit_risk * lot_size))))
                            else:
                                lots = 2
                            total_qty = lots * lot_size

                            trade_res = simulate_trade(day_df, i, 'LONG', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, armed_setup['pattern'])
                            trades.append(trade_res)
                            daily_pnls[trade_date] += trade_res['realized_pnl']
                            session_trades += 1
                            armed_setup = None
                            break
                        else:
                            armed_setup = None
                    elif setup_dir == 'SHORT' and c_low <= trigger_p:
                        entry_price = trigger_p
                        if entry_price < c_vwap and entry_price < pdl:
                            unit_risk = abs(entry_price - sl_p)
                            if dynamic_sizing and unit_risk > 0:
                                lots = max(1, min(10, int(10000.0 / (unit_risk * lot_size))))
                            else:
                                lots = 2
                            total_qty = lots * lot_size

                            trade_res = simulate_trade(day_df, i, 'SHORT', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, armed_setup['pattern'])
                            trades.append(trade_res)
                            daily_pnls[trade_date] += trade_res['realized_pnl']
                            session_trades += 1
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

                if not lvr_matched and i >= 1:
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

    print_results(trades, daily_pnls, len(trading_dates))

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

def print_results(trades, daily_pnls, total_days):
    if not trades:
        print(" No trades executed during the backtest window.")
        return

    df_trades = pd.DataFrame(trades)
    total_trades = len(df_trades)
    wins = df_trades[df_trades['realized_pnl'] > 0]
    losses = df_trades[df_trades['realized_pnl'] < 0]
    evens = df_trades[df_trades['realized_pnl'] == 0]

    win_rate = (len(wins) / total_trades) * 100.0
    total_pnl = df_trades['realized_pnl'].sum()
    gross_profit = wins['realized_pnl'].sum()
    gross_loss = abs(losses['realized_pnl'].sum())
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')

    avg_win = wins['realized_pnl'].mean() if len(wins) > 0 else 0.0
    avg_loss = abs(losses['realized_pnl'].mean()) if len(losses) > 0 else 0.0
    reward_to_risk = (avg_win / avg_loss) if avg_loss > 0 else float('inf')

    pnl_series = pd.Series(list(daily_pnls.values()))
    cum_pnl = pnl_series.cumsum()
    peak = cum_pnl.cummax()
    drawdown = peak - cum_pnl
    max_dd = drawdown.max() if not drawdown.empty else 0.0

    print(f" Total Trading Days:        {total_days}")
    print(f" Total Trades Executed:     {total_trades}")
    print(f" Winning Trades:            {len(wins)} ({win_rate:.1f}%)")
    print(f" Losing Trades:             {len(losses)} ({len(losses)/total_trades*100.0:.1f}%)")
    print(f" Breakeven / Cost SL Trades:{len(evens)}")
    print(f"--------------------------------------------------------------------------------")
    print(f" Gross Profit:              +₹{gross_profit:,.2f}")
    print(f" Gross Loss:                -₹{gross_loss:,.2f}")
    print(f" Net Realized P&L:          +₹{total_pnl:,.2f}" if total_pnl >= 0 else f" Net Realized P&L:          -₹{abs(total_pnl):,.2f}")
    print(f" Profit Factor:             {profit_factor:.2f}")
    print(f" Avg Win / Avg Loss (R:R):  {reward_to_risk:.2f} : 1")
    print(f" Average Trade Return:      ₹{df_trades['realized_pnl'].mean():,.2f}")
    print(f" Max Strategy Drawdown:     ₹{max_dd:,.2f}")
    print(f"--------------------------------------------------------------------------------")
    print(" Performance by Setup Pattern:")
    for pat, grp in df_trades.groupby('pattern'):
        p_wins = len(grp[grp['realized_pnl'] > 0])
        p_wr = (p_wins / len(grp)) * 100.0
        p_pnl = grp['realized_pnl'].sum()
        print(f"  • {pat:<24}: {len(grp):2d} trades | Win Rate: {p_wr:5.1f}% | Net P&L: ₹{p_pnl:+10,.2f}")

    print(f"--------------------------------------------------------------------------------")
    print(" Exit Breakdown:")
    for reason, grp in df_trades.groupby('exit_reason'):
        print(f"  • {reason:<24}: {len(grp):2d} trades | Net P&L: ₹{grp['realized_pnl'].sum():+10,.2f}")
    print("="*80)

if __name__ == '__main__':
    run_backtest(dynamic_sizing=False)
    run_backtest(dynamic_sizing=True)
