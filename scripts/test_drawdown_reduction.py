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

def backtest_drawdown_optimizer(
    df_merged,
    max_trades_per_day=2,
    stop_after_one_loss=False,
    max_daily_loss_pts=30.0,         # Hard daily drawdown cap in option pts (e.g. ~Rs 19,500 on 10 lots)
    trailing_step_enabled=True,      # Multi-step trailing stop
    trail_step_1_trigger=15.0,       # At +15 pts, lock +2 pts (Breakeven)
    trail_step_2_trigger=28.0,       # At +28 pts, lock +18 pts
    vwap_max_distance_pts=45.0,      # Don't chase: skip entry if spot is > 45 pts away from VWAP
    adx_threshold=20.0,
    dead_zone_rsi_filter=False,
    start_time_str="09:30",
    end_time_str="14:15",
    sl_pts=30.0,                     # Tighter base SL (30 pts instead of 35)
    tp_pts=50.0
):
    LOT_SIZE = 65
    NUM_LOTS = 10
    TOTAL_QTY = LOT_SIZE * NUM_LOTS

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
    rsi_15ms = df_merged['RSI_15m'].values

    start_t = datetime.time.fromisoformat(start_time_str)
    end_t = datetime.time.fromisoformat(end_time_str)

    for i in range(1, len(df_merged)):
        day = dates[i]
        t = times[i]
        spot = closes[i]
        vwap = vwaps[i]
        st_dir = st_dirs[i]
        adx = adxs[i]
        p_di = p_dis[i]
        m_di = m_dis[i]
        rsi_15 = rsi_15ms[i]
        timestamp = df_merged.index[i]

        prev_spot = closes[i - 1]
        prev_vwap = vwaps[i - 1]
        prev_st_dir = st_dirs[i - 1]

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
            theta_pts = hours_held * 2.5 # theta decay

            if direction == 'BULLISH':
                spot_move = spot - entry_spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts
            else:
                spot_move = entry_spot - spot
                opt_pnl_pts = (spot_move * 0.50) + theta_pts

            if opt_pnl_pts > current_trade['max_fav_pts']:
                current_trade['max_fav_pts'] = opt_pnl_pts

            # Dynamic Stepped Trailing Stop
            effective_sl = -sl_pts
            if trailing_step_enabled:
                if current_trade['max_fav_pts'] >= trail_step_2_trigger:
                    effective_sl = 18.0
                elif current_trade['max_fav_pts'] >= trail_step_1_trigger:
                    effective_sl = 2.0

            # SL or Trailing SL hit
            if opt_pnl_pts <= effective_sl:
                exit_reason = "TRAIL_SL_LOCK" if effective_sl > -sl_pts else "STOP_LOSS"
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = effective_sl
                current_trade['pnl'] = effective_sl * TOTAL_QTY
                current_trade['exit_reason'] = exit_reason
                daily_pnl_pts += effective_sl
                trades.append(current_trade)
                current_trade = None
                continue

            # Target Profit Hit
            elif opt_pnl_pts >= tp_pts:
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = tp_pts
                current_trade['pnl'] = tp_pts * TOTAL_QTY
                current_trade['exit_reason'] = "TARGET_PROFIT"
                daily_pnl_pts += tp_pts
                trades.append(current_trade)
                current_trade = None
                continue

            # Supertrend Flip Exit
            if (direction == 'BULLISH' and st_dir == -1) or (direction == 'BEARISH' and st_dir == 1):
                current_trade['exit_time'] = timestamp
                current_trade['exit_spot'] = spot
                current_trade['opt_pts'] = opt_pnl_pts
                current_trade['pnl'] = opt_pnl_pts * TOTAL_QTY
                current_trade['exit_reason'] = "ST_FLIP"
                daily_pnl_pts += opt_pnl_pts
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
                daily_pnl_pts += opt_pnl_pts
                trades.append(current_trade)
                current_trade = None
                continue

        # 2. Check New Entry Conditions
        if current_trade is None and trades_today < max_trades_per_day:
            if stop_after_one_loss and daily_pnl_pts < 0:
                continue # Circuit breaker after 1 loss

            if daily_pnl_pts <= -max_daily_loss_pts:
                continue # Daily max loss cap reached

            if start_t <= t <= end_t:
                if adx >= adx_threshold:
                    if dead_zone_rsi_filter and (45.0 <= rsi_15 <= 55.0):
                        continue

                    # Bullish Entry
                    vwap_dist = spot - vwap
                    if 0 < vwap_dist <= vwap_max_distance_pts and st_dir == 1 and p_di > m_di:
                        bull_cross = (prev_spot <= prev_vwap and spot > vwap) or (prev_st_dir == -1 and st_dir == 1) or (trades_today == 0)
                        if bull_cross:
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

                    # Bearish Entry
                    vwap_dist_bear = vwap - spot
                    if 0 < vwap_dist_bear <= vwap_max_distance_pts and st_dir == -1 and m_di > p_di:
                        bear_cross = (prev_spot >= prev_vwap and spot < vwap) or (prev_st_dir == 1 and st_dir == -1) or (trades_today == 0)
                        if bear_cross:
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
    df_15m['RSI_15m'] = calculate_rsi(df_15m['Close'], 14)

    df_5m['Time_15m'] = df_5m.index.floor('15min')
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI', 'RSI_15m']
    df_15m_subset = df_15m[cols_15m].shift(1)
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX', 'RSI_15m'])

    print("=========================================================================================================")
    print("DRAWDOWN REDUCTION & RISK MANAGEMENT EXPERIMENTS (10 LOTS / 650 QTY)")
    print("=========================================================================================================")

    experiments = [
        ("Base Setup (Fixed 35pt SL, No Trailing, No Limit)", {
            "max_trades_per_day": 2, "stop_after_one_loss": False, "trailing_step_enabled": False, "vwap_max_distance_pts": 999.0, "sl_pts": 35.0
        }),
        ("Step 1: Stepped Trailing Stop (Lock breakeven @ +15, Lock +18 @ +28)", {
            "max_trades_per_day": 2, "stop_after_one_loss": False, "trailing_step_enabled": True, "trail_step_1_trigger": 15.0, "trail_step_2_trigger": 28.0, "vwap_max_distance_pts": 999.0, "sl_pts": 30.0
        }),
        ("Step 2: No-Chasing Rule (Max Distance from VWAP <= 40 pts)", {
            "max_trades_per_day": 2, "stop_after_one_loss": False, "trailing_step_enabled": True, "trail_step_1_trigger": 15.0, "trail_step_2_trigger": 28.0, "vwap_max_distance_pts": 40.0, "sl_pts": 30.0
        }),
        ("Step 3: 1-Loss-and-Done Circuit Breaker + Trailing SL", {
            "max_trades_per_day": 2, "stop_after_one_loss": True, "trailing_step_enabled": True, "trail_step_1_trigger": 15.0, "trail_step_2_trigger": 28.0, "vwap_max_distance_pts": 40.0, "sl_pts": 30.0
        }),
        ("Step 4: ADX >= 22 (Avoid Weak Choppy Trends) + Trailing SL", {
            "max_trades_per_day": 2, "stop_after_one_loss": False, "trailing_step_enabled": True, "trail_step_1_trigger": 15.0, "trail_step_2_trigger": 28.0, "adx_threshold": 22.0, "vwap_max_distance_pts": 40.0, "sl_pts": 30.0
        }),
        ("Step 5: [CHAMPION] Trailing Stop + No-Chase (<=35pts VWAP) + ADX>=22", {
            "max_trades_per_day": 2, "stop_after_one_loss": False, "trailing_step_enabled": True, "trail_step_1_trigger": 15.0, "trail_step_2_trigger": 28.0, "adx_threshold": 22.0, "vwap_max_distance_pts": 35.0, "sl_pts": 28.0
        })
    ]

    for name, params in experiments:
        res = backtest_drawdown_optimizer(df_merged, **params)
        pnl_str = f"+Rs {res['pnl']:,.2f}" if res['pnl'] >= 0 else f"-Rs {abs(res['pnl']):,.2f}"
        dd_str = f"-Rs {abs(res['max_dd']):,.2f}"
        print(f"\n>> {name}")
        print(f"   Trades: {res['trades']:<2} | Win Rate: {res['win_rate']:<4.1f}% | Net PnL: {pnl_str:<14} | PF: {res['profit_factor']:<4.2f} | Max DD: {dd_str}")

if __name__ == '__main__':
    main()
