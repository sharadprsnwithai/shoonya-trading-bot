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

def prepare_data(ticker):
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.empty:
        return None

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
    return df_merged

def run_index_backtest(name, df_merged, lot_size, num_lots, sl_pts, tp_pts, t1, l1, t2, l2, max_vwap_dist, adx_min, theta_decay_hr):
    total_qty = lot_size * num_lots
    trades = []
    current_trade = None
    trades_today = 0
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
            if current_trade:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['exit_reason'] = "DAY_END"
                trades.append(current_trade)
                current_trade = None

        # Manage Open Trade
        if current_trade is not None:
            entry_spot = current_trade['entry_spot']
            direction = current_trade['direction']
            hours_held = (timestamp - current_trade['entry_time']).total_seconds() / 3600.0
            theta_pts = hours_held * theta_decay_hr

            if direction == 'BULLISH':
                spot_move = spot - entry_spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts
            else:
                spot_move = entry_spot - spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts

            if opt_pnl_pts > current_trade['max_fav_pts']:
                current_trade['max_fav_pts'] = opt_pnl_pts

            effective_sl = -sl_pts
            if t2 > 0 and current_trade['max_fav_pts'] >= t2:
                effective_sl = l2
            elif t1 > 0 and current_trade['max_fav_pts'] >= t1:
                effective_sl = l1

            if opt_pnl_pts <= effective_sl:
                exit_reason = "TRAIL_SL_LOCK" if effective_sl > -sl_pts else "STOP_LOSS"
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = effective_sl * total_qty
                current_trade['exit_reason'] = exit_reason
                trades.append(current_trade)
                current_trade = None
                continue

            if opt_pnl_pts >= tp_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = tp_pts * total_qty
                current_trade['exit_reason'] = "TARGET_PROFIT"
                trades.append(current_trade)
                current_trade = None
                continue

            if (direction == 'BULLISH' and st_dir == -1) or (direction == 'BEARISH' and st_dir == 1):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = opt_pnl_pts * total_qty
                current_trade['exit_reason'] = "ST_FLIP"
                trades.append(current_trade)
                current_trade = None
                continue

            if t >= datetime.time(15, 5):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = opt_pnl_pts * total_qty
                current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                trades.append(current_trade)
                current_trade = None
                continue

        # Check Entry
        if current_trade is None and trades_today < 1:
            if start_t <= t <= end_t:
                bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)

                if bullish_cross:
                    if spot <= vwap or abs(spot - vwap) > max_vwap_dist or st_dir != 1 or adx < adx_min or np.isnan(adx) or p_di <= m_di:
                        continue
                    current_trade = {
                        'symbol': name,
                        'type': 'INDEX_OPTION',
                        'entry_time': timestamp,
                        'direction': 'BULLISH',
                        'entry_spot': spot,
                        'vwap_at_entry': vwap,
                        'adx_at_entry': adx,
                        'max_fav_pts': 0.0,
                        'pnl': 0.0,
                        'exit_time': None,
                        'exit_spot': None,
                        'exit_reason': None
                    }
                    trades_today += 1

                elif bearish_cross:
                    if spot >= vwap or abs(spot - vwap) > max_vwap_dist or st_dir != -1 or adx < adx_min or np.isnan(adx) or m_di <= p_di:
                        continue
                    current_trade = {
                        'symbol': name,
                        'type': 'INDEX_OPTION',
                        'entry_time': timestamp,
                        'direction': 'BEARISH',
                        'entry_spot': spot,
                        'vwap_at_entry': vwap,
                        'adx_at_entry': adx,
                        'max_fav_pts': 0.0,
                        'pnl': 0.0,
                        'exit_time': None,
                        'exit_spot': None,
                        'exit_reason': None
                    }
                    trades_today += 1

    df_res = pd.DataFrame(trades)
    if df_res.empty:
        return {'symbol': name, 'trades': 0, 'wins': 0, 'losses': 0, 'win_rate': 0.0, 'pnl': 0.0, 'gross_profit': 0.0, 'gross_loss': 0.0, 'profit_factor': 0.0, 'max_dd': 0.0, 'trades_df': pd.DataFrame()}

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
        'symbol': name,
        'type': 'INDEX_OPTION',
        'trades': len(df_res),
        'wins': len(wins),
        'losses': len(losses),
        'win_rate': win_rate,
        'pnl': total_pnl,
        'gross_profit': gross_profit,
        'gross_loss': gross_loss,
        'profit_factor': profit_factor,
        'max_dd': max_dd,
        'trades_df': df_res
    }

