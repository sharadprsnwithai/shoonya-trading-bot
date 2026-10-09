import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_rsi(series, period=14):
    delta = series.diff()
    gain = (delta.where(delta > 0, 0)).copy()
    loss = (-delta.where(delta < 0, 0)).copy()
    avg_gain = gain.rolling(window=period, min_periods=period).mean()
    avg_loss = loss.rolling(window=period, min_periods=period).mean()
    for i in range(period, len(series)):
        avg_gain.iloc[i] = (avg_gain.iloc[i-1] * (period - 1) + gain.iloc[i]) / period
        avg_loss.iloc[i] = (avg_loss.iloc[i-1] * (period - 1) + loss.iloc[i]) / period
    rs = avg_gain / avg_loss.replace(0, np.nan)
    return (100 - (100 / (1 + rs))).fillna(50)

def calculate_intraday_vwap(df):
    vwaps = []
    for date, group in df.groupby(df.index.date):
        vol = group['Volume'].replace(0, 1)
        typical = (group['High'] + group['Low'] + group['Close']) / 3.0
        cum_vp = (typical * vol).cumsum()
        cum_vol = vol.cumsum()
        vwap = cum_vp / cum_vol
        vwaps.append(vwap)
    return pd.concat(vwaps)

def run_orb_backtest(sl_type='ORB_BOUNDARY', rr_ratio=2.0):
    df_5m = yf.download('^NSEI', period='60d', interval='5m', progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)
    df_5m = df_5m.dropna().copy()
    df_5m['RSI'] = calculate_rsi(df_5m['Close'], 14)
    df_5m['VWAP'] = calculate_intraday_vwap(df_5m)
    
    dates = np.unique(df_5m.index.date)
    trades = []
    
    for session_date in dates:
        day_bars = df_5m[df_5m.index.date == session_date].copy()
        if len(day_bars) < 20:
            continue
            
        # 1. 09:15 to 09:45 Bars (First 6 bars: 09:15, 09:20, 09:25, 09:30, 09:35, 09:40)
        bars_0915_0945 = day_bars.between_time('09:15', '09:40')
        if len(bars_0915_0945) < 5:
            continue
            
        bar_0945 = bars_0915_0945.iloc[-1]
        spot_0945 = bar_0945['Close']
        open_0915 = bars_0915_0945.iloc[0]['Open']
        vwap_0945 = bar_0945['VWAP']
        rsi_0945 = bar_0945['RSI']
        
        # Determine 09:45 Directional Bias:
        pct_0945 = ((spot_0945 - open_0915) / open_0915) * 100.0
        bias = None
        if spot_0945 > vwap_0945 and pct_0945 > 0.10 and rsi_0945 > 52:
            bias = 'LONG'
        elif spot_0945 < vwap_0945 and pct_0945 < -0.10 and rsi_0945 < 48:
            bias = 'SHORT'
        else:
            bias = 'NEUTRAL'
            
        if bias == 'NEUTRAL':
            continue
            
        # 2. 09:45 to 10:15 ORB Range (6 bars: 09:45, 09:50, 09:55, 10:00, 10:05, 10:10)
        bars_orb = day_bars.between_time('09:45', '10:10')
        if len(bars_orb) < 5:
            continue
            
        orb_high = bars_orb['High'].max()
        orb_low = bars_orb['Low'].min()
        orb_range = orb_high - orb_low
        
        # Skip if ORB range is excessively wide (> 1.2% of Nifty)
        if orb_range > spot_0945 * 0.012:
            continue
            
        # 3. 10:15 onwards Execution Window (up to 14:30)
        execution_bars = day_bars.between_time('10:15', '15:15')
        if execution_bars.empty:
            continue
            
        position = None
        entry_price = 0.0
        sl_price = 0.0
        target_price = 0.0
        entry_time = None
        
        for idx, bar in execution_bars.iterrows():
            c = bar['Close']
            h = bar['High']
            l = bar['Low']
            vwap = bar['VWAP']
            time_str = idx.strftime('%H:%M')
            
            if position is None:
                # Check Breakout entry in direction of bias
                if bias == 'LONG':
                    if c > orb_high and c > vwap and time_str <= '13:00':
                        position = 'LONG'
                        entry_price = c
                        entry_time = idx
                        if sl_type == 'ORB_BOUNDARY':
                            sl_price = orb_low
                        elif sl_type == 'ORB_MID':
                            sl_price = (orb_high + orb_low) / 2.0
                        else:
                            sl_price = min(orb_low, vwap)
                        risk = entry_price - sl_price
                        if risk <= 0 or risk > entry_price * 0.01:
                            risk = entry_price * 0.004 # default 0.4% risk
                            sl_price = entry_price - risk
                        target_price = entry_price + (risk * rr_ratio)
                elif bias == 'SHORT':
                    if c < orb_low and c < vwap and time_str <= '13:00':
                        position = 'SHORT'
                        entry_price = c
                        entry_time = idx
                        if sl_type == 'ORB_BOUNDARY':
                            sl_price = orb_high
                        elif sl_type == 'ORB_MID':
                            sl_price = (orb_high + orb_low) / 2.0
                        else:
                            sl_price = max(orb_high, vwap)
                        risk = sl_price - entry_price
                        if risk <= 0 or risk > entry_price * 0.01:
                            risk = entry_price * 0.004
                            sl_price = entry_price + risk
                        target_price = entry_price - (risk * rr_ratio)
            else:
                # Manage Open Position
                exit_price = 0.0
                exit_reason = ""
                
                if position == 'LONG':
                    if l <= sl_price:
                        exit_price = sl_price
                        exit_reason = 'STOP_LOSS_HIT'
                    elif h >= target_price:
                        exit_price = target_price
                        exit_reason = 'TARGET_HIT'
                    elif time_str >= '15:00':
                        exit_price = c
                        exit_reason = 'EOD_1500_EXIT'
                elif position == 'SHORT':
                    if h >= sl_price:
                        exit_price = sl_price
                        exit_reason = 'STOP_LOSS_HIT'
                    elif l <= target_price:
                        exit_price = target_price
                        exit_reason = 'TARGET_HIT'
                    elif time_str >= '15:00':
                        exit_price = c
                        exit_reason = 'EOD_1500_EXIT'
                        
                if exit_price > 0:
                    pnl_pts = (exit_price - entry_price) if position == 'LONG' else (entry_price - exit_price)
                    trades.append({
                        'date': session_date,
                        'bias': bias,
                        'position': position,
                        'entry_time': entry_time,
                        'exit_time': idx,
                        'entry_price': entry_price,
                        'exit_price': exit_price,
                        'pnl_pts': pnl_pts,
                        'exit_reason': exit_reason
                    })
                    break # 1 trade per day
                    
    return pd.DataFrame(trades)

