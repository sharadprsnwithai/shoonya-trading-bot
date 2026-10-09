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

def run_filter_study(symbol, yf_ticker, price_factor, lot_size):
    df = yf.download(yf_ticker, period='59d', interval='15m', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    df.index = df.index.tz_convert('Asia/Kolkata')
    df = df[(df.index.time >= datetime.time(9, 0)) & (df.index.time <= datetime.time(23, 30))].copy()
    df['VWAP'] = calculate_intraday_vwap(df)
    df['EMA20'] = calculate_ema(df['Close'], 20)
    df['Vol_MA'] = df['Volume'].rolling(10).mean()
    
    dates = np.unique(df.index.date)
    
    # We will test multiple filter configurations:
    # 0. Baseline (No extra filters)
    # 1. Filter: European/US Session Only (Trade entries >= 15:30 IST / 3:30 PM, skipping low-volume 1:30-3:30 lull)
    # 2. Filter: EMA20 Trend Filter (LONG if Close > EMA20, SHORT if Close < EMA20)
    # 3. Filter: Volume Confirmation (Crossover candle Volume >= Volume MA)
    # 4. Filter: Trigger Expiry (Must break out within 2 candles = 30 min of cross, avoiding stale ranges)
    # 5. Filter: Combined (Session >= 15:30 + EMA20 + Trigger Expiry <= 2 bars)
    
    configs = [
        ('Baseline (Current)', lambda t, bar, prev, armed_age: True),
        ('A. Skip Afternoon Chop (Entries >= 15:30 IST)', lambda t, bar, prev, armed_age: t >= datetime.time(15, 30)),
        ('B. Trend Filter (EMA20 Alignment)', lambda t, bar, prev, armed_age: (bar['Close'] >= bar['EMA20'] if bar['side'] == 'LONG' else bar['Close'] <= bar['EMA20'])),
        ('C. Fresh Breakouts Only (Trigger Age <= 2 bars)', lambda t, bar, prev, armed_age: armed_age <= 2),
        ('D. Volume Confirmation (Vol > 10-bar Avg)', lambda t, bar, prev, armed_age: prev['Volume'] >= prev['Vol_MA']),
        ('★ E. PRO FILTER: (Time >= 15:30 + EMA20 + Fresh Age <= 2)', lambda t, bar, prev, armed_age: (t >= datetime.time(15, 30) and armed_age <= 2 and (bar['Close'] >= bar['EMA20'] if bar['side'] == 'LONG' else bar['Close'] <= bar['EMA20']))),
    ]
    
    print("=" * 105)
    print(f" 🔬 FILTER OPTIMIZATION STUDY TO REDUCE LOSING TRADES: {symbol} (LAST 60 DAYS)")
    print("=" * 105)
    print(f"{'Filter Configuration':<50} | {'Trades':<7} | {'Wins':<6} | {'Losses':<7} | {'Win Rate':<10} | {'Profit Factor':<14} | {'Net P&L (₹)':<12}")
    print("-" * 105)
    
    for name, filter_fn in configs:
        trades = []
        for session_date in dates:
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
            prev_cross_bar = None
            
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
                        active_position = None
                        day_done = True
                        break
                    continue
                    
                if day_done:
                    break
                    
                # Breakout check
                if armed_state is not None and active_position is None:
                    armed_age = i - armed_idx
                    side_check = {'side': armed_state, 'Close': curr_bar['Close'], 'EMA20': curr_bar['EMA20']}
                    
                    if filter_fn(bar_time, side_check, prev_cross_bar, armed_age):
                        if armed_state == 'LONG' and curr_bar['High'] >= trigger_price:
                            entry = trigger_price
                            sl = setup_vwap
                            risk = entry - sl
                            if risk <= entry * 0.001:
                                risk = entry * 0.002
                                sl = entry - risk
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
                            target = entry - (2.5 * risk)
                            active_position = {'side': 'SHORT', 'entry': entry, 'sl': sl, 'target': target}
                            armed_state = None
                            continue
                    else:
                        if armed_age > 2:
                            armed_state = None # Trigger expired
                            
                # Crossover Check
                if bar_time < datetime.time(22, 30) and active_position is None:
                    p_c = prev_bar['Close']
                    p_v = prev_bar['VWAP']
                    c_c = curr_bar['Close']
                    c_v = curr_bar['VWAP']
                    
                    if bias == 'BULLISH' and p_c <= p_v and c_c > c_v:
                        armed_state = 'LONG'
                        trigger_price = curr_bar['High']
                        setup_vwap = c_v
                        armed_idx = i
                        prev_cross_bar = curr_bar
                    elif bias == 'BEARISH' and p_c >= p_v and c_c < c_v:
                        armed_state = 'SHORT'
                        trigger_price = curr_bar['Low']
                        setup_vwap = c_v
                        armed_idx = i
                        prev_cross_bar = curr_bar
                        
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
        
        print(f"{name:<50} | {tot:<7} | {wins:<6} | {losses:<7} | {wr:>8.1f}% | {pf:>14.2f} | ₹{pnl:>10,.2f}")

if __name__ == '__main__':
    # 1. Silver Mini
    run_filter_study('SILVER MINI (SILVERM)', 'SI=F', 32.15075 * 84.0, 5)
    print("\n")
    # 2. Crude Oil Mini
    run_filter_study('CRUDE OIL MINI (CRUDEOILM)', 'CL=F', 84.0, 10)
