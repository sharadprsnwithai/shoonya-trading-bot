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

def run_grid():
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

    # Grid search across key parameters
    results = []

    for max_daily_trades in [1, 2, 3]:
        for max_loss in [1, 2]:
            for target_rr in [2.0, 2.5, 3.0]:
                for trail_mode in ['10EMA', '15EMA', 'COST_ONLY']:
                    trades, daily_pnls = simulate_grid_run(data_5m, daily_data, trading_dates, 
                                                           max_daily_trades=max_daily_trades, 
                                                           max_consecutive_losses=max_loss,
                                                           target_rr=target_rr,
                                                           trail_mode=trail_mode)
                    if not trades: continue
                    df_t = pd.DataFrame(trades)
                    wins = df_t[df_t['realized_pnl'] > 0]
                    losses = df_t[df_t['realized_pnl'] < 0]
                    tot_pnl = df_t['realized_pnl'].sum()
                    gp = wins['realized_pnl'].sum()
                    gl = abs(losses['realized_pnl'].sum())
                    pf = (gp / gl) if gl > 0 else float('inf')
                    wr = (len(wins) / len(df_t)) * 100.0
                    
                    pnl_series = pd.Series(list(daily_pnls.values()))
                    cum_pnl = pnl_series.cumsum()
                    dd = (cum_pnl.cummax() - cum_pnl).max() if not cum_pnl.empty else 0.0
                    calmar = (tot_pnl / dd) if dd > 0 else 0.0

                    results.append({
                        'max_trades': max_daily_trades,
                        'max_loss': max_loss,
                        'target_rr': target_rr,
                        'trail_mode': trail_mode,
                        'trades': len(df_t),
                        'win_rate': wr,
                        'profit_factor': pf,
                        'net_pnl': tot_pnl,
                        'max_dd': dd,
                        'calmar': calmar
                    })

    df_res = pd.DataFrame(results)
    df_sorted = df_res.sort_values(by='net_pnl', ascending=False)

    print("\n" + "="*115)
    print(" 🏆 TOP 8 STRATEGY VARIATIONS RANKED BY NET PROFIT & DRAWDOWN RATIO")
    print("="*115)
    print(f"{'Trades/Day':<10} | {'Max Loss/Day':<12} | {'T1 Target':<10} | {'Trail Mode':<10} | {'Trades':<6} | {'Win %':<6} | {'PF':<5} | {'Net P&L (₹)':<12} | {'Max DD (₹)':<12} | {'Return/DD'}")
    print("-"*115)
    for _, r in df_sorted.head(8).iterrows():
        print(f"{r['max_trades']:<10.0f} | {r['max_loss']:<12.0f} | {r['target_rr']:<10.1f} | {r['trail_mode']:<10} | {r['trades']:<6.0f} | {r['win_rate']:<5.1f}% | {r['profit_factor']:<5.2f} | +₹{r['net_pnl']:<11,.0f} | ₹{r['max_dd']:<11,.0f} | {r['calmar']:.2f}x")
    print("="*115)

