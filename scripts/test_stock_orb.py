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

def test_stock_orb(symbols=['COFORGE.NS', 'PERSISTENT.NS', 'SUNTV.NS', 'PRESTIGE.NS', 'MUTHOOTFIN.NS', 'LAURUSLABS.NS']):
    all_trades = []
    for sym in symbols:
        df_5m = yf.download(sym, period='60d', interval='5m', progress=False)
        if isinstance(df_5m.columns, pd.MultiIndex):
            df_5m.columns = df_5m.columns.get_level_values(0)
        df_5m = df_5m.dropna().copy()
        if len(df_5m) < 100: continue
        df_5m['RSI'] = calculate_rsi(df_5m['Close'], 14)
        df_5m['VWAP'] = calculate_intraday_vwap(df_5m)
        
        dates = np.unique(df_5m.index.date)
        for session_date in dates:
            day_bars = df_5m[df_5m.index.date == session_date].copy()
            if len(day_bars) < 20: continue
            
            bars_0915_0945 = day_bars.between_time('09:15', '09:40')
            if len(bars_0915_0945) < 5: continue
            
            bar_0945 = bars_0915_0945.iloc[-1]
            spot_0945 = bar_0945['Close']
            open_0915 = bars_0915_0945.iloc[0]['Open']
            vwap_0945 = bar_0945['VWAP']
            rsi_0945 = bar_0945['RSI']
            
            pct_0945 = ((spot_0945 - open_0915) / open_0915) * 100.0
            if spot_0945 > vwap_0945 and pct_0945 > 0.50 and rsi_0945 > 55:
                bias = 'LONG'
            elif spot_0945 < vwap_0945 and pct_0945 < -0.50 and rsi_0945 < 45:
                bias = 'SHORT'
            else:
                continue
                
            bars_orb = day_bars.between_time('09:45', '10:10')
            if len(bars_orb) < 5: continue
            
            orb_high = bars_orb['High'].max()
            orb_low = bars_orb['Low'].min()
            
            execution_bars = day_bars.between_time('10:15', '15:00')
            position = None
            entry_price = 0.0
            sl_price = 0.0
            target_price = 0.0
            
            for idx, bar in execution_bars.iterrows():
                c = bar['Close']
                h = bar['High']
                l = bar['Low']
                vwap = bar['VWAP']
                t_str = idx.strftime('%H:%M')
                
                if position is None:
                    if bias == 'LONG' and c > orb_high and c > vwap and t_str <= '12:30':
                        position = 'LONG'
                        entry_price = c
                        sl_price = orb_low
                        risk = entry_price - sl_price
                        if risk <= 0 or risk > entry_price * 0.02:
                            risk = entry_price * 0.0075
                            sl_price = entry_price - risk
                        target_price = entry_price + (risk * 2.0)
                    elif bias == 'SHORT' and c < orb_low and c < vwap and t_str <= '12:30':
                        position = 'SHORT'
                        entry_price = c
                        sl_price = orb_high
                        risk = sl_price - entry_price
                        if risk <= 0 or risk > entry_price * 0.02:
                            risk = entry_price * 0.0075
                            sl_price = entry_price + risk
                        target_price = entry_price - (risk * 2.0)
                else:
                    exit_price = 0.0
                    if position == 'LONG':
                        if l <= sl_price: exit_price = sl_price
                        elif h >= target_price: exit_price = target_price
                        elif t_str >= '15:00': exit_price = c
                    elif position == 'SHORT':
                        if h >= sl_price: exit_price = sl_price
                        elif l <= target_price: exit_price = target_price
                        elif t_str >= '15:00': exit_price = c
                        
                    if exit_price > 0:
                        pnl_pct = ((exit_price - entry_price) / entry_price * 100.0) if position == 'LONG' else ((entry_price - exit_price) / entry_price * 100.0)
                        all_trades.append({'symbol': sym, 'bias': bias, 'pnl_pct': pnl_pct})
                        break
                        
    return pd.DataFrame(all_trades)

if __name__ == '__main__':
    df = test_stock_orb()
    if len(df) > 0:
        wins = df[df['pnl_pct'] > 0]
        losses = df[df['pnl_pct'] <= 0]
        wr = len(wins) / len(df) * 100
        gross_w = wins['pnl_pct'].sum()
        gross_l = abs(losses['pnl_pct'].sum())
        pf = gross_w / gross_l if gross_l > 0 else 99.9
        print(f"Stock Sector Leaders ORB (10:15 Breakout) -> Trades: {len(df)}, WinRate: {wr:.1f}%, ProfitFactor: {pf:.2f}, Total % Gain: {df['pnl_pct'].sum():+.2f}%")
