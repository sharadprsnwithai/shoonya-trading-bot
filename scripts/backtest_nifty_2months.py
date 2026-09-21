import datetime
import numpy as np
import pandas as pd
import yfinance as yf

def calculate_supertrend(df, period=10, multiplier=2.0):
    high = df['High'].values
    low = df['Low'].values
    close = df['Close'].values
    n = len(df)

    tr = np.zeros(n)
    tr[0] = high[0] - low[0]
    for i in range(1, n):
        tr[i] = max(
            high[i] - low[i],
            abs(high[i] - close[i - 1]),
            abs(low[i] - close[i - 1])
        )

    atr = np.zeros(n)
    if n >= period:
        atr[period - 1] = np.mean(tr[:period])
        for i in range(period, n):
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period

    hl2 = (high + low) / 2.0
    upper_basic = hl2 + multiplier * atr
    lower_basic = hl2 - multiplier * atr

    upper_band = np.zeros(n)
    lower_band = np.zeros(n)
    direction = np.zeros(n, dtype=int)
    supertrend = np.zeros(n)

    for i in range(period, n):
        if lower_basic[i] > lower_band[i - 1] or close[i - 1] < lower_band[i - 1]:
            lower_band[i] = lower_basic[i]
        else:
            lower_band[i] = lower_band[i - 1]

        if upper_basic[i] < upper_band[i - 1] or close[i - 1] > upper_band[i - 1]:
            upper_band[i] = upper_basic[i]
        else:
            upper_band[i] = upper_band[i - 1]

        if i == period:
            direction[i] = 1 if close[i] > upper_band[i] else -1
        else:
            if direction[i - 1] == -1 and close[i] > upper_band[i - 1]:
                direction[i] = 1
            elif direction[i - 1] == 1 and close[i] < lower_band[i - 1]:
                direction[i] = -1
            else:
                direction[i] = direction[i - 1]

        supertrend[i] = lower_band[i] if direction[i] == 1 else upper_band[i]

    df = df.copy()
    df['SuperTrend'] = supertrend
    df['ST_Direction'] = direction
    return df

def calculate_adx(df, period=14):
    high = df['High'].values
    low = df['Low'].values
    close = df['Close'].values
    n = len(df)

    plus_dm = np.zeros(n)
    minus_dm = np.zeros(n)
    tr = np.zeros(n)

    for i in range(1, n):
        up_move = high[i] - high[i - 1]
        down_move = low[i - 1] - low[i]

        if up_move > down_move and up_move > 0:
            plus_dm[i] = up_move
        if down_move > up_move and down_move > 0:
            minus_dm[i] = down_move

        tr[i] = max(
            high[i] - low[i],
            abs(high[i] - close[i - 1]),
            abs(low[i] - close[i - 1])
        )

    tr_smooth = np.zeros(n)
    plus_dm_smooth = np.zeros(n)
    minus_dm_smooth = np.zeros(n)

    if n > period:
        tr_smooth[period] = np.sum(tr[1:period + 1])
        plus_dm_smooth[period] = np.sum(plus_dm[1:period + 1])
        minus_dm_smooth[period] = np.sum(minus_dm[1:period + 1])

        for i in range(period + 1, n):
            tr_smooth[i] = tr_smooth[i - 1] - (tr_smooth[i - 1] / period) + tr[i]
            plus_dm_smooth[i] = plus_dm_smooth[i - 1] - (plus_dm_smooth[i - 1] / period) + plus_dm[i]
            minus_dm_smooth[i] = minus_dm_smooth[i - 1] - (minus_dm_smooth[i - 1] / period) + minus_dm[i]

    plus_di = np.zeros(n)
    minus_di = np.zeros(n)
    dx = np.zeros(n)

    for i in range(period, n):
        if tr_smooth[i] > 0:
            plus_di[i] = 100.0 * (plus_dm_smooth[i] / tr_smooth[i])
            minus_di[i] = 100.0 * (minus_dm_smooth[i] / tr_smooth[i])
        
        sum_di = plus_di[i] + minus_di[i]
        if sum_di > 0:
            dx[i] = 100.0 * abs(plus_di[i] - minus_di[i]) / sum_di

    adx = np.zeros(n)
    if n > 2 * period:
        adx[2 * period] = np.mean(dx[period:2 * period + 1])
        for i in range(2 * period + 1, n):
            adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period

    df = df.copy()
    df['ADX'] = adx
    df['Plus_DI'] = plus_di
    df['Minus_DI'] = minus_di
    return df

def calculate_intraday_vwap(df):
    df = df.copy()
    typical_price = (df['High'] + df['Low'] + df['Close']) / 3.0
    vol = df['Volume'].replace(0, 1)
    tp_vol = typical_price * vol

    df['DateOnly'] = df.index.date
    df['Cum_TP_Vol'] = tp_vol.groupby(df['DateOnly']).cumsum()
    df['Cum_Vol'] = vol.groupby(df['DateOnly']).cumsum()
    df['VWAP'] = df['Cum_TP_Vol'] / df['Cum_Vol']
    return df

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

