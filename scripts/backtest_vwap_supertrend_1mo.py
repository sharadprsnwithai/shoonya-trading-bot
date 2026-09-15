import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# ==============================================================================
# Helper Functions for Indicators
# ==============================================================================

def calculate_supertrend(df, period=10, multiplier=2.0):
    """
    Computes ATR and SuperTrend (period, multiplier).
    Returns Series for supertrend and direction (1 for Bullish, -1 for Bearish).
    """
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

    # Wilder's smoothing for ATR
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
        # Lower band logic
        if lower_basic[i] > lower_band[i - 1] or close[i - 1] < lower_band[i - 1]:
            lower_band[i] = lower_basic[i]
        else:
            lower_band[i] = lower_band[i - 1]

        # Upper band logic
        if upper_basic[i] < upper_band[i - 1] or close[i - 1] > upper_band[i - 1]:
            upper_band[i] = upper_basic[i]
        else:
            upper_band[i] = upper_band[i - 1]

        # Direction logic
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
    """
    Calculates ADX(14), +DI(14), -DI(14) using Welles Wilder's method.
    """
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

    # Wilder's Smoothing
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
    """
    Calculates Intraday VWAP that strictly resets every day at 09:15 AM IST.
    """
    df = df.copy()
    typical_price = (df['High'] + df['Low'] + df['Close']) / 3.0
    vol = df['Volume'].replace(0, 1) # avoid zero vol
    tp_vol = typical_price * vol

    df['DateOnly'] = df.index.tz_convert('Asia/Kolkata').date

    df['Cum_TP_Vol'] = tp_vol.groupby(df['DateOnly']).cumsum()
    df['Cum_Vol'] = vol.groupby(df['DateOnly']).cumsum()
    df['VWAP'] = df['Cum_TP_Vol'] / df['Cum_Vol']
    return df

# ==============================================================================
# Main Backtest Function
# ==============================================================================

