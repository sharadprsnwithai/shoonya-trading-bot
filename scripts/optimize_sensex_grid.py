import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from test_vwap_st_variations import calculate_supertrend, calculate_adx, calculate_intraday_vwap

def calculate_rsi(series, period=14):
    delta = series.diff()
    gain = (delta.where(delta > 0, 0)).fillna(0)
    loss = (-delta.where(delta < 0, 0)).fillna(0)
    
    avg_gain = gain.rolling(window=period, min_periods=period).mean()
    avg_loss = loss.rolling(window=period, min_periods=period).mean()
    
    for i in range(period, len(series)):
        avg_gain.iloc[i] = (avg_gain.iloc[i-1] * (period - 1) + gain.iloc[i]) / period
        avg_loss.iloc[i] = (avg_loss.iloc[i-1] * (period - 1) + loss.iloc[i]) / period
        
    rs = avg_gain / avg_loss
    return 100 - (100 / (1 + rs))

def run_simulation(df_merged, sl_pts, tp_pts, trail_trigger1, trail_lock1, trail_trigger2, trail_lock2, max_vwap_dist, adx_min):
    lot_size = 20
    num_lots = 10
    total_qty = lot_size * num_lots
    theta_decay_per_hour = 7.5

    trades = []
    current_trade = None
    trades_today = 0
    daily_pnl_pts = 0.0
    current_day = None

    dates = df_merged['DateOnly'].values
    times = df_merged.index.time
    closes = df_merged['Close'].values
    vwaps = df_merged['VWAP'].values
    st_dirs = df_merged['ST_Direction'].values
    adxs = df_merged['ADX'].values
    p_dis = df_merged['Plus_DI'].values
    m_dis = df_merged['Minus_DI'].values
    rsi_5ms = df_merged['RSI_5m'].values
    rsi_15ms = df_merged['RSI_15m'].values

    start_t = datetime.time(9, 30)
    end_t = datetime.time(14, 15)

    for i in range(1, len(df_merged)):
        day = dates[i]
        t = times[i]
        spot = closes[i]
        vwap = vwaps[i]
        st_dir = st_dirs[i]
        adx = adxs[i]
        p_di = p_dis[i]
        m_di = m_dis[i]
        rsi_5 = rsi_5ms[i]
        rsi_15 = rsi_15ms[i]
        timestamp = df_merged.index[i]

        prev_spot = closes[i - 1]
        prev_vwap = vwaps[i - 1]
        prev_st_dir = st_dirs[i - 1]
        prev_rsi_5 = rsi_5ms[i - 1]
        prev_rsi_15 = rsi_15ms[i - 1]

        if day != current_day:
            current_day = day
            trades_today = 0
            daily_pnl_pts = 0.0
            if current_trade:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['exit_reason'] = "DAY_END"
                trades.append(current_trade)
                current_trade = None

        if current_trade is not None:
            entry_spot = current_trade['entry_spot']
            direction = current_trade['direction']
            hours_held = (timestamp - current_trade['entry_time']).total_seconds() / 3600.0
            theta_pts = hours_held * theta_decay_per_hour

            if direction == 'BULLISH':
                spot_move = spot - entry_spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts
            else:
                spot_move = entry_spot - spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts

            if opt_pnl_pts > current_trade['max_fav_pts']:
                current_trade['max_fav_pts'] = opt_pnl_pts

            effective_sl = -sl_pts
            if trail_trigger2 > 0 and current_trade['max_fav_pts'] >= trail_trigger2:
                effective_sl = trail_lock2
            elif trail_trigger1 > 0 and current_trade['max_fav_pts'] >= trail_trigger1:
                effective_sl = trail_lock1

            if opt_pnl_pts <= effective_sl:
                exit_reason = "TRAIL_SL_LOCK" if effective_sl > -sl_pts else "STOP_LOSS"
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = effective_sl
                current_trade['pnl'] = effective_sl * total_qty
                current_trade['exit_reason'] = exit_reason
                daily_pnl_pts += effective_sl
                trades.append(current_trade)
                current_trade = None
                continue

            elif opt_pnl_pts >= tp_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = tp_pts
                current_trade['pnl'] = tp_pts * total_qty
                current_trade['exit_reason'] = "TARGET_PROFIT"
                daily_pnl_pts += tp_pts
                trades.append(current_trade)
                current_trade = None
                continue

            if (direction == 'BULLISH' and st_dir == -1) or (direction == 'BEARISH' and st_dir == 1):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * total_qty
                current_trade['exit_reason'] = "ST_FLIP"
                daily_pnl_pts += opt_pnl_pts
                trades.append(current_trade)
                current_trade = None
                continue

            if t >= datetime.time(15, 5):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * total_qty
                current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                daily_pnl_pts += opt_pnl_pts
                trades.append(current_trade)
                current_trade = None
                continue

        if current_trade is None and trades_today < 2:
            if start_t <= t <= end_t:
                bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)

                if adx >= adx_min:
                    if bullish_cross and spot > vwap and st_dir == 1 and p_di > m_di:
                        vwap_dist = spot - vwap
                        if vwap_dist <= max_vwap_dist:
                            current_trade = {
                                'trade_id': f"TRD_{len(trades)+1}",
                                'entry_time': timestamp,
                                'direction': 'BULLISH',
                                'entry_spot': spot,
                                'vwap_at_entry': vwap,
                                'adx_at_entry': adx,
                                'max_fav_pts': 0.0,
                                'opt_pts': 0.0,
                                'pnl': 0.0,
                                'exit_time': None,
                                'exit_spot': None,
                                'exit_reason': None
                            }
                            trades_today += 1

                    elif bearish_cross and spot < vwap and st_dir == -1 and m_di > p_di:
                        vwap_dist_bear = vwap - spot
                        if vwap_dist_bear <= max_vwap_dist:
                            current_trade = {
                                'trade_id': f"TRD_{len(trades)+1}",
                                'entry_time': timestamp,
                                'direction': 'BEARISH',
                                'entry_spot': spot,
                                'vwap_at_entry': vwap,
                                'adx_at_entry': adx,
                                'max_fav_pts': 0.0,
                                'opt_pts': 0.0,
                                'pnl': 0.0,
                                'exit_time': None,
                                'exit_spot': None,
                                'exit_reason': None
                            }
                            trades_today += 1

    if not trades:
        return None

    df_res = pd.DataFrame(trades)
    wins = df_res[df_res['pnl'] > 0]
    losses = df_res[df_res['pnl'] <= 0]
    win_rate = (len(wins) / len(df_res)) * 100.0
    total_pnl = df_res['pnl'].sum()
    gross_profit = wins['pnl'].sum() if not wins.empty else 0.0
    gross_loss = abs(losses['pnl'].sum()) if not losses.empty else 1.0
    profit_factor = gross_profit / gross_loss if gross_loss > 0 else float('inf')

    df_res['cum_pnl'] = df_res['pnl'].cumsum()
    df_res['peak'] = df_res['cum_pnl'].cummax()
    df_res['drawdown'] = df_res['cum_pnl'] - df_res['peak']
    max_dd = df_res['drawdown'].min()

    return {
        'trades': len(df_res),
        'win_rate': win_rate,
        'pnl': total_pnl,
        'profit_factor': profit_factor,
        'max_dd': max_dd
    }