def run_nifty_backtest(
    df_merged,
    lot_size=65,                    # NSE NIFTY Lot size
    num_lots=10,                    # 10 lots = 650 qty
    sl_pts=30.0,                    # Hard SL points on option
    tp_pts=50.0,                    # Target Profit points
    trail_trigger1=12.0,            # Step 1 trigger (+12 pts)
    trail_lock1=2.0,                # Step 1 lock (+2 pts breakeven)
    trail_trigger2=25.0,            # Step 2 trigger (+25 pts)
    trail_lock2=15.0,               # Step 2 lock (+15 pts)
    max_vwap_dist=35.0,             # Spot distance to VWAP limit
    adx_min=22.0,                   # ADX minimum
    use_vwap=True,
    use_supertrend=True,
    use_di=True,
    use_adx=True,
    use_trailing=True,
    use_vwap_gate=True,
    max_trades_per_day=1,
    theta_decay_per_hour=2.5        # Option selling intraday theta decay
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
            if use_trailing:
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
                trades.append(current_trade)
                daily_pnl_pts += effective_sl
                current_trade = None
                continue

            # Target Profit exit
            if opt_pnl_pts >= tp_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = tp_pts
                current_trade['pnl'] = tp_pts * total_qty
                current_trade['exit_reason'] = "TARGET_PROFIT"
                trades.append(current_trade)
                daily_pnl_pts += tp_pts
                current_trade = None
                continue

            # SuperTrend Flip exit
            if use_supertrend:
                if (direction == 'BULLISH' and st_dir == -1) or (direction == 'BEARISH' and st_dir == 1):
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['opt_pts'] = opt_pnl_pts
                    current_trade['pnl'] = opt_pnl_pts * total_qty
                    current_trade['exit_reason'] = "ST_FLIP"
                    trades.append(current_trade)
                    daily_pnl_pts += opt_pnl_pts
                    current_trade = None
                    continue

            # Mandatory 15:05 EOD Square-off
            if t >= datetime.time(15, 5):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * total_qty
                current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                trades.append(current_trade)
                daily_pnl_pts += opt_pnl_pts
                current_trade = None
                continue

        # 2. Check New Entry
        if current_trade is None and trades_today < max_trades_per_day:
            if start_t <= t <= end_t:
                bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)

                if bullish_cross:
                    if use_vwap and spot <= vwap:
                        continue
                    if use_vwap_gate and abs(spot - vwap) > max_vwap_dist:
                        continue
                    if use_supertrend and st_dir != 1:
                        continue
                    if use_adx and (adx < adx_min or np.isnan(adx)):
                        continue
                    if use_di and p_di <= m_di:
                        continue

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

                elif bearish_cross:
                    if use_vwap and spot >= vwap:
                        continue
                    if use_vwap_gate and abs(spot - vwap) > max_vwap_dist:
                        continue
                    if use_supertrend and st_dir != -1:
                        continue
                    if use_adx and (adx < adx_min or np.isnan(adx)):
                        continue
                    if use_di and m_di <= p_di:
                        continue

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
        return {'trades': 0, 'win_rate': 0, 'pnl': 0, 'gross_profit': 0, 'gross_loss': 0, 'profit_factor': 0, 'max_dd': 0, 'trades_df': pd.DataFrame()}

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

def print_result(res, name):
    print(f">> {name}")
    print(f"   Trades: {res['trades']:2d} | Win Rate: {res['win_rate']:4.1f}% | Net PnL: +Rs {res['pnl']:+10,.2f}  | PF: {res['profit_factor']:.2f} | Max DD: -Rs {abs(res['max_dd']):,.2f}\n")