def run_vwap_supertrend_backtest():
    print("================================================================================")
    print("NIFTY 50 INTRADAY 15m VWAP + SUPERTREND (10, 2) + ADX(14) >= 20 BACKTEST")
    print("================================================================================")

    # 1. Fetch 5m candles for the last 1-2 months
    ticker = "^NSEI"
    print(f"Fetching 60-day 5-minute data for {ticker} from Yahoo Finance...")
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)

    if df_5m.empty:
        print("No data received from Yahoo Finance.")
        return

    # Flatten MultiIndex columns if present
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    # Convert to IST timezone
    if df_5m.index.tz is None:
        df_5m.index = df_5m.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
    else:
        df_5m.index = df_5m.index.tz_convert('Asia/Kolkata')

    # Filter market hours 09:15 to 15:30 IST
    df_5m = df_5m.between_time('09:15', '15:30')
    df_5m = calculate_intraday_vwap(df_5m)

    # 2. Resample to 15m for Macro Direction & Indicators
    df_15m = df_5m.resample('15min', closed='left', label='left').agg({
        'Open': 'first',
        'High': 'max',
        'Low': 'min',
        'Close': 'last',
        'Volume': 'sum'
    }).dropna()

    # Calculate 15m Supertrend (10, 2) & ADX(14)
    df_15m = calculate_supertrend(df_15m, period=10, multiplier=2.0)
    df_15m = calculate_adx(df_15m, period=14)

    # Forward-fill 15m indicator columns onto 5m dataframe so each 5m candle knows latest completed 15m state
    df_5m['Time_15m'] = df_5m.index.floor('15min')
    
    # Merge 15m indicators
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI']
    df_15m_subset = df_15m[cols_15m].shift(1) # Use completed 15m bar
    
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX'])

    print(f"Processed {len(df_merged)} 5m bars across {len(np.unique(df_merged['DateOnly']))} trading days.")

    # ==============================================================================
    # Simulation Execution (Option Selling with Hedged Credit Spread)
    # 1 Lot = 65 Qty, Approx Net Credit ~100 pts (Selling ATM PE/CE + Buying 2% OTM Hedge)
    # SL = 30% of Net Credit (30 pts), Target = 60% of Net Credit (60 pts)
    # Trend Reversal = Supertrend or VWAP cross
    # ==============================================================================

    LOT_SIZE = 65
    NUM_LOTS = 1
    TOTAL_QTY = LOT_SIZE * NUM_LOTS
    MAX_TRADES_PER_DAY = 2
    ADX_THRESHOLD = 20.0
    STOP_LOSS_PTS = 35.0 # Max SL on option points
    TARGET_PTS = 60.0    # Target profit on option points

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

        if day != current_day:
            current_day = day
            trades_today = 0
            # Force close any overnight position (intraday only)
            if current_trade:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['exit_reason'] = "DAY_END"
                trades.append(current_trade)
                current_trade = None

        # 1. Manage Open Position
        if current_trade is not None:
            entry_spot = current_trade['entry_spot']
            direction = current_trade['direction'] # 'BULLISH' or 'BEARISH'
            
            # Approximate option points gained (selling option = points move in favor + theta)
            if direction == 'BULLISH':
                # Bullish: Spot going up is positive
                spot_move = spot - entry_spot
                opt_pnl_pts = spot_move * 0.50 # Delta ~0.50
            else:
                # Bearish: Spot going down is positive
                spot_move = entry_spot - spot
                opt_pnl_pts = spot_move * 0.50

            # Check SL
            if opt_pnl_pts <= -STOP_LOSS_PTS:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = -STOP_LOSS_PTS
                current_trade['pnl'] = -STOP_LOSS_PTS * TOTAL_QTY
                current_trade['exit_reason'] = "STOP_LOSS"
                trades.append(current_trade)
                current_trade = None
                continue

            # Check Target
            elif opt_pnl_pts >= TARGET_PTS:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = TARGET_PTS
                current_trade['pnl'] = TARGET_PTS * TOTAL_QTY
                current_trade['exit_reason'] = "TARGET_PROFIT"
                trades.append(current_trade)
                current_trade = None
                continue

            # Check Trend Invalidation (Exit on Supertrend Flip OR VWAP Reversal)
            reversal = False
            if direction == 'BULLISH' and (st_dir == -1 or spot < vwap):
                reversal = True
            elif direction == 'BEARISH' and (st_dir == 1 or spot > vwap):
                reversal = True

            if reversal:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * TOTAL_QTY
                current_trade['exit_reason'] = "TREND_REVERSAL"
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

        # 2. Check New Entry (Between 09:30 and 14:45 IST)
        if current_trade is None and trades_today < MAX_TRADES_PER_DAY:
            if datetime.time(9, 30) <= t <= datetime.time(14, 45):
                # Filter 1: ADX Momentum Filter
                if adx >= ADX_THRESHOLD:
                    # Bullish Condition: Spot > VWAP and 15m ST is Green and +DI > -DI
                    if spot > vwap and st_dir == 1 and p_di > m_di:
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

                    # Bearish Condition: Spot < VWAP and 15m ST is Red and -DI > +DI
                    elif spot < vwap and st_dir == -1 and m_di > p_di:
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

    # ==============================================================================
    # Performance Reporting
    # ==============================================================================
    if not trades:
        print("No trades triggered with the selected parameters.")
        return

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

    print("\n================================================================================")
    print("1-MONTH NIFTY 15m VWAP + SUPERTREND (10, 2) + ADX(>=20) PERFORMANCE SUMMARY")
    print("================================================================================")
    print(f"Total Trades Taken   : {len(df_res)}")
    print(f"Winning Trades       : {len(wins)} ({win_rate:.1f}%)")
    print(f"Losing Trades        : {len(losses)} ({100.0 - win_rate:.1f}%)")
    print(f"Gross Profit         : +Rs {gross_profit:,.2f}")
    print(f"Gross Loss           : -Rs {gross_loss:,.2f}")
    print(f"Net Realized PnL     : +Rs {total_pnl:,.2f}" if total_pnl >= 0 else f"Net Realized PnL     : -Rs {abs(total_pnl):,.2f}")
    print(f"Profit Factor        : {profit_factor:.2f}")
    print(f"Max Drawdown         : Rs {max_dd:,.2f}")
    print("================================================================================")

    print("\nRECENT TRADES BREAKDOWN:")
    for idx, row in df_res.tail(15).iterrows():
        pnl_str = f"+Rs {row['pnl']:,.2f}" if row['pnl'] >= 0 else f"-Rs {abs(row['pnl']):,.2f}"
        print(f"  {row['entry_time'].strftime('%d-%b %H:%M')} -> {row['direction']:<7} | Spot: {row['entry_spot']:.1f} (VWAP: {row['vwap_at_entry']:.1f}, ADX: {row['adx_at_entry']:.1f}) | Exit: {row['exit_time'].strftime('%H:%M')} ({row['exit_reason']:<15}) | PnL: {pnl_str}")
    print("================================================================================\n")

if __name__ == '__main__':
    run_vwap_supertrend_backtest()
