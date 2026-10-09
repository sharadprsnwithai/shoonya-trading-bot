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

def calculate_atr(df, period=14):
    tr1 = df['High'] - df['Low']
    tr2 = (df['High'] - df['Close'].shift(1)).abs()
    tr3 = (df['Low'] - df['Close'].shift(1)).abs()
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    return tr.ewm(alpha=1.0/period, adjust=False).mean()

def run_ema21_vwap_crossover(ticker, name, timeframe='5m', mcx_lot=100, exit_mode='1_2_RR_TRAIL_EMA21'):
    df = yf.download(ticker, period='60d', interval=timeframe, progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    if len(df) < 100: return None
    
    df['EMA21'] = calculate_ema(df['Close'], 21)
    df['VWAP'] = calculate_intraday_vwap(df)
    df['ATR'] = calculate_atr(df, 14)
    df = df.dropna().copy()
    
    dates = np.unique(df.index.date)
    trades = []
    
    for session_date in dates:
        day_bars = df[df.index.date == session_date].copy()
        if len(day_bars) < 10: continue
        
        position = None
        open_trade = None
        
        for idx in range(1, len(day_bars)):
            bar = day_bars.iloc[idx]
            prev_bar = day_bars.iloc[idx - 1]
            ts = day_bars.index[idx]
            
            c = bar['Close']
            h = bar['High']
            l = bar['Low']
            ema = bar['EMA21']
            vwap = bar['VWAP']
            atr = bar['ATR'] if not np.isnan(bar['ATR']) else (c * 0.005)
            
            prev_ema = prev_bar['EMA21']
            prev_vwap = prev_bar['VWAP']
            
            # Crossover condition
            bullish_cross = (prev_ema <= prev_vwap and ema > vwap and c > vwap)
            bearish_cross = (prev_ema >= prev_vwap and ema < vwap and c < vwap)
            
            # 1. Manage Open Position
            if position is not None:
                p_dir = open_trade['direction']
                entry_p = open_trade['entry_price']
                sl_p = open_trade['current_sl']
                t1 = open_trade['target1']
                partial_booked = open_trade['partial_booked']
                
                exit_price = 0.0
                exit_reason = ""
                
                if p_dir == 'LONG':
                    # Stop Loss
                    if l <= sl_p:
                        exit_price = sl_p
                        exit_reason = "TRAILING_SL_HIT" if partial_booked else "STOP_LOSS_HIT"
                    # Target 1 (1:2 RR)
                    elif not partial_booked and h >= t1:
                        open_trade['partial_booked'] = True
                        open_trade['partial_pnl'] = (t1 - entry_p) * 0.5
                        open_trade['current_sl'] = entry_p # Move SL to Cost
                    # Trailing on 21 EMA (after partial book)
                    elif partial_booked and c < ema:
                        exit_price = c
                        exit_reason = "EMA21_TRAIL_EXIT"
                    # Opposite crossover exit
                    elif bearish_cross:
                        exit_price = c
                        exit_reason = "OPPOSITE_CROSSOVER"
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                else: # SHORT
                    if h >= sl_p:
                        exit_price = sl_p
                        exit_reason = "TRAILING_SL_HIT" if partial_booked else "STOP_LOSS_HIT"
                    elif not partial_booked and l <= t1:
                        open_trade['partial_booked'] = True
                        open_trade['partial_pnl'] = (entry_p - t1) * 0.5
                        open_trade['current_sl'] = entry_p # Move SL to Cost
                    elif partial_booked and c > ema:
                        exit_price = c
                        exit_reason = "EMA21_TRAIL_EXIT"
                    elif bullish_cross:
                        exit_price = c
                        exit_reason = "OPPOSITE_CROSSOVER"
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                        
                if exit_price > 0:
                    runner_pnl = ((exit_price - entry_p) if p_dir == 'LONG' else (entry_p - exit_price)) * (0.5 if partial_booked else 1.0)
                    total_pts = (open_trade['partial_pnl'] + runner_pnl) if partial_booked else runner_pnl
                    pnl_rs = total_pts * mcx_lot
                    
                    trades.append({
                        'date': session_date,
                        'name': name,
                        'direction': p_dir,
                        'entry_time': open_trade['entry_time'],
                        'exit_time': ts,
                        'entry_price': entry_p,
                        'exit_price': exit_price,
                        'total_pts': total_pts,
                        'pnl_rs': pnl_rs,
                        'exit_reason': exit_reason
                    })
                    position = None
                    open_trade = None
                    
            # 2. Check New Entry Signal
            if position is None:
                if bullish_cross:
                    position = 'LONG'
                    entry_p = c
                    sl_dist = max(atr * 1.5, entry_p * 0.0035)
                    sl_p = entry_p - sl_dist
                    t1 = entry_p + (sl_dist * 2.0)
                    open_trade = {
                        'direction': 'LONG',
                        'entry_time': ts,
                        'entry_price': entry_p,
                        'initial_sl': sl_p,
                        'current_sl': sl_p,
                        'target1': t1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                elif bearish_cross:
                    position = 'SHORT'
                    entry_p = c
                    sl_dist = max(atr * 1.5, entry_p * 0.0035)
                    sl_p = entry_p + sl_dist
                    t1 = entry_p - (sl_dist * 2.0)
                    open_trade = {
                        'direction': 'SHORT',
                        'entry_time': ts,
                        'entry_price': entry_p,
                        'initial_sl': sl_p,
                        'current_sl': sl_p,
                        'target1': t1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    
    return pd.DataFrame(trades)

if __name__ == '__main__':
    print("=" * 115)
    print("🚀 BACKTEST: 21 EMA + INTRADAY VWAP CROSSOVER STRATEGY")
    print("=" * 115)
    print("⚙️ Entry Rules: 21 EMA Crosses Intraday VWAP (Confirmed on Candle Close)")
    print("🛡️ Risk Management: Stop Loss = 1.5x ATR (or 0.35% min) | Target = 1:2 RR (50% Book) + 21 EMA Trail on Runner")
    print("=" * 115 + "\n")
    
    assets = [
        ('CL=F', 'Crude Oil (MCX 100 bbl)', 100),
        ('GC=F', 'Gold (MCX 100g unit)', 100),
        ('SI=F', 'Silver (MCX 30 kg lot)', 30),
        ('NG=F', 'Natural Gas (MCX 1250 mmBtu)', 1250),
        ('HG=F', 'Copper (MCX 2500 kg lot)', 2500),
        ('^NSEI', 'NIFTY 50 (2 Lots = 50 qty)', 50)
    ]
    
    for tf in ['5m', '15m']:
        print(f"📊 --- TIMEFRAME: {tf.upper()} CANDLES ---")
        print(f"{'Asset / Commodity':<30} {'Trades':<8} {'Win Rate':<10} {'Profit Factor':<14} {'Points':<10} {'Net PnL (₹)':<15} {'Avg/Trade (₹)':<12}")
        print("-" * 115)
        
        tot_pnl = 0.0
        tot_trades = 0
        tot_wins = 0
        
        for ticker, name, lot in assets:
            df_res = run_ema21_vwap_crossover(ticker, name, timeframe=tf, mcx_lot=lot)
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
        print(f"🏆 {tf.upper()} COMBINED SUMMARY: {tot_trades} Trades | Win Rate: {comb_wr:.1f}% | Total Net P&L: ₹{tot_pnl:+,.2f}\n")
    print("=" * 115)