def run_stock_backtest(symbol, df_merged, trade_capital=200000.0, sl_pct=0.80, tp_pct=1.60, t1_pct=0.50, l1_pct=0.10, t2_pct=1.00, l2_pct=0.60, max_vwap_dist_pct=0.60, adx_min=22.0):
    trades = []
    current_trade = None
    trades_today = 0
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
            if current_trade:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['exit_reason'] = "DAY_END"
                trades.append(current_trade)
                current_trade = None

        if current_trade is not None:
            entry_spot = current_trade['entry_spot']
            direction = current_trade['direction']
            qty = current_trade['qty']

            if direction == 'LONG':
                pnl_pct = ((spot - entry_spot) / entry_spot) * 100.0
            else:
                pnl_pct = ((entry_spot - spot) / entry_spot) * 100.0

            if pnl_pct > current_trade['max_fav_pct']:
                current_trade['max_fav_pct'] = pnl_pct

            effective_sl_pct = -sl_pct
            if current_trade['max_fav_pct'] >= t2_pct:
                effective_sl_pct = l2_pct
            elif current_trade['max_fav_pct'] >= t1_pct:
                effective_sl_pct = l1_pct

            if pnl_pct <= effective_sl_pct:
                exit_reason = "TRAIL_SL_LOCK" if effective_sl_pct > -sl_pct else "STOP_LOSS"
                realized_pnl = (effective_sl_pct / 100.0) * (entry_spot * qty)
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = realized_pnl
                current_trade['exit_reason'] = exit_reason
                trades.append(current_trade)
                current_trade = None
                continue

            if pnl_pct >= tp_pct:
                realized_pnl = (tp_pct / 100.0) * (entry_spot * qty)
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = realized_pnl
                current_trade['exit_reason'] = "TARGET_PROFIT"
                trades.append(current_trade)
                current_trade = None
                continue

            if (direction == 'LONG' and st_dir == -1) or (direction == 'SHORT' and st_dir == 1):
                realized_pnl = (pnl_pct / 100.0) * (entry_spot * qty)
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = realized_pnl
                current_trade['exit_reason'] = "ST_FLIP"
                trades.append(current_trade)
                current_trade = None
                continue

            if t >= datetime.time(15, 5):
                realized_pnl = (pnl_pct / 100.0) * (entry_spot * qty)
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['pnl'] = realized_pnl
                current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                trades.append(current_trade)
                current_trade = None
                continue

        if current_trade is None and trades_today < 1:
            if start_t <= t <= end_t:
                bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)
                vwap_dist_pct = (abs(spot - vwap) / spot) * 100.0

                if bullish_cross:
                    if spot <= vwap or vwap_dist_pct > max_vwap_dist_pct or st_dir != 1 or adx < adx_min or np.isnan(adx) or p_di <= m_di:
                        continue
                    qty = max(1, int(trade_capital / spot))
                    current_trade = {
                        'symbol': symbol,
                        'type': 'EQUITY',
                        'entry_time': timestamp,
                        'direction': 'LONG',
                        'entry_spot': spot,
                        'qty': qty,
                        'vwap_at_entry': vwap,
                        'adx_at_entry': adx,
                        'max_fav_pct': 0.0,
                        'pnl': 0.0,
                        'exit_time': None,
                        'exit_spot': None,
                        'exit_reason': None
                    }
                    trades_today += 1

                elif bearish_cross:
                    if spot >= vwap or vwap_dist_pct > max_vwap_dist_pct or st_dir != -1 or adx < adx_min or np.isnan(adx) or m_di <= p_di:
                        continue
                    qty = max(1, int(trade_capital / spot))
                    current_trade = {
                        'symbol': symbol,
                        'type': 'EQUITY',
                        'entry_time': timestamp,
                        'direction': 'SHORT',
                        'entry_spot': spot,
                        'qty': qty,
                        'vwap_at_entry': vwap,
                        'adx_at_entry': adx,
                        'max_fav_pct': 0.0,
                        'pnl': 0.0,
                        'exit_time': None,
                        'exit_spot': None,
                        'exit_reason': None
                    }
                    trades_today += 1

    df_res = pd.DataFrame(trades)
    if df_res.empty:
        return {'symbol': symbol, 'trades': 0, 'wins': 0, 'losses': 0, 'win_rate': 0.0, 'pnl': 0.0, 'gross_profit': 0.0, 'gross_loss': 0.0, 'profit_factor': 0.0, 'max_dd': 0.0, 'trades_df': pd.DataFrame()}

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
        'symbol': symbol,
        'type': 'EQUITY',
        'trades': len(df_res),
        'wins': len(wins),
        'losses': len(losses),
        'win_rate': win_rate,
        'pnl': total_pnl,
        'gross_profit': gross_profit,
        'gross_loss': gross_loss,
        'profit_factor': profit_factor,
        'max_dd': max_dd,
        'trades_df': df_res
    }

