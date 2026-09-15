import datetime
import numpy as np
import pandas as pd
import yfinance as yf
import concurrent.futures

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

def backtest_single_stock(
    symbol,
    ticker,
    trade_capital=200000.0,         # Rs 2,00,000 deployed capital per trade
    sl_pct=0.80,                    # 0.80% Stop-loss
    tp_pct=1.60,                    # 1.60% Target Profit (2:1 RR)
    trail_trigger1_pct=0.50,        # Step 1: Move SL to +0.10% after +0.50% gain
    trail_lock1_pct=0.10,           # Step 1 Lock
    trail_trigger2_pct=1.00,        # Step 2: Move SL to +0.60% after +1.00% gain
    trail_lock2_pct=0.60,           # Step 2 Lock
    max_vwap_dist_pct=0.60,         # VWAP proximity gate (<= 0.60% from VWAP)
    adx_min=22.0,                   # ADX minimum
    max_trades_per_day=1
):
    try:
        df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
        if isinstance(df_5m.columns, pd.MultiIndex):
            df_5m.columns = df_5m.columns.get_level_values(0)

        if df_5m.empty or len(df_5m) < 100:
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

        if len(df_merged) < 50:
            return None

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

            # 1. Manage Open Position
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
                if current_trade['max_fav_pct'] >= trail_trigger2_pct:
                    effective_sl_pct = trail_lock2_pct
                elif current_trade['max_fav_pct'] >= trail_trigger1_pct:
                    effective_sl_pct = trail_lock1_pct

                # SL / Trailing SL Exit
                if pnl_pct <= effective_sl_pct:
                    exit_reason = "TRAIL_SL_LOCK" if effective_sl_pct > -sl_pct else "STOP_LOSS"
                    realized_pnl = (effective_sl_pct / 100.0) * (entry_spot * qty)
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['pnl_pct'] = effective_sl_pct
                    current_trade['pnl'] = realized_pnl
                    current_trade['exit_reason'] = exit_reason
                    trades.append(current_trade)
                    current_trade = None
                    continue

                # Target Profit Exit
                if pnl_pct >= tp_pct:
                    realized_pnl = (tp_pct / 100.0) * (entry_spot * qty)
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['pnl_pct'] = tp_pct
                    current_trade['pnl'] = realized_pnl
                    current_trade['exit_reason'] = "TARGET_PROFIT"
                    trades.append(current_trade)
                    current_trade = None
                    continue

                # SuperTrend Reversal Flip Exit
                if (direction == 'LONG' and st_dir == -1) or (direction == 'SHORT' and st_dir == 1):
                    realized_pnl = (pnl_pct / 100.0) * (entry_spot * qty)
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['pnl_pct'] = pnl_pct
                    current_trade['pnl'] = realized_pnl
                    current_trade['exit_reason'] = "ST_FLIP"
                    trades.append(current_trade)
                    current_trade = None
                    continue

                # Mandatory 15:05 EOD Square-off
                if t >= datetime.time(15, 5):
                    realized_pnl = (pnl_pct / 100.0) * (entry_spot * qty)
                    current_trade['exit_time'] = timestamp
                    current_trade['exit_spot'] = spot
                    current_trade['pnl_pct'] = pnl_pct
                    current_trade['pnl'] = realized_pnl
                    current_trade['exit_reason'] = "EOD_SQUARE_OFF"
                    trades.append(current_trade)
                    current_trade = None
                    continue

            # 2. Check New Entry
            if current_trade is None and trades_today < max_trades_per_day:
                if start_t <= t <= end_t:
                    bullish_cross = (prev_rsi_5 <= prev_rsi_15) and (rsi_5 > rsi_15)
                    bearish_cross = (prev_rsi_5 >= prev_rsi_15) and (rsi_5 < rsi_15)
                    vwap_dist_pct = (abs(spot - vwap) / spot) * 100.0

                    if bullish_cross:
                        if spot <= vwap:
                            continue
                        if vwap_dist_pct > max_vwap_dist_pct:
                            continue
                        if st_dir != 1:
                            continue
                        if adx < adx_min or np.isnan(adx):
                            continue
                        if p_di <= m_di:
                            continue

                        qty = max(1, int(trade_capital / spot))
                        current_trade = {
                            'symbol': symbol,
                            'entry_time': timestamp,
                            'direction': 'LONG',
                            'entry_spot': spot,
                            'qty': qty,
                            'vwap_at_entry': vwap,
                            'adx_at_entry': adx,
                            'max_fav_pct': 0.0,
                            'pnl_pct': 0.0,
                            'pnl': 0.0,
                            'exit_time': None,
                            'exit_spot': None,
                            'exit_reason': None
                        }
                        trades_today += 1

                    elif bearish_cross:
                        if spot >= vwap:
                            continue
                        if vwap_dist_pct > max_vwap_dist_pct:
                            continue
                        if st_dir != -1:
                            continue
                        if adx < adx_min or np.isnan(adx):
                            continue
                        if m_di <= p_di:
                            continue

                        qty = max(1, int(trade_capital / spot))
                        current_trade = {
                            'symbol': symbol,
                            'entry_time': timestamp,
                            'direction': 'SHORT',
                            'entry_spot': spot,
                            'qty': qty,
                            'vwap_at_entry': vwap,
                            'adx_at_entry': adx,
                            'max_fav_pct': 0.0,
                            'pnl_pct': 0.0,
                            'pnl': 0.0,
                            'exit_time': None,
                            'exit_spot': None,
                            'exit_reason': None
                        }
                        trades_today += 1

        if not trades:
            return {
                'symbol': symbol,
                'trades': 0,
                'win_rate': 0.0,
                'pnl': 0.0,
                'profit_factor': 0.0,
                'max_dd': 0.0,
                'avg_price': closes[-1],
                'trades_df': pd.DataFrame()
            }

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
            'symbol': symbol,
            'trades': len(df_res),
            'wins': len(wins),
            'losses': len(losses),
            'win_rate': win_rate,
            'pnl': total_pnl,
            'gross_profit': gross_profit,
            'gross_loss': gross_loss,
            'profit_factor': profit_factor,
            'max_dd': max_dd,
            'avg_price': closes[-1],
            'trades_df': df_res
        }
    except Exception as e:
        print(f"Error backtesting {symbol}: {e}")
        return None