if __name__ == '__main__':
    print("=" * 90)
    print("🔬 BACKTEST: 09:45 AM DIRECTIONAL BIAS + 09:45-10:15 AM ORB STRATEGY")
    print("=" * 90)
    
    for sl in ['ORB_BOUNDARY', 'ORB_MID']:
        for rr in [1.5, 2.0]:
            df_res = run_orb_backtest(sl_type=sl, rr_ratio=rr)
            if len(df_res) == 0:
                print(f"SL={sl}, RR={rr}: No trades")
                continue
            wins = df_res[df_res['pnl_pts'] > 0]
            losses = df_res[df_res['pnl_pts'] <= 0]
            wr = len(wins) / len(df_res) * 100.0
            tot_pts = df_res['pnl_pts'].sum()
            gross_p = wins['pnl_pts'].sum()
            gross_l = abs(losses['pnl_pts'].sum())
            pf = gross_p / gross_l if gross_l > 0 else 99.9
            
            # On 2 lots Nifty (50 qty):
            pnl_rs_2lots = tot_pts * 50
            
            print(f"⚙️ SL Mode: {sl:<12} | Target: 1:{rr} RR -> Trades: {len(df_res):<2} | WinRate: {wr:.1f}% | PF: {pf:.2f} | Net Points: {tot_pts:<+7.1f} | PnL (2 lots): ₹{pnl_rs_2lots:<+9,.0f}")
    print("=" * 90)
