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

def run_sensex_backtest(
    df_merged,
    lot_size=20,                    # BSE SENSEX Lot size
    num_lots=10,                    # 10 lots = 200 qty
    sl_pts=100.0,                   # Sensex scaled SL (~30 pts Nifty * 3.3)
    tp_pts=160.0,                   # Sensex scaled TP (~50 pts Nifty * 3.3)
    trail_trigger1=40.0,            # Step 1 trigger (+40 pts)
    trail_lock1=6.0,                # Step 1 lock (+6 pts breakeven)
    trail_trigger2=80.0,            # Step 2 trigger (+80 pts)
    trail_lock2=50.0,               # Step 2 lock (+50 pts)
    max_vwap_dist=115.0,            # Sensex scaled VWAP proximity (~35 * 3.3)
    adx_min=22.0,
    max_trades_per_day=2,
    theta_decay_per_hour=7.5        # Sensex theta decay per hour (~2.5 * 3)
):
    total_qty = lot_size * num_lots

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

        # 1. Manage Open Position
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

            # Trailing SL calculation
            effective_sl = -sl_pts
            if trail_trigger2 > 0 and current_trade['max_fav_pts'] >= trail_trigger2:
                effective_sl = trail_lock2
            elif trail_trigger1 > 0 and current_trade['max_fav_pts'] >= trail_trigger1:
                effective_sl = trail_lock1

            # SL / Trailing SL exit
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

            # TP exit
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

            # 15m SuperTrend Flip Exit
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

            # Mandatory EOD Square-Off
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

        # 2. Check New Entry
        if current_trade is None and trades_today < max_trades_per_day:
            if start_t <= t <= end_t:
                # RSI 5m vs 15m crossover triggers
                bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)

                if adx >= adx_min:
                    # Bullish Entry: Crossover + Spot > VWAP + VWAP distance <= max_dist + ST Bullish + (+DI > -DI)
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

                    # Bearish Entry: Crossover + Spot < VWAP + VWAP distance <= max_dist + ST Bearish + (-DI > +DI)
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
        return {'trades': 0, 'win_rate': 0, 'pnl': 0, 'profit_factor': 0, 'max_dd': 0, 'trades_df': pd.DataFrame()}

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
        'gross_profit': gross_profit,
        'gross_loss': gross_loss,
        'profit_factor': profit_factor,
        'max_dd': max_dd,
        'trades_df': df_res
    }

def main():
    ticker = "^BSESN"
    print(f"Fetching last 60 days 5-minute data for SENSEX ({ticker})...")
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.empty:
        print("Error: No data returned for BSE SENSEX.")
        return

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

    print(f"Total evaluated 5-min bars for BSE SENSEX: {len(df_merged)}")
    print(f"Index Price Range: {df_merged['Close'].min():,.1f} - {df_merged['Close'].max():,.1f}")

    print("\n" + "="*105)
    print("BSE SENSEX INTRADAY STRATEGY BACKTEST (10 LOTS = 200 QTY, LOT SIZE = 20)")
    print("="*105)

    experiments = [
        ("1. Unhedged Fixed SL/TP (SL 100 pts, TP 160 pts, No Trailing)", {
            "sl_pts": 100.0, "tp_pts": 160.0, "trail_trigger1": 0.0, "trail_lock1": 0.0, "trail_trigger2": 0.0, "trail_lock2": 0.0, "max_vwap_dist": 999.0, "adx_min": 20.0
        }),
        ("2. Base + Stepped Trailing Stop (+40->+6, +80->+50)", {
            "sl_pts": 100.0, "tp_pts": 160.0, "trail_trigger1": 40.0, "trail_lock1": 6.0, "trail_trigger2": 80.0, "trail_lock2": 50.0, "max_vwap_dist": 999.0, "adx_min": 20.0
        }),
        ("3. Stepped Trailing SL + No-Chasing (VWAP <= 120 pts)", {
            "sl_pts": 100.0, "tp_pts": 160.0, "trail_trigger1": 40.0, "trail_lock1": 6.0, "trail_trigger2": 80.0, "trail_lock2": 50.0, "max_vwap_dist": 120.0, "adx_min": 20.0
        }),
        ("4. Stepped Trailing SL + No-Chasing (VWAP <= 120 pts) + ADX >= 22", {
            "sl_pts": 100.0, "tp_pts": 160.0, "trail_trigger1": 40.0, "trail_lock1": 6.0, "trail_trigger2": 80.0, "trail_lock2": 50.0, "max_vwap_dist": 120.0, "adx_min": 22.0
        }),
        ("5. Tight Risk Setup (SL 80 pts, TP 150 pts, VWAP <= 100 pts, ADX >= 22)", {
            "sl_pts": 80.0, "tp_pts": 150.0, "trail_trigger1": 35.0, "trail_lock1": 6.0, "trail_trigger2": 70.0, "trail_lock2": 45.0, "max_vwap_dist": 100.0, "adx_min": 22.0
        }),
        ("6. High-Target Setup (SL 100 pts, TP 200 pts, VWAP <= 110 pts, ADX >= 22)", {
            "sl_pts": 100.0, "tp_pts": 200.0, "trail_trigger1": 40.0, "trail_lock1": 6.0, "trail_trigger2": 90.0, "trail_lock2": 60.0, "max_vwap_dist": 110.0, "adx_min": 22.0
        })
    ]

    for name, params in experiments:
        res = run_sensex_backtest(df_merged, **params)
        pnl_str = f"+Rs {res['pnl']:,.2f}" if res['pnl'] >= 0 else f"-Rs {abs(res['pnl']):,.2f}"
        dd_str = f"-Rs {abs(res['max_dd']):,.2f}"
        print(f"\n>> {name}")
        print(f"   Trades: {res['trades']:<2} | Win Rate: {res['win_rate']:<4.1f}% | Net PnL: {pnl_str:<14} | PF: {res['profit_factor']:<4.2f} | Max DD: {dd_str}")

    # Print Detailed Trade Log for Champion Setup (Experiment #4)
    champ = run_sensex_backtest(df_merged, **experiments[3][1])
    if champ and not champ['trades_df'].empty:
        df_t = champ['trades_df']
        print("\n" + "="*105)
        print("SAMPLE SENSEX TRADE LOG (LAST 15 TRADES)")
        print("="*105)
        print(f"{'Date':<12} {'Entry':<6} {'Dir':<8} {'Sensex Spot':<12} {'VWAP':<10} {'ADX':<6} {'Exit':<6} {'Reason':<16} {'Opt Pts':<10} {'PnL (Rs)':<12}")
        print("-"*105)
        for _, t in df_t.tail(15).iterrows():
            d_str = t['entry_time'].strftime("%d-%b")
            t_in = t['entry_time'].strftime("%H:%M")
            t_out = t['exit_time'].strftime("%H:%M") if t['exit_time'] else "N/A"
            pnl_val = f"{t['pnl']:+,.0f}"
            print(f"{d_str:<12} {t_in:<6} {t['direction']:<8} {t['entry_spot']:<12.1f} {t['vwap_at_entry']:<10.1f} {t['adx_at_entry']:<6.1f} {t_out:<6} {t['exit_reason']:<16} {t['opt_pts']:<+10.1f} {pnl_val:<12}")

if __name__ == '__main__':
    main()
