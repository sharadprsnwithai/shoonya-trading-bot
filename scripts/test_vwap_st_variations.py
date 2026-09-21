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
    df['ATR'] = atr
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

    df['DateOnly'] = df.index.tz_convert('Asia/Kolkata').date

    df['Cum_TP_Vol'] = tp_vol.groupby(df['DateOnly']).cumsum()
    df['Cum_Vol'] = vol.groupby(df['DateOnly']).cumsum()
    df['VWAP'] = df['Cum_TP_Vol'] / df['Cum_Vol']
    return df

def backtest_engine(df_merged, max_trades_per_day=1, st_multiplier=2.0, adx_thresh=20.0, sl_pts=35.0, tp_pts=50.0, exit_on_st_flip=True, exit_on_vwap=False):
    LOT_SIZE = 65
    NUM_LOTS = 1
    TOTAL_QTY = LOT_SIZE * NUM_LOTS

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

    for i in range(1, len(df_merged)):
        day = dates[i]
        t = times[i]
        spot = closes[i]
        vwap = vwaps[i]
        st_dir = st_dirs[i]
        adx = adxs[i]
        p_di = p_dis[i]
        m_di = m_dis[i]
        timestamp = df_merged.index[i]

        prev_spot = closes[i - 1]
        prev_vwap = vwaps[i - 1]
        prev_st_dir = st_dirs[i - 1]

        if day != current_day:
            current_day = day
            trades_today = 0
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
            
            # Option Selling: Option moves in favor + theta decay (+3 pts/hr theta)
            # Delta = 0.50
            hours_held = (timestamp - current_trade['entry_time']).total_seconds() / 3600.0
            theta_pts = hours_held * 2.5 # ~2.5 pts theta decay per hour

            if direction == 'BULLISH':
                spot_move = spot - entry_spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts
            else:
                spot_move = entry_spot - spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts

            # Hard SL
            if opt_pnl_pts <= -sl_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = -sl_pts
                current_trade['pnl'] = -sl_pts * TOTAL_QTY
                current_trade['exit_reason'] = "STOP_LOSS"
                trades.append(current_trade)
                current_trade = None
                continue

            # Target Profit
            elif opt_pnl_pts >= tp_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = tp_pts
                current_trade['pnl'] = tp_pts * TOTAL_QTY
                current_trade['exit_reason'] = "TARGET_PROFIT"
                trades.append(current_trade)
                current_trade = None
                continue

            # Exit on 15m Supertrend Flip
            if exit_on_st_flip:
                if (direction == 'BULLISH' and st_dir == -1) or (direction == 'BEARISH' and st_dir == 1):
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['opt_pts'] = opt_pnl_pts
                    current_trade['pnl'] = opt_pnl_pts * TOTAL_QTY
                    current_trade['exit_reason'] = "ST_FLIP"
                    trades.append(current_trade)
                    current_trade = None
                    continue

            # Exit on 15m VWAP Reversal (Optional)
            if exit_on_vwap:
                if (direction == 'BULLISH' and spot < vwap) or (direction == 'BEARISH' and spot > vwap):
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['opt_pts'] = opt_pnl_pts
                    current_trade['pnl'] = opt_pnl_pts * TOTAL_QTY
                    current_trade['exit_reason'] = "VWAP_CROSS"
                    trades.append(current_trade)
                    current_trade = None
                    continue

            # Mandatory EOD Square-Off at 15:05 IST
            if t >= datetime.time(15, 5):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * TOTAL_QTY
                current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                trades.append(current_trade)
                current_trade = None
                continue

        # 2. Check New Entry (Between 09:30 and 14:30 IST)
        if current_trade is None and trades_today < max_trades_per_day:
            if datetime.time(9, 30) <= t <= datetime.time(14, 30):
                if adx >= adx_thresh:
                    # Bullish Entry Trigger: Spot above VWAP and ST is Green and +DI > -DI
                    # Triggered when either Spot just crossed VWAP OR ST just turned Green
                    bull_cross = (prev_spot <= prev_vwap and spot > vwap) or (prev_st_dir == -1 and st_dir == 1) or (spot > vwap and st_dir == 1 and p_di > m_di and trades_today == 0)

                    if bull_cross and spot > vwap and st_dir == 1 and p_di > m_di:
                        current_trade = {
                            'trade_id': f"TRD_{len(trades)+1}",
                            'entry_time': timestamp,
                            'direction': 'BULLISH',
                            'structure': 'SELL_ATM_PE_SPREAD',
                            'entry_spot': spot,
                            'vwap_at_entry': vwap,
                            'adx_at_entry': adx,
                            'opt_pts': 0.0,
                            'pnl': 0.0,
                            'exit_time': None,
                            'exit_spot': None,
                            'exit_reason': None
                        }
                        trades_today += 1

                    # Bearish Entry Trigger: Spot below VWAP and ST is Red and -DI > +DI
                    bear_cross = (prev_spot >= prev_vwap and spot < vwap) or (prev_st_dir == 1 and st_dir == -1) or (spot < vwap and st_dir == -1 and m_di > p_di and trades_today == 0)

                    if bear_cross and spot < vwap and st_dir == -1 and m_di > p_di:
                        current_trade = {
                            'trade_id': f"TRD_{len(trades)+1}",
                            'entry_time': timestamp,
                            'direction': 'BEARISH',
                            'structure': 'SELL_ATM_CE_SPREAD',
                            'entry_spot': spot,
                            'vwap_at_entry': vwap,
                            'adx_at_entry': adx,
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
    ticker = "^NSEI"
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.index.tz is None:
        df_5m.index = df_5m.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
    else:
        df_5m.index = df_5m.index.tz_convert('Asia/Kolkata')

    df_5m = df_5m.between_time('09:15', '15:30')
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

    df_5m['Time_15m'] = df_5m.index.floor('15min')
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI']
    df_15m_subset = df_15m[cols_15m].shift(1)
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX'])

    print("================================================================================")
    print("GRID SEARCH & TUNING: 15m VWAP + SUPERTREND + ADX INTRADAY OPTION SPREAD")
    print("================================================================================")

    configs = [
        # (name, max_trades, st_mult, adx_thresh, sl_pts, tp_pts, exit_st_flip, exit_vwap)
        ("Base (Exit on ST Flip only, Max 1 Trade)", 1, 2.0, 20.0, 30.0, 50.0, True, False),
        ("Higher ADX Threshold >= 25 (Stronger Trends)", 1, 2.0, 25.0, 30.0, 50.0, True, False),
        ("Wider SL 40 pts, TP 50 pts, ADX >= 20", 1, 2.0, 20.0, 40.0, 50.0, True, False),
        ("SuperTrend (10, 3.0) + ADX >= 20", 1, 3.0, 20.0, 30.0, 50.0, True, False),
        ("Strict VWAP + ST Flip Exit", 1, 2.0, 20.0, 30.0, 50.0, True, True),
        ("Max 2 Trades Per Day, ADX >= 20", 2, 2.0, 20.0, 30.0, 50.0, True, False),
    ]

    for name, mt, mult, adx_t, sl, tp, st_flip, vwap_ex in configs:
        res = backtest_engine(df_merged, max_trades_per_day=mt, st_multiplier=mult, adx_thresh=adx_t, sl_pts=sl, tp_pts=tp, exit_on_st_flip=st_flip, exit_on_vwap=vwap_ex)
        pnl_str = f"+Rs {res['pnl']:,.2f}" if res['pnl'] >= 0 else f"-Rs {abs(res['pnl']):,.2f}"
        print(f"\n>> {name}")
        print(f"   Trades: {res['trades']} | Win Rate: {res['win_rate']:.1f}% | Net PnL: {pnl_str} | PF: {res['profit_factor']:.2f} | Max DD: Rs {res['max_dd']:,.2f}")

    # Print top configuration trade log
    best_res = backtest_engine(df_merged, max_trades_per_day=1, st_multiplier=2.0, adx_thresh=20.0, sl_pts=30.0, tp_pts=50.0, exit_on_st_flip=True, exit_on_vwap=False)
    print("\n================================================================================")
    print("DETAILED TRADE LOG FOR BEST CONFIGURATION (1-Month Recent):")
    print("================================================================================")
    for idx, row in best_res['trades_df'].tail(20).iterrows():
        pnl_str = f"+Rs {row['pnl']:,.2f}" if row['pnl'] >= 0 else f"-Rs {abs(row['pnl']):,.2f}"
        print(f"  {row['entry_time'].strftime('%d-%b %H:%M')} -> {row['direction']:<7} | Spot: {row['entry_spot']:.1f} (VWAP: {row['vwap_at_entry']:.1f}) | Exit: {row['exit_time'].strftime('%H:%M')} ({row['exit_reason']:<14}) | PnL: {pnl_str}")

if __name__ == '__main__':
    main()