def simulate_grid_run(data_5m, daily_data, trading_dates, max_daily_trades=2, max_consecutive_losses=1, target_rr=2.0, trail_mode='10EMA'):
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
            lot_size = candidate['lot_size']
            lots = 2
            total_qty = lots * lot_size

            day_df = day_df.copy()
            day_df['VWAP'] = calculate_vwap(day_df)
            span_val = 15 if trail_mode == '15EMA' else 10
            day_df['EMA_TRAIL'] = calculate_ema(day_df['Close'], span=span_val)

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

                    if setup_dir == 'LONG' and c_low <= sl_p:
                        armed_setup = None
                    elif setup_dir == 'SHORT' and c_high >= sl_p:
                        armed_setup = None
                    elif setup_dir == 'LONG' and c_high >= trigger_p:
                        entry_price = trigger_p
                        if entry_price > c_vwap and entry_price > pdh:
                            trade_res = simulate_trade_grid(day_df, i, 'LONG', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, trail_mode)
                            trades.append(trade_res)
                            pnl = trade_res['realized_pnl']
                            daily_pnls[trade_date] += pnl
                            session_trades += 1
                            if pnl < 0: consecutive_losses_today += 1
                            else: consecutive_losses_today = 0
                            armed_setup = None
                            break
                        else:
                            armed_setup = None
                    elif setup_dir == 'SHORT' and c_low <= trigger_p:
                        entry_price = trigger_p
                        if entry_price < c_vwap and entry_price < pdl:
                            trade_res = simulate_trade_grid(day_df, i, 'SHORT', entry_price, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, trail_mode)
                            trades.append(trade_res)
                            pnl = trade_res['realized_pnl']
                            daily_pnls[trade_date] += pnl
                            session_trades += 1
                            if pnl < 0: consecutive_losses_today += 1
                            else: consecutive_losses_today = 0
                            armed_setup = None
                            break
                        else:
                            armed_setup = None

                is_opposite = is_red if direction == 'LONG' else is_green

                if is_opposite and c_vol > 0 and c_vol <= rolling_lowest_vol:
                    if direction == 'LONG':
                        trg = round_to_tick(c_high + 0.05)
                        raw_sl = round_to_tick(c_low - 0.05)
                        risk = max(trg - raw_sl, trg * 0.0035)
                        sl = round_to_tick(trg - risk)
                        t1 = round_to_tick(trg + (target_rr * risk))
                    else:
                        trg = round_to_tick(c_low - 0.05)
                        raw_sl = round_to_tick(c_high + 0.05)
                        risk = max(raw_sl - trg, trg * 0.0035)
                        sl = round_to_tick(trg + risk)
                        t1 = round_to_tick(trg - (target_rr * risk))

                    armed_setup = {
                        'direction': direction,
                        'trigger_price': trg,
                        'sl_price': sl,
                        't1_price': t1,
                        'pattern': 'SETUP_2_LVR_PULLBACK',
                        'armed_index': i
                    }
                    rolling_lowest_vol = c_vol
                elif c_vol > 0 and c_vol < rolling_lowest_vol:
                    rolling_lowest_vol = c_vol

    return trades, daily_pnls

def simulate_trade_grid(day_df, entry_idx, direction, entry_p, sl_p, t1_p, total_qty, lot_size, lots, sym, trade_date, trail_mode):
    half_qty = (lots // 2) * lot_size if lots > 1 else total_qty // 2
    runner_qty = total_qty - half_qty

    partial_booked = False
    current_sl = sl_p
    realized_pnl = 0.0

    for j in range(entry_idx + 1, len(day_df)):
        bar = day_df.iloc[j]
        bar_time = bar.name.time()
        b_high = float(bar['High'])
        b_low = float(bar['Low'])
        b_close = float(bar['Close'])
        b_ema = float(bar['EMA_TRAIL'])

        if bar_time >= datetime.time(15, 0):
            exit_price = b_close
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
                if partial_booked:
                    runner_pts = current_sl - entry_p
                    realized_pnl += (runner_pts * runner_qty)
                else:
                    realized_pnl = (current_sl - entry_p) * total_qty
                break
        else:
            if b_high >= current_sl:
                exit_price = current_sl
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

        if partial_booked and trail_mode in ['10EMA', '15EMA']:
            if direction == 'LONG' and b_close < b_ema:
                exit_price = b_close
                runner_pts = exit_price - entry_p
                realized_pnl += (runner_pts * runner_qty)
                break
            elif direction == 'SHORT' and b_close > b_ema:
                exit_price = b_close
                runner_pts = entry_p - exit_price
                realized_pnl += (runner_pts * runner_qty)
                break

    return {
        'date': trade_date,
        'symbol': sym,
        'direction': direction,
        'entry_price': entry_p,
        'realized_pnl': realized_pnl
    }

if __name__ == '__main__':
    run_grid()