def main():
    print("=" * 115)
    print("UNIFIED MULTI-ASSET BACKTEST: 3 INDICES + 9 CHAMPION STOCKS (LAST 60 DAYS)")
    print("Strategy: 5m/15m RSI Crossover + 15m Supertrend(10,2) + VWAP Gate + ADX + DI + Stepped Trailing SL")
    print("=" * 115 + "\n")

    all_results = []
    all_trade_dfs = []

    # 1. Backtest NIFTY 50 (10 lots = 650 qty)
    print("1/12 Processing NIFTY 50 (^NSEI)...")
    df_nifty = prepare_data('^NSEI')
    r_nifty = run_index_backtest(
        name='NIFTY 50',
        df_merged=df_nifty,
        lot_size=65,
        num_lots=10,
        sl_pts=30.0,
        tp_pts=50.0,
        t1=12.0, l1=2.0,
        t2=25.0, l2=15.0,
        max_vwap_dist=35.0,
        adx_min=22.0,
        theta_decay_hr=2.5
    )
    all_results.append(r_nifty)
    all_trade_dfs.append(r_nifty['trades_df'])

    # 2. Backtest BSE SENSEX (10 lots = 200 qty)
    print("2/12 Processing BSE SENSEX (^BSESN)...")
    df_sensex = prepare_data('^BSESN')
    r_sensex = run_index_backtest(
        name='BSE SENSEX',
        df_merged=df_sensex,
        lot_size=20,
        num_lots=10,
        sl_pts=80.0,
        tp_pts=220.0,
        t1=35.0, l1=6.0,
        t2=75.0, l2=45.0,
        max_vwap_dist=150.0,
        adx_min=25.0,
        theta_decay_hr=8.0
    )
    all_results.append(r_sensex)
    all_trade_dfs.append(r_sensex['trades_df'])

    # 3. Backtest BANK NIFTY (10 lots = 300 qty)
    print("3/12 Processing BANK NIFTY (^NSEBANK)...")
    df_bank = prepare_data('^NSEBANK')
    r_bank = run_index_backtest(
        name='BANK NIFTY',
        df_merged=df_bank,
        lot_size=30,
        num_lots=10,
        sl_pts=50.0,
        tp_pts=100.0,
        t1=25.0, l1=5.0,
        t2=55.0, l2=30.0,
        max_vwap_dist=80.0,
        adx_min=22.0,
        theta_decay_hr=5.5
    )
    all_results.append(r_bank)
    all_trade_dfs.append(r_bank['trades_df'])

    # 4. Backtest 9 Champion Stocks
    stocks = [
        ('BSE', 'BSE.NS'),
        ('LAURUSLABS', 'LAURUSLABS.NS'),
        ('SAIL', 'SAIL.NS'),
        ('POLYCAB', 'POLYCAB.NS'),
        ('ADANIENSOL', 'ADANIENSOL.NS'),
        ('MCX', 'MCX.NS'),
        ('TORNTPHARM', 'TORNTPHARM.NS'),
        ('BHEL', 'BHEL.NS'),
        ('HINDALCO', 'HINDALCO.NS')
    ]

    for idx, (s_name, s_ticker) in enumerate(stocks, 4):
        print(f"{idx}/12 Processing Stock {s_name} ({s_ticker})...")
        df_stock = prepare_data(s_ticker)
        r_stock = run_stock_backtest(
            symbol=s_name,
            df_merged=df_stock,
            trade_capital=200000.0,
            sl_pct=0.80,
            tp_pct=1.60,
            t1_pct=0.50, l1_pct=0.10,
            t2_pct=1.00, l2_pct=0.60,
            max_vwap_dist_pct=0.60,
            adx_min=22.0
        )
        all_results.append(r_stock)
        all_trade_dfs.append(r_stock['trades_df'])

    print("\n" + "=" * 115)
    print(f"{'#':<3} | {'Instrument':<14} | {'Asset Class':<14} | {'Trades':<6} | {'Wins':<5} | {'Loss':<5} | {'Win %':<6} | {'Net PnL (Rs)':<14} | {'PF':<5} | {'Max DD (Rs)':<12}")
    print("-" * 115)

    idx_trades, idx_wins, idx_losses, idx_pnl, idx_gp, idx_gl = 0, 0, 0, 0.0, 0.0, 0.0
    stk_trades, stk_wins, stk_losses, stk_pnl, stk_gp, stk_gl = 0, 0, 0, 0.0, 0.0, 0.0

    for i, r in enumerate(all_results, 1):
        pf_str = f"{r['profit_factor']:.2f}" if r['profit_factor'] < 100 else "Inf"
        is_index = r['type'] == 'INDEX_OPTION'
        asset_type = "Index (10 Lots)" if is_index else "Equity (Rs 2L)"

        if is_index:
            idx_trades += r['trades']
            idx_wins += r['wins']
            idx_losses += r['losses']
            idx_pnl += r['pnl']
            idx_gp += r['gross_profit']
            idx_gl += r['gross_loss']
        else:
            stk_trades += r['trades']
            stk_wins += r['wins']
            stk_losses += r['losses']
            stk_pnl += r['pnl']
            stk_gp += r['gross_profit']
            stk_gl += r['gross_loss']

        print(f"{i:<3} | {r['symbol']:<14} | {asset_type:<14} | {r['trades']:<6} | {r['wins']:<5} | {r['losses']:<5} | {r['win_rate']:<5.1f}% | Rs {r['pnl']:+11,.0f} | {pf_str:<5} | Rs {r['max_dd']:+10,.0f}")

    idx_wr = (idx_wins / idx_trades * 100.0) if idx_trades > 0 else 0.0
    idx_pf = (idx_gp / idx_gl) if idx_gl > 0 else float('inf')

    stk_wr = (stk_wins / stk_trades * 100.0) if stk_trades > 0 else 0.0
    stk_pf = (stk_gp / stk_gl) if stk_gl > 0 else float('inf')

    tot_trades = idx_trades + stk_trades
    tot_wins = idx_wins + stk_wins
    tot_losses = idx_losses + stk_losses
    tot_pnl = idx_pnl + stk_pnl
    tot_gp = idx_gp + stk_gp
    tot_gl = idx_gl + stk_gl
    tot_wr = (tot_wins / tot_trades * 100.0) if tot_trades > 0 else 0.0
    tot_pf = (tot_gp / tot_gl) if tot_gl > 0 else float('inf')

    print("-" * 115)
    print(f"SUBTOTAL - 3 INDICES (Nifty, Sensex, BankNifty) : {idx_trades:3d} Trades | {idx_wr:5.1f}% Win | Net PnL: Rs {idx_pnl:+12,.2f} | PF: {idx_pf:.2f}")
    print(f"SUBTOTAL - 9 STOCKS (BSE, Laurus, SAIL, Polycab...): {stk_trades:3d} Trades | {stk_wr:5.1f}% Win | Net PnL: Rs {stk_pnl:+12,.2f} | PF: {stk_pf:.2f}")
    print("=" * 115)
    print(f"GRAND TOTAL COMBINED PORTFOLIO (12 INSTRUMENTS): {tot_trades:3d} Trades | {tot_wr:5.1f}% Win | Net PnL: Rs {tot_pnl:+12,.2f} | PF: {tot_pf:.2f}")
    print("=" * 115 + "\n")

    # Combined Portfolio Equity Curve & Maximum Drawdown
    df_combined_trades = pd.concat(all_trade_dfs, ignore_index=True)
    df_combined_trades = df_combined_trades.sort_values(by='entry_time').reset_index(drop=True)
    df_combined_trades['cum_pnl'] = df_combined_trades['pnl'].cumsum()
    df_combined_trades['peak'] = df_combined_trades['cum_pnl'].cummax()
    df_combined_trades['drawdown'] = df_combined_trades['cum_pnl'] - df_combined_trades['peak']
    portfolio_max_dd = df_combined_trades['drawdown'].min()

    print(f"COMBINED PORTFOLIO MAXIMUM DRAWDOWN: Rs {portfolio_max_dd:+12,.2f}")
    print(f"PORTFOLIO PROFIT-TO-DRAWDOWN RATIO: {abs(tot_pnl / portfolio_max_dd):.2f}x\n")

    # Monthly breakdown across all 12 instruments
    df_combined_trades['Month'] = df_combined_trades['entry_time'].apply(lambda x: x.strftime('%B %Y'))
    print("=" * 80)
    print("MONTH-BY-MONTH UNIFIED PORTFOLIO PERFORMANCE")
    print("=" * 80)
    for m, g in df_combined_trades.groupby('Month', sort=False):
        wins = g[g['pnl'] > 0]
        wr = (len(wins) / len(g) * 100.0) if len(g) > 0 else 0.0
        pnl = g['pnl'].sum()
        idx_p = g[g['type'] == 'INDEX_OPTION']['pnl'].sum()
        stk_p = g[g['type'] == 'EQUITY']['pnl'].sum()
        print(f"{m:<16}: {len(g):3d} trades | Win Rate: {wr:5.1f}% | Net PnL: Rs {pnl:+11,.2f} (Indices: Rs {idx_p:+9,.0f} | Stocks: Rs {stk_p:+8,.0f})")
    print("=" * 80 + "\n")

if __name__ == '__main__':
    main()