def main():
    ticker = "^BSESN"
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.index.tz is None:
        df_5m.index = df_5m.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
    else:
        df_5m.index = df_5m.index.tz_convert('Asia/Kolkata')

    df_5m = df_5m.between_time('09:15', '15:30')
    df_5m['RSI_5m'] = calculate_rsi(df_5m['Close'], 14)
    df_5m = calculate_intraday_vwap(df_5m)

    df_15m = df_5m.resample('15min', closed='left', label='left').agg({
        'Open': 'first',
        'High': 'max',
        'Low': 'min',
        'Close': 'last',
        'Volume': 'sum'
    }).dropna()

    df_15m = calculate_supertrend(df_15m, period=10, multiplier=2.0)
    df_15m = calculate_adx(df_15m, period=14)
    df_15m['RSI_15m'] = calculate_rsi(df_15m['Close'], 14)

    df_5m['Time_15m'] = df_5m.index.floor('15min')
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI', 'RSI_15m']
    df_15m_subset = df_15m[cols_15m].shift(1)
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX', 'RSI_5m', 'RSI_15m'])

    print("=========================================================================================================")
    print("SENSEX GRID SEARCH: MINIMIZING DRAWDOWN & MAXIMIZING PROFIT FACTOR (10 LOTS = 200 QTY)")
    print("=========================================================================================================")

    results = []
    for sl in [70.0, 80.0, 100.0, 120.0]:
        for tp in [150.0, 180.0, 220.0]:
            for adx in [20.0, 22.0, 25.0]:
                for max_dist in [80.0, 100.0, 120.0, 150.0]:
                    for (t1, l1, t2, l2) in [
                        (35.0, 6.0, 75.0, 45.0),
                        (40.0, 6.0, 80.0, 50.0),
                        (45.0, 8.0, 90.0, 60.0),
                        (0.0, 0.0, 0.0, 0.0)
                    ]:
                        res = run_simulation(
                            df_merged,
                            sl_pts=sl,
                            tp_pts=tp,
                            trail_trigger1=t1,
                            trail_lock1=l1,
                            trail_trigger2=t2,
                            trail_lock2=l2,
                            max_vwap_dist=max_dist,
                            adx_min=adx
                        )
                        if res:
                            results.append({
                                'sl': sl, 'tp': tp, 'adx': adx, 'dist': max_dist,
                                'trail': f"({t1}->{l1},{t2}->{l2})",
                                'trades': res['trades'],
                                'win_rate': res['win_rate'],
                                'pnl': res['pnl'],
                                'pf': res['profit_factor'],
                                'max_dd': res['max_dd']
                            })

    df_grid = pd.DataFrame(results)
    top_pnl = df_grid.sort_values(by=['pnl', 'pf'], ascending=[False, False]).head(10)
    top_dd = df_grid.sort_values(by=['max_dd', 'pf'], ascending=[False, False]).head(10)

    print("\n--- TOP 10 HIGHEST PROFIT & PROFIT FACTOR CONFIGURATIONS (SENSEX) ---")
    for _, r in top_pnl.iterrows():
        print(f"SL:{r['sl']:<3} TP:{r['tp']:<3} ADX:{r['adx']:<2} Dist:{r['dist']:<3} Trail:{r['trail']:<18} | Trades:{r['trades']:<2} Win:{r['win_rate']:<4.1f}% | PnL:+Rs {r['pnl']:<10,.2f} | PF:{r['pf']:<4.2f} | DD:-Rs {abs(r['max_dd']):<8,.2f}")

    print("\n--- TOP 10 LOWEST DRAWDOWN CONFIGURATIONS (SENSEX) ---")
    for _, r in top_dd.iterrows():
        print(f"SL:{r['sl']:<3} TP:{r['tp']:<3} ADX:{r['adx']:<2} Dist:{r['dist']:<3} Trail:{r['trail']:<18} | Trades:{r['trades']:<2} Win:{r['win_rate']:<4.1f}% | PnL:+Rs {r['pnl']:<10,.2f} | PF:{r['pf']:<4.2f} | DD:-Rs {abs(r['max_dd']):<8,.2f}")

if __name__ == '__main__':
    main()
