import sys
import io
import math
import calendar
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def norm_cdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))

def bs_call_price(S, K, T, r, sigma):
    if T <= 0.0001:
        return max(0.0, S - K)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return S * norm_cdf(d1) - K * math.exp(-r * T) * norm_cdf(d2)

def bs_put_price(S, K, T, r, sigma):
    if T <= 0.0001:
        return max(0.0, K - S)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return K * math.exp(-r * T) * norm_cdf(-d2) - S * norm_cdf(-d1)

def get_monthly_expiry(year, month):
    cal = calendar.monthcalendar(year, month)
    thursdays = [week[calendar.THURSDAY] for week in cal if week[calendar.THURSDAY] != 0]
    return datetime.date(year, month, thursdays[-1])

def get_rollover_date(year, month):
    cal = calendar.monthcalendar(year, month)
    wednesdays = [week[calendar.WEDNESDAY] for week in cal if week[calendar.WEDNESDAY] != 0]
    return datetime.date(year, month, wednesdays[-2] if len(wednesdays) >= 2 else wednesdays[-1])

def get_next_month(year, month):
    return (year + 1, 1) if month == 12 else (year, month + 1)

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
    
    st = np.zeros(len(df))
    trend = np.ones(len(df)) # 1: Bullish, -1: Bearish
    
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

def select_spread_strikes(spot, spread_type, expiry_date, current_date, iv=0.135, r=0.065):
    days_to_exp = max(0.5, (expiry_date - current_date).days)
    T = days_to_exp / 365.0
    max_width = spot * 0.025
    base_strike = round(spot / 100.0) * 100
    candidates = []
    
    if spread_type == 'BEAR_CALL_SPREAD':
        for short_k in range(int(base_strike) + 100, int(base_strike) + 1500, 100):
            short_prem = bs_call_price(spot, short_k, T, r, iv)
            if short_prem < 80: break
            for hedge_k in range(short_k + 100, short_k + int(max_width) + 100, 100):
                if (hedge_k - short_k) > max_width: break
                hedge_prem = bs_call_price(spot, hedge_k, T, r, iv)
                net_credit = short_prem - hedge_prem
                if 90.0 <= net_credit <= 140.0:
                    candidates.append({
                        'short_strike': short_k, 'hedge_strike': hedge_k,
                        'short_prem': short_prem, 'hedge_prem': hedge_prem,
                        'net_credit': net_credit, 'distance': short_k - spot
                    })
    else:
        for short_k in range(int(base_strike) - 100, int(base_strike) - 1500, -100):
            short_prem = bs_put_price(spot, short_k, T, r, iv)
            if short_prem < 80: break
            for hedge_k in range(short_k - 100, short_k - int(max_width) - 100, -100):
                if (short_k - hedge_k) > max_width: break
                hedge_prem = bs_put_price(spot, hedge_k, T, r, iv)
                net_credit = short_prem - hedge_prem
                if 90.0 <= net_credit <= 140.0:
                    candidates.append({
                        'short_strike': short_k, 'hedge_strike': hedge_k,
                        'short_prem': short_prem, 'hedge_prem': hedge_prem,
                        'net_credit': net_credit, 'distance': spot - short_k
                    })

    if candidates:
        candidates.sort(key=lambda x: x['distance'], reverse=True)
        return candidates[0]
    
    # Fallback
    if spread_type == 'BEAR_CALL_SPREAD':
        sk = int(base_strike) + 200
        hk = sk + 400
        sp = bs_call_price(spot, sk, T, r, iv)
        hp = bs_call_price(spot, hk, T, r, iv)
    else:
        sk = int(base_strike) - 200
        hk = sk - 400
        sp = bs_put_price(spot, sk, T, r, iv)
        hp = bs_put_price(spot, hk, T, r, iv)
    return {'short_strike': sk, 'hedge_strike': hk, 'short_prem': sp, 'hedge_prem': hp, 'net_credit': sp - hp, 'distance': 200}

