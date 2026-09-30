import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_ema(series, span):
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

def calculate_supertrend(df, period=10, multiplier=3.0):
    hl2 = (df['High'] + df['Low']) / 2.0
    tr1 = df['High'] - df['Low']
    tr2 = (df['High'] - df['Close'].shift(1)).abs()
    tr3 = (df['Low'] - df['Close'].shift(1)).abs()
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1.0/period, adjust=False).mean()
    upper = hl2 + multiplier * atr
    lower = hl2 - multiplier * atr
    trend = np.ones(len(df))
    for i in range(1, len(df)):
        if df['Close'].iloc[i-1] > upper.iloc[i-1]: trend[i] = 1
        elif df['Close'].iloc[i-1] < lower.iloc[i-1]: trend[i] = -1
        else:
            trend[i] = trend[i-1]
            if trend[i] == 1 and lower.iloc[i] < lower.iloc[i-1]: lower.iloc[i] = lower.iloc[i-1]
            if trend[i] == -1 and upper.iloc[i] > upper.iloc[i-1]: upper.iloc[i] = upper.iloc[i-1]
    return pd.Series(trend, index=df.index)

def fetch_macro():
    dxy = yf.download('DX-Y.NYB', period='60d', interval='1h', progress=False)
    if isinstance(dxy.columns, pd.MultiIndex): dxy.columns = dxy.columns.get_level_values(0)
    dxy = dxy.dropna().copy()
    dxy['VWAP'] = calculate_intraday_vwap(dxy)
    
    usdinr = yf.download('USDINR=X', period='60d', interval='1h', progress=False)
    if isinstance(usdinr.columns, pd.MultiIndex): usdinr.columns = usdinr.columns.get_level_values(0)
    usdinr = usdinr.dropna().copy()
    usdinr['EMA20'] = calculate_ema(usdinr['Close'], 20)
    
    return dxy, usdinr

