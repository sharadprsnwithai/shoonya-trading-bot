import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_ema(series, span=21):
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

def run_vwap_ema21_pullback(ticker, name, mcx_lot=100):
    df = yf.download(ticker, period='60d', interval='5m', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    if len(df) < 100: return None
    
    df['EMA21'] = calculate_ema(df['Close'], 21)
    df['EMA9'] = calculate_ema(df['Close'], 9)
    df['VWAP'] = calculate_intraday_vwap(df)
    df['RSI'] = calculate_rsi(df['Close'], 14)
    df = df.dropna().copy()
    
    dates = np.unique(df.index.date)
    trades = []
    
    for session_date in dates:
        day_bars = df[df.index.date == session_date].copy()
        if len(day_bars) < 15: continue
        
        position = None
        open_trade = None
        
        for idx in range(2, len(day_bars)):
            bar = day_bars.iloc[idx]
            prev_bar = day_bars.iloc[idx - 1]
            ts = day_bars.index[idx]
            
            c = bar['Close']
            o = bar['Open']
            h = bar['High']
            l = bar['Low']
            ema21 = bar['EMA21']
            ema9 = bar['EMA9']
            vwap = bar['VWAP']
            rsi = bar['RSI']
            
            # 1. Manage Active Trade
            if position is not None:
                p_dir = open_trade['direction']
                entry_p = open_trade['entry_price']
                sl_p = open_trade['current_sl']
                t1 = open_trade['target1']
                partial_booked = open_trade['partial_booked']
                
                exit_price = 0.0
                exit_reason = ""
                
                if p_dir == 'LONG':
                    if l <= sl_p:
                        exit_price = sl_p
                        exit_reason = "TRAILING_COST_SL" if partial_booked else "STOP_LOSS"
                    elif not partial_booked and h >= t1:
                        open_trade['partial_booked'] = True
                        open_trade['partial_pnl'] = (t1 - entry_p) * 0.5
                        open_trade['current_sl'] = entry_p # Cost SL
                    elif partial_booked and c < ema9:
                        exit_price = c
                        exit_reason = "EMA9_TRAIL_EXIT"
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                else: # SHORT
                    if h >= sl_p:
                        exit_price = sl_p
                        exit_reason = "TRAILING_COST_SL" if partial_booked else "STOP_LOSS"
                    elif not partial_booked and l <= t1:
                        open_trade['partial_booked'] = True
                        open_trade['partial_pnl'] = (entry_p - t1) * 0.5
                        open_trade['current_sl'] = entry_p
                    elif partial_booked and c > ema9:
                        exit_price = c
                        exit_reason = "EMA9_TRAIL_EXIT"
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                        
                if exit_price > 0:
                    runner_pnl = ((exit_price - entry_p) if p_dir == 'LONG' else (entry_p - exit_price)) * (0.5 if partial_booked else 1.0)
                    total_pts = (open_trade['partial_pnl'] + runner_pnl) if partial_booked else runner_pnl
                    pnl_rs = total_pts * mcx_lot
                    
                    trades.append({
                        'name': name,
                        'total_pts': total_pts,
                        'pnl_rs': pnl_rs,
                        'exit_reason': exit_reason
                    })
                    position = None
                    open_trade = None
                    break # Max 1 trade/day
                    
            # 2. Check Pullback Reversal Setup
            if position is None:
                # Bullish Pullback: Price is in uptrend (EMA21 > VWAP), bar dips into EMA21 zone (Low <= EMA21) and closes Green above EMA21
                if ema21 > vwap and prev_bar['Low'] <= prev_bar['EMA21'] and c > o and c > ema21 and rsi > 50:
                    position = 'LONG'
                    entry_p = c
                    sl_p = min(prev_bar['Low'], l) - (entry_p * 0.001)
                    risk = max(entry_p - sl_p, entry_p * 0.003)
                    sl_p = entry_p - risk
                    t1 = entry_p + (risk * 2.0)
                    open_trade = {
                        'direction': 'LONG',
                        'entry_time': ts,
                        'entry_price': entry_p,
                        'current_sl': sl_p,
                        'target1': t1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                # Bearish Pullback: Price is in downtrend (EMA21 < VWAP), bar rallies to touch EMA21 (High >= EMA21) and closes Red below EMA21
                elif ema21 < vwap and prev_bar['High'] >= prev_bar['EMA21'] and c < o and c < ema21 and rsi < 50:
                    position = 'SHORT'
                    entry_p = c
                    sl_p = max(prev_bar['High'], h) + (entry_p * 0.001)
                    risk = max(sl_p - entry_p, entry_p * 0.003)
                    sl_p = entry_p + risk
                    t1 = entry_p - (risk * 2.0)
                    open_trade = {
                        'direction': 'SHORT',
                        'entry_time': ts,
                        'entry_price': entry_p,
                        'current_sl': sl_p,
                        'target1': t1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    
    return pd.DataFrame(trades)

if __name__ == '__main__':
    print("=" * 115)
    print("🎯 PRO BACKTEST: 21 EMA + VWAP PULLBACK BOUNCE STRATEGY (5-Min Candles)")
    print("=" * 115)
    print("⚙️ Entry Logic: Trend defined by EMA21 vs VWAP | Enter on Pullback & Rejection off 21 EMA + RSI Alignment")
    print("🛡️ Risk/Reward: Stop Loss = Swing Rejection Wick | Target = 1:2 RR (50% Book) + 9 EMA Trail on Runner")
    print("=" * 115 + "\n")
    
    assets = [
        ('CL=F', 'Crude Oil (MCX 100 bbl)', 100),
        ('GC=F', 'Gold (MCX 100g unit)', 100),
        ('SI=F', 'Silver (MCX 30 kg lot)', 30),
        ('NG=F', 'Natural Gas (MCX 1250 mmBtu)', 1250),
        ('HG=F', 'Copper (MCX 2500 kg lot)', 2500),
        ('^NSEI', 'NIFTY 50 (2 Lots = 50 qty)', 50)
    ]
    
    tot_pnl = 0.0
    tot_trades = 0
    tot_wins = 0
    
    print(f"{'Asset / Commodity':<30} {'Trades':<8} {'Win Rate':<10} {'Profit Factor':<14} {'Points':<10} {'Net PnL (₹)':<15} {'Avg/Trade (₹)':<12}")
    print("-" * 115)
    
    for ticker, name, lot in assets:
        df_res = run_vwap_ema21_pullback(ticker, name, mcx_lot=lot)
        if df_res is None or len(df_res) == 0: continue
        
        wins = df_res[df_res['pnl_rs'] > 0]
        losses = df_res[df_res['pnl_rs'] <= 0]
        wr = len(wins) / len(df_res) * 100.0
        pts = df_res['total_pts'].sum()
        pnl = df_res['pnl_rs'].sum()
        gross_w = wins['pnl_rs'].sum() if len(wins) > 0 else 0
        gross_l = abs(losses['pnl_rs'].sum()) if len(losses) > 0 else 0
        pf = gross_w / gross_l if gross_l > 0 else 99.9
        
        tot_pnl += pnl
        tot_trades += len(df_res)
        tot_wins += len(wins)
        
        print(f"{name:<30} {len(df_res):<8} {wr:<9.1f}% {pf:<13.2f} {pts:<+9.2f} ₹{pnl:<+14,.2f} ₹{pnl/len(df_res):<+11,.2f}")
        
    comb_wr = (tot_wins / tot_trades * 100.0) if tot_trades > 0 else 0
    print("-" * 115)
    print(f"🏆 COMBINED PULLBACK SUMMARY: {tot_trades} Trades | Win Rate: {comb_wr:.1f}% | Total Net P&L: ₹{tot_pnl:+,.2f}")
    print("=" * 115)