def run_simulation(df_1h, df_daily, trend_mode='NONE', tp_pct=0.0, num_lots=2, lot_size=25, capital=150000.0):
    r = 0.065
    iv = 0.135
    position = None
    active_spread = None
    trades = []
    
    # Pre-map daily trend onto 1h bars
    daily_trends = {}
    for idx, row in df_daily.iterrows():
        dt = idx.date()
        if trend_mode == 'DAILY_EMA50':
            t = 1 if row['Close'] > row['Daily_EMA50'] else -1
        elif trend_mode == 'DAILY_EMA20_50':
            t = 1 if row['Daily_EMA20'] > row['Daily_EMA50'] else -1
        elif trend_mode == 'DAILY_SUPERTREND_10_2':
            t = row['Daily_ST_10_2']
        elif trend_mode == 'DAILY_SUPERTREND_10_3':
            t = row['Daily_ST_10_3']
        elif trend_mode == 'HOURLY_EMA200':
            t = None # Will evaluate per hour
        else:
            t = 0 # No filter
        daily_trends[dt] = t

    for i in range(14, len(df_1h)):
        ts = df_1h.index[i]
        curr_date = ts.date()
        spot = df_1h['Close'].iloc[i]
        rsi = df_1h['RSI'].iloc[i]
        hour = ts.hour
        
        # Determine higher timeframe trend
        if trend_mode == 'HOURLY_EMA200':
            trend = 1 if spot > df_1h['Hourly_EMA200'].iloc[i] else -1
        else:
            # Look up previous day close trend
            prev_dates = [d for d in daily_trends.keys() if d < curr_date]
            if prev_dates:
                trend = daily_trends.get(prev_dates[-1], 0)
            else:
                trend = 0

        if curr_date.day <= 15:
            exp_year, exp_month = curr_date.year, curr_date.month
        else:
            exp_year, exp_month = get_next_month(curr_date.year, curr_date.month)
        
        target_expiry = get_monthly_expiry(exp_year, exp_month)
        rollover_date = get_rollover_date(exp_year, exp_month)

        if position is not None and active_spread is not None:
            active_expiry = active_spread['expiry']
            active_rollover = active_spread['rollover_date']
            days_to_exp = max(0.001, (active_expiry - curr_date).days + (15.0 - hour) / 24.0)
            T_now = days_to_exp / 365.0
            
            if active_spread['type'] == 'BEAR_CALL_SPREAD':
                current_short_p = bs_call_price(spot, active_spread['short_strike'], T_now, r, iv)
                current_hedge_p = bs_call_price(spot, active_spread['hedge_strike'], T_now, r, iv)
            else:
                current_short_p = bs_put_price(spot, active_spread['short_strike'], T_now, r, iv)
                current_hedge_p = bs_put_price(spot, active_spread['hedge_strike'], T_now, r, iv)
            
            current_spread_val = current_short_p - current_hedge_p
            entry_credit = active_spread['net_credit']

            # Check exits
            reversal = False
            if active_spread['type'] == 'BEAR_CALL_SPREAD' and rsi > 52.0:
                reversal = True
            elif active_spread['type'] == 'BULL_PUT_SPREAD' and rsi < 32.0:
                reversal = True

            # If trend flipped against us, exit
            trend_exit = False
            if trend_mode != 'NONE':
                if active_spread['type'] == 'BULL_PUT_SPREAD' and trend == -1:
                    trend_exit = True
                elif active_spread['type'] == 'BEAR_CALL_SPREAD' and trend == 1:
                    trend_exit = True

            tp_hit = False
            if tp_pct > 0 and current_spread_val <= entry_credit * (1.0 - tp_pct):
                tp_hit = True

            rollover_trigger = False
            if curr_date >= active_rollover and (hour >= 15 or curr_date > active_rollover):
                rollover_trigger = True

            expired = False
            if curr_date >= active_expiry and hour >= 15:
                expired = True

            if reversal or trend_exit or tp_hit or rollover_trigger or expired:
                exit_spread_val = max(0.0, current_spread_val)
                pnl_pts = entry_credit - exit_spread_val
                pnl_rs = pnl_pts * (num_lots * lot_size)
                trades.append({'pnl_pts': pnl_pts, 'pnl_rs': pnl_rs, 'exit_time': ts})
                
                # Check if we should re-enter
                if rollover_trigger:
                    next_y, next_m = get_next_month(active_expiry.year, active_expiry.month)
                    next_expiry = get_monthly_expiry(next_y, next_m)
                    next_rollover = get_rollover_date(next_y, next_m)
                    spread_info = select_spread_strikes(spot, active_spread['type'], next_expiry, curr_date, iv, r)
                    active_spread = {
                        'type': active_spread['type'], 'entry_time': ts, 'entry_spot': spot,
                        'short_strike': spread_info['short_strike'], 'hedge_strike': spread_info['hedge_strike'],
                        'expiry': next_expiry, 'rollover_date': next_rollover, 'net_credit': spread_info['net_credit']
                    }
                    continue
                else:
                    position = None
                    active_spread = None

        if position is None:
            spread_type = None
            if rsi < 32.0:
                if trend_mode == 'NONE' or trend == -1 or trend == 0:
                    spread_type = 'BEAR_CALL_SPREAD'
            elif rsi > 52.0:
                if trend_mode == 'NONE' or trend == 1 or trend == 0:
                    spread_type = 'BULL_PUT_SPREAD'

            if spread_type is not None:
                spread_info = select_spread_strikes(spot, spread_type, target_expiry, curr_date, iv, r)
                position = spread_type
                active_spread = {
                    'type': spread_type, 'entry_time': ts, 'entry_spot': spot,
                    'short_strike': spread_info['short_strike'], 'hedge_strike': spread_info['hedge_strike'],
                    'expiry': target_expiry, 'rollover_date': rollover_date, 'net_credit': spread_info['net_credit']
                }

    if not trades:
        return {'trades': 0, 'pts': 0, 'win_rate': 0, 'pf': 0, 'pnl_rs': 0, 'max_dd': 0, 'cagr': 0}

    tdf = pd.DataFrame(trades)
    total_pts = tdf['pnl_pts'].sum()
    total_pnl = tdf['pnl_rs'].sum()
    win_rate = (tdf['pnl_pts'] > 0).mean() * 100
    gross_p = tdf[tdf['pnl_pts'] > 0]['pnl_rs'].sum() if (tdf['pnl_pts'] > 0).any() else 0
    gross_l = abs(tdf[tdf['pnl_pts'] <= 0]['pnl_rs'].sum()) if (tdf['pnl_pts'] <= 0).any() else 0
    pf = gross_p / gross_l if gross_l > 0 else 99.9
    tdf['cum'] = tdf['pnl_rs'].cumsum()
    max_dd = (tdf['cum'] - tdf['cum'].cummax()).min()
    years = 2.94
    cagr = (((capital + total_pnl) / capital) ** (1.0 / years) - 1.0) * 100

    return {
        'trades': len(tdf),
        'win_rate': win_rate,
        'pf': pf,
        'pts': total_pts,
        'pnl_rs': total_pnl,
        'max_dd': max_dd,
        'cagr': cagr
    }