def main():
    symbols_map = {
        'ABB': 'ABB.NS',
        'ADANIENSOL': 'ADANIENSOL.NS',
        'ADANIGREEN': 'ADANIGREEN.NS',
        'ADANIPOWER': 'ADANIPOWER.NS',
        'ABCAPITAL': 'ABCAPITAL.NS',
        'BSE': 'BSE.NS',
        'BHARATFORG': 'BHARATFORG.NS',
        'BHEL': 'BHEL.NS',
        'CGPOWER': 'CGPOWER.NS',
        'CUMMINSIND': 'CUMMINSIND.NS',
        'FEDERALBNK': 'FEDERALBNK.NS',
        'GVT&D': 'GVT&D.NS',
        'GLENMARK': 'GLENMARK.NS',
        'HINDALCO': 'HINDALCO.NS',
        'POWERINDIA': 'POWERINDIA.NS',
        'KEI': 'KEI.NS',
        'LTF': 'LTF.NS',
        'LAURUSLABS': 'LAURUSLABS.NS',
        'MCX': 'MCX.NS',
        'NTPC': 'NTPC.NS',
        'NATIONALUM': 'NATIONALUM.NS',
        'POLYCAB': 'POLYCAB.NS',
        'MOTHERSON': 'MOTHERSON.NS',
        'SHRIRAMFIN': 'SHRIRAMFIN.NS',
        'SOLARINDS': 'SOLARINDS.NS',
        'SAIL': 'SAIL.NS',
        'TATASTEEL': 'TATASTEEL.NS',
        'TORNTPHARM': 'TORNTPHARM.NS',
        'VEDL': 'VEDL.NS',
        'IDEA': 'IDEA.NS'
    }

    print("="*115)
    print(f"BACKTESTING INTRADAY RSI CROSSOVER CHAMPION STRATEGY ON {len(symbols_map)} STOCKS (LAST 60 DAYS)")
    print("Capital Deployed: Rs 2,00,000 per trade | SL: 0.80% | TP: 1.60% | Trail: +0.50%->+0.10%, +1.00%->+0.60%")
    print("="*115 + "\n")

    results = []
    all_trades = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        future_to_stock = {
            executor.submit(backtest_single_stock, sym, tick): sym
            for sym, tick in symbols_map.items()
        }
        for future in concurrent.futures.as_completed(future_to_stock):
            res = future.result()
            if res is not None:
                results.append(res)
                if not res['trades_df'].empty:
                    all_trades.append(res['trades_df'])

    # Sort results by Net PnL descending
    results = sorted(results, key=lambda x: x['pnl'], reverse=True)

    print(f"{'#':<3} | {'Symbol':<12} | {'Price (Rs)':<10} | {'Trades':<6} | {'Wins':<5} | {'Loss':<5} | {'Win %':<6} | {'Net PnL (Rs)':<14} | {'PF':<5} | {'Max DD (Rs)':<12}")
    print("-" * 115)
    
    tot_trades = 0
    tot_wins = 0
    tot_losses = 0
    tot_pnl = 0.0
    tot_gross_profit = 0.0
    tot_gross_loss = 0.0

    for idx, r in enumerate(results, 1):
        tot_trades += r['trades']
        tot_wins += r.get('wins', 0)
        tot_losses += r.get('losses', 0)
        tot_pnl += r['pnl']
        tot_gross_profit += r.get('gross_profit', 0.0)
        tot_gross_loss += r.get('gross_loss', 0.0)

        pf_str = f"{r['profit_factor']:.2f}" if r['profit_factor'] < 100 else "Inf"
        print(f"{idx:<3} | {r['symbol']:<12} | {r['avg_price']:<10.1f} | {r['trades']:<6} | {r.get('wins', 0):<5} | {r.get('losses', 0):<5} | {r['win_rate']:<5.1f}% | Rs {r['pnl']:+11,.0f} | {pf_str:<5} | Rs {r['max_dd']:+10,.0f}")

    port_win_rate = (tot_wins / tot_trades * 100.0) if tot_trades > 0 else 0.0
    port_pf = (tot_gross_profit / tot_gross_loss) if tot_gross_loss > 0 else float('inf')

    print("=" * 115)
    print(f"PORTFOLIO TOTALS ({len(results)} STOCKS EVALUATED):")
    print(f"Total Trades: {tot_trades} | Total Wins: {tot_wins} | Total Losses: {tot_losses} | Win Rate: {port_win_rate:.1f}%")
    print(f"Net Portfolio PnL: Rs {tot_pnl:+12,.2f} | Profit Factor: {port_pf:.2f} | Gross Profit: Rs {tot_gross_profit:+12,.2f} | Gross Loss: Rs -{tot_gross_loss:11,.2f}")
    print("=" * 115 + "\n")

    # Direction breakdown
    if all_trades:
        df_all = pd.concat(all_trades, ignore_index=True)
        long_trades = df_all[df_all['direction'] == 'LONG']
        short_trades = df_all[df_all['direction'] == 'SHORT']

        print("--- LONG VS SHORT BREAKDOWN ---")
        for d_name, d_df in [('LONG', long_trades), ('SHORT', short_trades)]:
            w = d_df[d_df['pnl'] > 0]
            wr = (len(w) / len(d_df) * 100.0) if len(d_df) > 0 else 0.0
            gp = w['pnl'].sum() if not w.empty else 0.0
            gl = abs(d_df[d_df['pnl'] <= 0]['pnl'].sum())
            pf = gp / gl if gl > 0 else float('inf')
            print(f"{d_name:<6}: {len(d_df):3d} trades | Win Rate: {wr:5.1f}% | Net PnL: Rs {d_df['pnl'].sum():+10,.2f} | PF: {pf:.2f}")
        print("-" * 50 + "\n")

        # Monthly breakdown
        df_all['Month'] = df_all['entry_time'].apply(lambda x: x.strftime('%B %Y'))
        print("--- PORTFOLIO MONTHLY CONSISTENCY ---")
        for m, g in df_all.groupby('Month', sort=False):
            wins = g[g['pnl'] > 0]
            wr = (len(wins) / len(g) * 100.0) if len(g) > 0 else 0.0
            print(f"{m:<16}: {len(g):3d} trades | Win Rate: {wr:5.1f}% | Net PnL: Rs {g['pnl'].sum():+10,.2f}")
        print("-" * 50 + "\n")

if __name__ == '__main__':
    main()
