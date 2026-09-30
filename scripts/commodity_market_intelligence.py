import sys
import io
import math
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
        if df['Close'].iloc[i-1] > upper.iloc[i-1]:
            trend[i] = 1
        elif df['Close'].iloc[i-1] < lower.iloc[i-1]:
            trend[i] = -1
        else:
            trend[i] = trend[i-1]
            if trend[i] == 1 and lower.iloc[i] < lower.iloc[i-1]:
                lower.iloc[i] = lower.iloc[i-1]
            if trend[i] == -1 and upper.iloc[i] > upper.iloc[i-1]:
                upper.iloc[i] = upper.iloc[i-1]
    return pd.Series(trend, index=df.index)

def calculate_vwap(df):
    vol = df['Volume'].replace(0, 1)
    typical_price = (df['High'] + df['Low'] + df['Close']) / 3.0
    cum_vp = (typical_price * vol).cumsum()
    cum_vol = vol.cumsum()
    return cum_vp / cum_vol

def analyze_commodity(symbol, ticker_symbol, name):
    df_1h = yf.download(ticker_symbol, period='5d', interval='1h', progress=False)
    if isinstance(df_1h.columns, pd.MultiIndex):
        df_1h.columns = df_1h.columns.get_level_values(0)
    df_1h = df_1h.dropna().copy()
    
    if len(df_1h) < 15:
        return None
        
    df_1h['RSI'] = calculate_rsi(df_1h['Close'], 14)
    df_1h['SuperTrend'] = calculate_supertrend(df_1h, 10, 3.0)
    df_1h['VWAP'] = calculate_vwap(df_1h)
    df_1h['EMA20'] = df_1h['Close'].ewm(span=20, adjust=False).mean()
    df_1h['EMA50'] = df_1h['Close'].ewm(span=50, adjust=False).mean()
    
    last = df_1h.iloc[-1]
    prev = df_1h.iloc[-2]
    day_open = df_1h.iloc[0]['Open']
    
    ltp = last['Close']
    pct_change = ((ltp - day_open) / day_open) * 100.0
    rsi = last['RSI']
    st_bullish = (last['SuperTrend'] == 1)
    above_vwap = (ltp > last['VWAP'])
    ema_bullish = (last['EMA20'] > last['EMA50'])
    
    # Quantitative Score (-100 to +100)
    score = 0
    if pct_change > 0.5: score += 25
    elif pct_change < -0.5: score -= 25
    
    if rsi > 55: score += 25
    elif rsi < 45: score -= 25
    
    if above_vwap: score += 25
    else: score -= 25
    
    if st_bullish and ema_bullish: score += 25
    elif not st_bullish and not ema_bullish: score -= 25
    
    if score >= 50:
        bias = "🟢 STRONG BULLISH"
    elif score > 0:
        bias = "🟢 MILD BULLISH"
    elif score <= -50:
        bias = "🔴 STRONG BEARISH"
    elif score < 0:
        bias = "🔴 MILD BEARISH"
    else:
        bias = "⚪ NEUTRAL / CHOPPY"
        
    return {
        'name': name,
        'symbol': symbol,
        'ticker': ticker_symbol,
        'ltp': ltp,
        'pct_change': pct_change,
        'rsi': rsi,
        'vwap': last['VWAP'],
        'above_vwap': above_vwap,
        'st_bullish': st_bullish,
        'ema_bullish': ema_bullish,
        'score': score,
        'bias': bias,
        'support': df_1h['Low'].tail(24).min(),
        'resistance': df_1h['High'].tail(24).max()
    }

def get_macro_drivers():
    res = {}
    for name, sym in [('DXY (Dollar Index)', 'DX-Y.NYB'), ('US 10Y Yield', '^TNX'), ('USD/INR', 'USDINR=X')]:
        try:
            d = yf.download(sym, period='3d', interval='1h', progress=False)
            if isinstance(d.columns, pd.MultiIndex):
                d.columns = d.columns.get_level_values(0)
            if len(d) >= 2:
                ltp = d['Close'].iloc[-1]
                prev = d['Close'].iloc[-2]
                pct = ((ltp - prev) / prev) * 100.0
                res[name] = (ltp, pct)
        except Exception:
            pass
    return res

def generate_report():
    print("=" * 85)
    print("🌍 DAILY COMMODITY DIRECTION & INTER-MARKET INTELLIGENCE REPORT")
    print(f"⏰ Generated: {datetime.datetime.now().strftime('%d-%b-%Y %H:%M:%S IST')}")
    print("=" * 85)
    
    macro = get_macro_drivers()
    print("\n🌐 1. GLOBAL MACRO DRIVERS:")
    for k, v in macro.items():
        print(f"  • {k:<22}: {v[0]:.2f} ({v[1]:+5.2f}%)")
        
    gold = analyze_commodity('GOLD', 'GC=F', 'Gold (Bullion)')
    silver = analyze_commodity('SILVER', 'SI=F', 'Silver')
    crude = analyze_commodity('CRUDEOIL', 'CL=F', 'Crude Oil (WTI/MCX)')
    
    if gold and silver:
        gsr = gold['ltp'] / silver['ltp']
        print(f"  • Gold/Silver Ratio (GSR): {gsr:.2f} (", end="")
        if gsr > 85: print("Defensive / Risk-off bias - Gold leading)")
        elif gsr < 75: print("Industrial / Risk-on expansion - Silver leading)")
        else: print("Balanced Bullion Range)")
        
    print("\n📊 2. COMMODITY DIRECTIONAL ASSESSMENT:")
    print("-" * 85)
    
    for c in [gold, silver, crude]:
        if not c: continue
        print(f"📦 {c['name'].upper()} ({c['symbol']}):")
        print(f"  • Bias / Direction:       {c['bias']} (Score: {c['score']:+d}/100)")
        print(f"  • Spot LTP:                ${c['ltp']:.2f} ({c['pct_change']:+5.2f}% on day)")
        print(f"  • Intraday VWAP:           ${c['vwap']:.2f} (Status: {'Above VWAP ✅' if c['above_vwap'] else 'Below VWAP ❌'})")
        print(f"  • 1-Hour RSI(14):          {c['rsi']:.1f}")
        print(f"  • Trend Structure:         {'SuperTrend Bullish (Green)' if c['st_bullish'] else 'SuperTrend Bearish (Red)'} | {'20 > 50 EMA' if c['ema_bullish'] else '20 < 50 EMA'}")
        print(f"  • 24-Hour Key Levels:      Support: ${c['support']:.2f} | Resistance: ${c['resistance']:.2f}")
        print("-" * 85)
        
    print("\n🎯 3. ACTIONABLE PLAYBOOK & PROMPT SYNTHESIS:")
    if gold and crude:
        print(f"  • Gold Strategy:   {'BUY Dips towards VWAP' if 'BULLISH' in gold['bias'] else 'SELL Rallies towards VWAP' if 'BEARISH' in gold['bias'] else 'Rangebound / Scalp Key S/R'}")
        print(f"  • Silver Strategy: {'BUY Breakouts above 24h High' if 'BULLISH' in silver['bias'] else 'SELL Breakdowns below 24h Low' if 'BEARISH' in silver['bias'] else 'Rangebound / Neutral'}")
        print(f"  • Crude Strategy:  {'LONG on VWAP bounce with London/NY session volume' if 'BULLISH' in crude['bias'] else 'SHORT on failure at VWAP / Resistance' if 'BEARISH' in crude['bias'] else 'Wait for US Open inventory clarity'}")
    print("=" * 85)

if __name__ == '__main__':
    generate_report()