if __name__ == '__main__':
    df_1h = yf.download('^NSEI', period='730d', interval='1h', progress=False)
    if isinstance(df_1h.columns, pd.MultiIndex):
        df_1h.columns = df_1h.columns.get_level_values(0)
    df_1h = df_1h.dropna().copy()
    df_1h['RSI'] = calculate_rsi(df_1h['Close'], period=14)
    df_1h['Hourly_EMA200'] = df_1h['Close'].ewm(span=200, adjust=False).mean()
    df_1h = df_1h.dropna().copy()

    df_daily = yf.download('^NSEI', period='1000d', interval='1d', progress=False)
    if isinstance(df_daily.columns, pd.MultiIndex):
        df_daily.columns = df_daily.columns.get_level_values(0)
    df_daily = df_daily.dropna().copy()
    df_daily['Daily_EMA20'] = df_daily['Close'].ewm(span=20, adjust=False).mean()
    df_daily['Daily_EMA50'] = df_daily['Close'].ewm(span=50, adjust=False).mean()
    df_daily['Daily_ST_10_2'] = calculate_supertrend(df_daily, 10, 2.0)
    df_daily['Daily_ST_10_3'] = calculate_supertrend(df_daily, 10, 3.0)

    print("=" * 115)
    print("🚀 OPTION C BACKTEST: TREND-FILTERED & OPTIMIZED RSI 52/32 SPREAD STRATEGY")
    print("=" * 115)
    print(f"{'Strategy Variant':<36} {'Trades':<8} {'Win Rate':<10} {'PF':<6} {'Points':<10} {'Net PnL (₹)':<14} {'Max DD (₹)':<12} {'CAGR %':<8}")
    print("-" * 115)

    variants = [
        ("Base (Pure 52/32 - No Filter)", 'NONE', 0.0),
        ("Variant 1: Hourly 200 EMA Filter", 'HOURLY_EMA200', 0.0),
        ("Variant 2: Daily 50 EMA Filter", 'DAILY_EMA50', 0.0),
        ("Variant 3: Daily 20/50 EMA Cross Filter", 'DAILY_EMA20_50', 0.0),
        ("Variant 4: Daily SuperTrend (10, 3)", 'DAILY_SUPERTREND_10_3', 0.0),
        ("Variant 5: Daily SuperTrend (10, 2)", 'DAILY_SUPERTREND_10_2', 0.0),
        ("Variant 6: Daily EMA50 + 80% Profit Target", 'DAILY_EMA50', 0.80),
        ("Variant 7: Daily ST(10,2) + 80% Profit Target", 'DAILY_SUPERTREND_10_2', 0.80),
        ("Variant 8: Daily ST(10,3) + 75% Profit Target", 'DAILY_SUPERTREND_10_3', 0.75),
    ]

    for name, mode, tp in variants:
        res = run_simulation(df_1h, df_daily, trend_mode=mode, tp_pct=tp, num_lots=2, lot_size=25, capital=150000.0)
        print(f"{name:<36} {res['trades']:<8} {res['win_rate']:<9.1f}% {res['pf']:<5.2f} {res['pts']:<+9.1f} ₹{res['pnl_rs']:<+13,.0f} ₹{res['max_dd']:<11,.0f} {res['cagr']:<+7.2f}%")
    print("=" * 115)