def run_techno_funda_backtest():
    print("=" * 115)
    print("💎🌍 TECHNO-FUNDA COMMODITY SESSION BREAKOUT & MACRO STRATEGY (VANDANA BHARTI MODEL)")
    print("=" * 115)
    print("⚙️ Core Principles:")
    print("   1. Macro Alignment: USD/INR Tailwind + Inverse Dollar Index (DXY) Trend Filter")
    print("   2. Session Windows: Asian Base Range (09:00-13:00) -> London Session Breakout (13:30-17:30) & US Open (18:00+)")
    print("   3. Technical Confluence: 15m RSI(14) > 55/< 45 + Spot vs Intraday VWAP + 15m SuperTrend Trail")
    print("   4. Risk/Reward: Stop Loss = Breakout Bar Low/High | Target 1:2 RR (50% Book) + SuperTrend Trail")
    print("=" * 115 + "\n")

    dxy_df, usdinr_df = fetch_macro()

    commodities = [
        ('CL=F', 'CRUDEOIL', 'Crude Oil (MCX 100 bbl)', 100, 0.05, 0.35),
        ('GC=F', 'GOLD', 'Gold (MCX 100g unit)', 100, 0.10, 0.20),
        ('SI=F', 'SILVER', 'Silver (MCX 30 kg lot)', 30, 0.01, 0.30),
        ('NG=F', 'NATURALGAS', 'Natural Gas (MCX 1250 mmBtu)', 1250, 0.01, 0.50),
        ('HG=F', 'COPPER', 'Copper (MCX 2500 kg lot)', 2500, 0.005, 0.25)
    ]

    all_results = []
    
    for ticker, symbol, name, mcx_lot, tick_size, min_sl_pct in commodities:
        df_15m = yf.download(ticker, period='60d', interval='15m', progress=False)
        if isinstance(df_15m.columns, pd.MultiIndex):
            df_15m.columns = df_15m.columns.get_level_values(0)
        df_15m = df_15m.dropna().copy()
        if len(df_15m) < 100: continue
        
        df_15m['VWAP'] = calculate_intraday_vwap(df_15m)
        df_15m['RSI'] = calculate_rsi(df_15m['Close'], 14)
        df_15m['SuperTrend'] = calculate_supertrend(df_15m, 10, 3.0)
        
        dates = np.unique(df_15m.index.date)
        eval_dates = dates[-30:] if len(dates) >= 30 else dates
        
        trades = []
        
        for session_date in eval_dates:
            day_bars = df_15m[df_15m.index.date == session_date].copy()
            if len(day_bars) < 15: continue
            
            # 1. Asian Base Range (09:00 to 13:00 IST)
            asian_bars = day_bars.between_time('09:00', '13:00')
            if len(asian_bars) < 4: continue
            
            asian_high = asian_bars['High'].max()
            asian_low = asian_bars['Low'].min()
            asian_range = asian_high - asian_low
            
            # 2. Macro Check for Day
            dxy_day = dxy_df[dxy_df.index.date == session_date]
            usdinr_day = usdinr_df[usdinr_df.index.date == session_date]
            
            dxy_bullish = False
            if len(dxy_day) > 0:
                dxy_last = dxy_day.iloc[-1]
                dxy_bullish = (dxy_last['Close'] > dxy_last['VWAP'])
                
            usdinr_bullish = True
            if len(usdinr_day) > 0:
                usdinr_last = usdinr_day.iloc[-1]
                usdinr_bullish = (usdinr_last['Close'] >= usdinr_last['EMA20'])

            # 3. Active Execution Windows: London (13:30-17:30) & US Session (18:00-22:30)
            exec_bars = day_bars.between_time('13:30', '22:30')
            if len(exec_bars) < 6: continue
            
            position = None
            open_trade = None
            
            for idx in range(len(exec_bars)):
                bar = exec_bars.iloc[idx]
                ts = exec_bars.index[idx]
                c = bar['Close']
                h = bar['High']
                l = bar['Low']
                vwap = bar['VWAP']
                rsi = bar['RSI']
                st = bar['SuperTrend']
                time_str = ts.strftime('%H:%M')
                
                # Crude EIA Avoidance (Wednesdays 19:45 to 20:15)
                is_wed = (session_date.weekday() == 2)
                if symbol == 'CRUDEOIL' and is_wed and '19:45' <= time_str <= '20:15':
                    continue
                
                # A. Manage Position
                if position is not None:
                    p_dir = open_trade['direction']
                    entry_p = open_trade['entry_price']
                    curr_sl = open_trade['current_sl']
                    t1 = open_trade['target1']
                    partial_booked = open_trade['partial_booked']
                    
                    exit_price = 0.0
                    exit_reason = ""
                    
                    if p_dir == 'LONG':
                        if l <= curr_sl:
                            exit_price = curr_sl
                            exit_reason = "TRAILING_COST_SL" if partial_booked else "STOP_LOSS_HIT"
                        elif not partial_booked and h >= t1:
                            open_trade['partial_booked'] = True
                            open_trade['partial_pnl'] = (t1 - entry_p) * 0.5
                            open_trade['current_sl'] = entry_p # Move to breakeven
                        elif partial_booked and st == -1:
                            exit_price = c
                            exit_reason = "SUPERTREND_TRAIL_EXIT"
                        elif idx == len(exec_bars) - 1 or time_str >= '22:30':
                            exit_price = c
                            exit_reason = "SESSION_EOD_EXIT"
                    else: # SHORT
                        if h >= curr_sl:
                            exit_price = curr_sl
                            exit_reason = "TRAILING_COST_SL" if partial_booked else "STOP_LOSS_HIT"
                        elif not partial_booked and l <= t1:
                            open_trade['partial_booked'] = True
                            open_trade['partial_pnl'] = (entry_p - t1) * 0.5
                            open_trade['current_sl'] = entry_p
                        elif partial_booked and st == 1:
                            exit_price = c
                            exit_reason = "SUPERTREND_TRAIL_EXIT"
                        elif idx == len(exec_bars) - 1 or time_str >= '22:30':
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
                        break # Max 1 trade per commodity per session
                        
                # B. Check Entry Signals (London / US Breakout with Techno-Funda Confluence)
                if position is None and time_str <= '21:00':
                    # Bullish Breakout Condition:
                    # 1. Close breaks above Asian High
                    # 2. Spot > VWAP & 15m RSI > 52 & Supertrend == 1
                    # 3. Macro: For Bullion, DXY not bullish
                    bullish_macro = True
                    if symbol in ['GOLD', 'SILVER'] and dxy_bullish:
                        bullish_macro = False
                        
                    if c > asian_high and c > vwap and rsi > 52 and st == 1 and bullish_macro:
                        position = 'LONG'
                        entry_p = c
                        sl_p = max(bar['Low'], entry_p * (1.0 - min_sl_pct/100.0))
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
                    # Bearish Breakdown Condition:
                    # 1. Close breaks below Asian Low
                    # 2. Spot < VWAP & 15m RSI < 48 & Supertrend == -1
                    # 3. Macro: For Bullion, DXY not bearish
                    bearish_macro = True
                    if symbol in ['GOLD', 'SILVER'] and not dxy_bullish:
                        bearish_macro = False
                        
                    elif c < asian_low and c < vwap and rsi < 48 and st == -1 and bearish_macro:
                        position = 'SHORT'
                        entry_p = c
                        sl_p = min(bar['High'], entry_p * (1.0 + min_sl_pct/100.0))
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

        tdf = pd.DataFrame(trades)
        if len(tdf) > 0:
            wins = tdf[tdf['pnl_rs'] > 0]
            losses = tdf[tdf['pnl_rs'] <= 0]
            wr = len(wins) / len(tdf) * 100.0
            tot_pnl = tdf['pnl_rs'].sum()
            tot_pts = tdf['total_pts'].sum()
            gross_w = wins['pnl_rs'].sum() if len(wins) > 0 else 0
            gross_l = abs(losses['pnl_rs'].sum()) if len(losses) > 0 else 0
            pf = gross_w / gross_l if gross_l > 0 else 99.9
            
            all_results.append({
                'name': name,
                'trades': len(tdf),
                'wins': len(wins),
                'losses': len(losses),
                'wr': wr,
                'pf': pf,
                'pts': tot_pts,
                'pnl': tot_pnl,
                'avg': tot_pnl / len(tdf)
            })

    print(f"{'Commodity':<30} {'Trades':<8} {'Win Rate':<10} {'Profit Factor':<14} {'Points':<10} {'Net PnL (₹)':<15} {'Avg/Trade (₹)':<12}")
    print("-" * 115)
    
    tot_trades_all = sum(r['trades'] for r in all_results)
    tot_wins_all = sum(r['wins'] for r in all_results)
    tot_pnl_all = sum(r['pnl'] for r in all_results)
    
    for r in all_results:
        print(f"{r['name']:<30} {r['trades']:<8} {r['wr']:<9.1f}% {r['pf']:<13.2f} {r['pts']:<+9.2f} ₹{r['pnl']:<+14,.2f} ₹{r['avg']:<+11,.2f}")
        
    print("=" * 115)
    print(f"🏆 ALL COMMODITIES COMBINED: {tot_trades_all} Trades across 30 Days | Win Rate: {(tot_wins_all/tot_trades_all)*100:.1f}% | Total Net P&L: ₹{tot_pnl_all:+,.2f}")
    print("=" * 115)

if __name__ == '__main__':
    run_techno_funda_backtest()
