import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_ema(series, span=20):
    return series.ewm(span=span, adjust=False).mean()

def calculate_rsi(series, period=14):
    delta = series.diff()
    gain = (delta.where(delta > 0, 0)).rolling(window=period).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=period).mean()
    rs = gain / loss.replace(0, 1e-9)
    return 100 - (100 / (1 + rs))

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

def run_drawdown_experiments(symbol, yf_ticker, price_factor, lot_size):
    df = yf.download(yf_ticker, period='59d', interval='15m', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    df.index = df.index.tz_convert('Asia/Kolkata')
    df = df[(df.index.time >= datetime.time(9, 0)) & (df.index.time <= datetime.time(23, 30))].copy()
    df['VWAP'] = calculate_intraday_vwap(df)
    df['EMA20'] = calculate_ema(df['Close'], 20)
    df['RSI'] = calculate_rsi(df['Close'], 14)
    
    dates = np.unique(df.index.date)
    
    # Drawdown reduction techniques to evaluate:
    # 0. Baseline (20 EMA + 30m fresh age)
    # 1. Max Risk Cap: Skip setups if Risk > 0.8% of spot (avoids wide volatile bar stops)
    # 2. Prime Liquidity Hours Only (Entries between 16:00 and 21:30 IST)
    # 3. RSI Momentum Filter (50 <= RSI <= 70 for LONG, 30 <= RSI <= 50 for SHORT)
    # 4. Consecutive Loss Stand-Down (1-session cooloff after 2 consecutive losing trades)
    # 5. Combined Fortress Guard (Max Risk <= 0.8% + Prime Hours 16:00-21:30 + RSI Momentum)
    
    techniques = [
        ('Baseline (20 EMA + 30m Age)', {
            'max_risk_pct': 99.0, 'start_time': datetime.time(15, 30), 'end_time': datetime.time(22, 30), 'rsi_filter': False, 'stand_down': False
        }),
        ('1. Max Risk Cap (Skip if Risk > 0.8%)', {
            'max_risk_pct': 0.008, 'start_time': datetime.time(15, 30), 'end_time': datetime.time(22, 30), 'rsi_filter': False, 'stand_down': False
        }),
        ('2. Prime Hours Only (16:00 - 21:30 IST)', {
            'max_risk_pct': 99.0, 'start_time': datetime.time(16, 0), 'end_time': datetime.time(21, 30), 'rsi_filter': False, 'stand_down': False
        }),
        ('3. RSI Filter (50-70 Long / 30-50 Short)', {
            'max_risk_pct': 99.0, 'start_time': datetime.time(15, 30), 'end_time': datetime.time(22, 30), 'rsi_filter': True, 'stand_down': False
        }),
        ('4. 2-Loss Stand-Down Cooloff', {
            'max_risk_pct': 99.0, 'start_time': datetime.time(15, 30), 'end_time': datetime.time(22, 30), 'rsi_filter': False, 'stand_down': True
        }),
        ('★ 5. COMBINED FORTRESS (Risk Cap + Prime Hours + RSI)', {
            'max_risk_pct': 0.008, 'start_time': datetime.time(16, 0), 'end_time': datetime.time(21, 30), 'rsi_filter': True, 'stand_down': False
        }),
    ]
    
    print("=" * 115)
    print(f" 🛡️ DRAWDOWN REDUCTION EXPERIMENTS: {symbol} (LAST 60 DAYS)")
    print("=" * 115)
    print(f"{'Configuration':<45} | {'Trades':<7} | {'Win Rate':<9} | {'PF':<6} | {'Net P&L (₹)':<12} | {'Max DD (₹)':<12} | {'Return / DD':<11}")
    print("-" * 115)
    
    for name, cfg in techniques:
        trades = []
        consecutive_losses = 0
        in_stand_down = False
        
        for session_date in dates:
            if cfg['stand_down'] and in_stand_down:
                in_stand_down = False
                continue
                
            day_df = df[df.index.date == session_date].copy()
            if len(day_df) < 8:
                continue
                
            morning_bars = day_df[day_df.index.time <= datetime.time(13, 30)]
            if len(morning_bars) < 3:
                continue
                
            c_1330 = morning_bars.iloc[-1]['Close']
            o_0900 = morning_bars.iloc[0]['Open']
            vwap_1330 = morning_bars.iloc[-1]['VWAP']
            
            if c_1330 >= vwap_1330 and c_1330 >= o_0900:
                bias = 'BULLISH'
            elif c_1330 <= vwap_1330 and c_1330 <= o_0900:
                bias = 'BEARISH'
            else:
                bias = 'BULLISH' if c_1330 >= vwap_1330 else 'BEARISH'
                
            post_1330 = day_df[day_df.index.time >= datetime.time(13, 30)]
            
            armed_state = None
            trigger_price = None
            setup_vwap = None
            armed_idx = None
            
            active_position = None
            day_done = False
            
            for i in range(1, len(post_1330)):
                prev_bar = post_1330.iloc[i - 1]
                curr_bar = post_1330.iloc[i]
                bar_time = curr_bar.name.time()
                
                # Manage active
                if active_position is not None:
                    side = active_position['side']
                    entry = active_position['entry']
                    sl = active_position['sl']
                    target = active_position['target']
                    
                    exit_price = None
                    if bar_time >= datetime.time(23, 15):
                        exit_price = curr_bar['Close']
                    elif side == 'LONG':
                        if curr_bar['Low'] <= sl: exit_price = sl
                        elif curr_bar['High'] >= target: exit_price = target
                    elif side == 'SHORT':
                        if curr_bar['High'] >= sl: exit_price = sl
                        elif curr_bar['Low'] <= target: exit_price = target
                        
                    if exit_price is not None:
                        pnl_pts = (exit_price - entry) if side == 'LONG' else (entry - exit_price)
                        pnl_inr = pnl_pts * price_factor * lot_size
                        active_position['pnl_inr'] = pnl_inr
                        trades.append(active_position)
                        
                        if pnl_inr <= 0:
                            consecutive_losses += 1
                            if consecutive_losses >= 2:
                                in_stand_down = True
                                consecutive_losses = 0
                        else:
                            consecutive_losses = 0
                            
                        active_position = None
                        day_done = True
                        break
                    continue
                    
                if day_done:
                    break
                    
                # Breakout Execution
                if armed_state is not None and active_position is None:
                    armed_age = i - armed_idx
                    if armed_age > 2:
                        armed_state = None
                    else:
                        curr_ema20 = curr_bar['EMA20']
                        curr_rsi = curr_bar['RSI']
                        trend_ok = (curr_bar['Close'] >= curr_ema20) if armed_state == 'LONG' else (curr_bar['Close'] <= curr_ema20)
                        
                        rsi_ok = True
                        if cfg['rsi_filter']:
                            if armed_state == 'LONG':
                                rsi_ok = (50 <= curr_rsi <= 72)
                            else:
                                rsi_ok = (28 <= curr_rsi <= 50)
                                
                        if trend_ok and rsi_ok and (bar_time >= cfg['start_time']) and (bar_time <= cfg['end_time']):
                            if armed_state == 'LONG' and curr_bar['High'] >= trigger_price:
                                entry = trigger_price
                                sl = setup_vwap
                                risk = entry - sl
                                if risk <= entry * 0.001:
                                    risk = entry * 0.002
                                    sl = entry - risk
                                    
                                if (risk / entry) <= cfg['max_risk_pct']:
                                    target = entry + (2.5 * risk)
                                    active_position = {'side': 'LONG', 'entry': entry, 'sl': sl, 'target': target}
                                    armed_state = None
                                    continue
                            elif armed_state == 'SHORT' and curr_bar['Low'] <= trigger_price:
                                entry = trigger_price
                                sl = setup_vwap
                                risk = sl - entry
                                if risk <= entry * 0.001:
                                    risk = entry * 0.002
                                    sl = entry + risk
                                    
                                if (risk / entry) <= cfg['max_risk_pct']:
                                    target = entry - (2.5 * risk)
                                    active_position = {'side': 'SHORT', 'entry': entry, 'sl': sl, 'target': target}
                                    armed_state = None
                                    continue
                                    
                # Crossover check
                if bar_time >= cfg['start_time'] and bar_time <= cfg['end_time'] and active_position is None:
                    p_c = prev_bar['Close']
                    p_v = prev_bar['VWAP']
                    c_c = curr_bar['Close']
                    c_v = curr_bar['VWAP']
                    
                    if bias == 'BULLISH' and p_c <= p_v and c_c > c_v:
                        armed_state = 'LONG'
                        trigger_price = curr_bar['High']
                        setup_vwap = c_v
                        armed_idx = i
                    elif bias == 'BEARISH' and p_c >= p_v and c_c < c_v:
                        armed_state = 'SHORT'
                        trigger_price = curr_bar['Low']
                        setup_vwap = c_v
                        armed_idx = i
                        
        df_res = pd.DataFrame(trades)
        if len(df_res) == 0:
            continue
        tot = len(df_res)
        wins = len(df_res[df_res['pnl_inr'] > 0])
        losses = len(df_res[df_res['pnl_inr'] <= 0])
        wr = (wins / tot) * 100.0 if tot > 0 else 0
        pnl = df_res['pnl_inr'].sum()
        gp = df_res[df_res['pnl_inr'] > 0]['pnl_inr'].sum()
        gl = abs(df_res[df_res['pnl_inr'] <= 0]['pnl_inr'].sum())
        pf = (gp / gl) if gl > 0 else 0
        
        cum = df_res['pnl_inr'].cumsum()
        peak = cum.cummax()
        dd = (peak - cum).max()
        ret_dd = (pnl / dd) if dd > 0 else 0.0
        
        print(f"{name:<45} | {tot:<7} | {wr:>7.1f}% | {pf:>6.2f} | ₹{pnl:>10,.2f} | ₹{dd:>10,.2f} | {ret_dd:>9.2f}x")

if __name__ == '__main__':
    run_drawdown_experiments('SILVER MINI (SILVERM)', 'SI=F', 32.15075 * 84.0, 5)
    print("\n")
    run_drawdown_experiments('CRUDE OIL MINI (CRUDEOILM)', 'CL=F', 84.0, 10)