def main():
    ticker = "^NSEI"
    print(f"Fetching last 60 days 5-minute data for NIFTY 50 ({ticker})...")
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.empty:
        print("Error: No data returned for NIFTY 50.")
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

    print(f"Total evaluated 5-min bars for NSE NIFTY: {len(df_merged)}")
    print(f"Index Price Range: {df_merged['Close'].min():,.1f} - {df_merged['Close'].max():,.1f}")

    print("\n" + "="*105)
    print("NSE NIFTY INTRADAY RSI CROSSOVER STRATEGY BACKTEST (10 LOTS = 650 QTY, LOT SIZE = 65)")
    print("="*105 + "\n")

    # 1. Raw Unfiltered Crossover (Fixed SL/TP, No Trailing)
    r1 = run_nifty_backtest(
        df_merged,
        use_vwap=False,
        use_supertrend=False,
        use_di=False,
        use_adx=False,
        use_trailing=False,
        use_vwap_gate=False,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=2
    )
    print_result(r1, "1. Unhedged Raw Crossover (SL 30 pts, TP 50 pts, No Filters, No Trailing)")

    # 2. + VWAP & 15m Supertrend Filters
    r2 = run_nifty_backtest(
        df_merged,
        use_vwap=True,
        use_supertrend=True,
        use_di=False,
        use_adx=False,
        use_trailing=False,
        use_vwap_gate=False,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=2
    )
    print_result(r2, "2. + VWAP & 15m Supertrend Filters")

    # 3. + ADX >= 22 & +DI/-DI Confluence
    r3 = run_nifty_backtest(
        df_merged,
        use_vwap=True,
        use_supertrend=True,
        use_di=True,
        use_adx=True,
        adx_min=22.0,
        use_trailing=False,
        use_vwap_gate=False,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=2
    )
    print_result(r3, "3. + 15m ADX >= 22 & +DI/-DI Confluence")

    # 4. + Stepped Trailing SL (+12 -> +2, +25 -> +15)
    r4 = run_nifty_backtest(
        df_merged,
        use_vwap=True,
        use_supertrend=True,
        use_di=True,
        use_adx=True,
        adx_min=22.0,
        use_trailing=True,
        trail_trigger1=12.0,
        trail_lock1=2.0,
        trail_trigger2=25.0,
        trail_lock2=15.0,
        use_vwap_gate=False,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=2
    )
    print_result(r4, "4. + Stepped Trailing SL (+12 -> +2, +25 -> +15)")

    # 5. Full CHAMPION SETUP: All Filters + Trailing SL + VWAP Proximity Gate <= 35 pts (Max 1 Trade/Day)
    r5 = run_nifty_backtest(
        df_merged,
        use_vwap=True,
        use_supertrend=True,
        use_di=True,
        use_adx=True,
        adx_min=22.0,
        use_trailing=True,
        trail_trigger1=12.0,
        trail_lock1=2.0,
        trail_trigger2=25.0,
        trail_lock2=15.0,
        use_vwap_gate=True,
        max_vwap_dist=35.0,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=1
    )
    print_result(r5, "5. [CHAMPION SETUP] Full Indicator Filters + Stepped Trailing SL + VWAP Gate <= 35 pts (Max 1 Trade/Day)")

    # 6. CHAMPION SETUP (Max 2 Trades/Day)
    r6 = run_nifty_backtest(
        df_merged,
        use_vwap=True,
        use_supertrend=True,
        use_di=True,
        use_adx=True,
        adx_min=22.0,
        use_trailing=True,
        trail_trigger1=12.0,
        trail_lock1=2.0,
        trail_trigger2=25.0,
        trail_lock2=15.0,
        use_vwap_gate=True,
        max_vwap_dist=35.0,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=2
    )
    print_result(r6, "6. [CHAMPION SETUP] Full Indicator Filters + Stepped Trailing SL + VWAP Gate <= 35 pts (Max 2 Trades/Day)")

    # 7. Retail Single Lot (65 Qty)
    r7 = run_nifty_backtest(
        df_merged,
        lot_size=65,
        num_lots=1,
        use_vwap=True,
        use_supertrend=True,
        use_di=True,
        use_adx=True,
        adx_min=22.0,
        use_trailing=True,
        trail_trigger1=12.0,
        trail_lock1=2.0,
        trail_trigger2=25.0,
        trail_lock2=15.0,
        use_vwap_gate=True,
        max_vwap_dist=35.0,
        sl_pts=30.0,
        tp_pts=50.0,
        max_trades_per_day=1
    )
    print_result(r7, "7. [CHAMPION SETUP] 1 Lot Retail (65 Qty, Max 1 Trade/Day)")

    # Detailed trade audit log of Champion Setup
    df_trades = r5['trades_df']
    if not df_trades.empty:
        print("="*105)
        print("NIFTY 50 CHAMPION SETUP - DETAILED TRADE AUDIT LOG (LAST 2 MONTHS)")
        print("="*105)
        print(f"{'Date':<12} {'Entry':<6} {'Dir':<8} {'Nifty Spot':<12} {'VWAP':<10} {'ADX':<6} {'Exit':<6} {'Reason':<16} {'Opt Pts':<10} {'PnL (Rs)':<12}")
        print("-"*105)
        for _, row in df_trades.iterrows():
            d_str = row['entry_time'].strftime("%d-%b")
            en_t = row['entry_time'].strftime("%H:%M")
            ex_t = row['exit_time'].strftime("%H:%M") if pd.notna(row['exit_time']) else "-"
            reason = str(row['exit_reason'])
            print(f"{d_str:<12} {en_t:<6} {row['direction']:<8} {row['entry_spot']:<12.1f} {row['vwap_at_entry']:<10.1f} {row['adx_at_entry']:<6.1f} {ex_t:<6} {reason:<16} {row['opt_pts']:+7.1f}   Rs {row['pnl']:+10,.0f}")
        print("="*105 + "\n")

if __name__ == '__main__':
    main()
