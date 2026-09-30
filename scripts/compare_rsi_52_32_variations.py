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

def evaluate_variation(df, rsi_upper=52, rsi_lower=32, profit_target_pct=0.0, sl_multiplier=0.0, num_lots=2, lot_size=25):
    r = 0.065
    iv = 0.135
    position = None
    active_spread = None
    trades = []

    for i in range(14, len(df)):
        ts = df.index[i]
        curr_date = ts.date()
        spot = df['Close'].iloc[i]
        rsi = df['RSI'].iloc[i]
        hour = ts.hour

        if curr_date.day <= 15:
            exp_year, exp_month = curr_date.year, curr_date.month
        else:
            exp_year, exp_month = get_next_month(curr_date.year, curr_date.month)
        
        target_expiry = get_monthly_expiry(exp_year, exp_month)
        rollover_date = get_rollover_date(exp_year, exp_month)

        if position is not None and active_spread is not None:
            active_expiry = active_spread['expiry']
            active_rollover = active_spread['rollover_date']
            days_to_exp = max(0.001, (active_expiry - curr_date).days + (15.5 - hour) / 24.0)
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
            if active_spread['type'] == 'BEAR_CALL_SPREAD' and rsi > rsi_upper:
                reversal = True
                exit_reason = "RSI_FLIP_BULLISH"
            elif active_spread['type'] == 'BULL_PUT_SPREAD' and rsi < rsi_lower:
                reversal = True
                exit_reason = "RSI_FLIP_BEARISH"

            tp_hit = False
            if profit_target_pct > 0 and current_spread_val <= entry_credit * (1.0 - profit_target_pct):
                tp_hit = True
                exit_reason = f"PROFIT_TARGET_{int(profit_target_pct*100)}%"

            sl_hit = False
            if sl_multiplier > 0 and current_spread_val >= entry_credit * sl_multiplier:
                sl_hit = True
                exit_reason = f"STOP_LOSS_{sl_multiplier}x"

            rollover_trigger = False
            if curr_date >= active_rollover and (hour >= 15 or curr_date > active_rollover):
                rollover_trigger = True
                exit_reason = "EXPIRY_ROLLOVER"

            expired = False
            if curr_date >= active_expiry and hour >= 15:
                expired = True
                exit_reason = "EXPIRY_SETTLEMENT"

            if reversal or tp_hit or sl_hit or rollover_trigger or expired:
                exit_spread_val = max(0.0, current_spread_val)
                pnl_pts = entry_credit - exit_spread_val
                pnl_rs = pnl_pts * (num_lots * lot_size)
                
                trades.append({
                    'pnl_pts': pnl_pts,
                    'pnl_rs': pnl_rs,
                    'exit_reason': exit_reason
                })
                
                if reversal:
                    new_type = 'BULL_PUT_SPREAD' if active_spread['type'] == 'BEAR_CALL_SPREAD' else 'BEAR_CALL_SPREAD'
                    spread_info = select_spread_strikes(spot, new_type, target_expiry, curr_date, iv, r)
                    position = new_type
                    active_spread = {
                        'type': new_type, 'entry_time': ts, 'entry_spot': spot,
                        'short_strike': spread_info['short_strike'], 'hedge_strike': spread_info['hedge_strike'],
                        'expiry': target_expiry, 'rollover_date': rollover_date, 'net_credit': spread_info['net_credit']
                    }
                    continue
                elif rollover_trigger:
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
            if rsi < rsi_lower:
                spread_type = 'BEAR_CALL_SPREAD'
            elif rsi > rsi_upper:
                spread_type = 'BULL_PUT_SPREAD'
            else:
                spread_type = None

            if spread_type is not None:
                spread_info = select_spread_strikes(spot, spread_type, target_expiry, curr_date, iv, r)
                position = spread_type
                active_spread = {
                    'type': spread_type, 'entry_time': ts, 'entry_spot': spot,
                    'short_strike': spread_info['short_strike'], 'hedge_strike': spread_info['hedge_strike'],
                    'expiry': target_expiry, 'rollover_date': rollover_date, 'net_credit': spread_info['net_credit']
                }

    if not trades:
        return {'trades': 0, 'pts': 0, 'win_rate': 0, 'pf': 0, 'pnl_rs': 0, 'max_dd': 0}

    tdf = pd.DataFrame(trades)
    wins = tdf[tdf['pnl_pts'] > 0]
    losses = tdf[tdf['pnl_pts'] <= 0]
    win_rate = len(wins) / len(tdf) * 100.0
    total_pts = tdf['pnl_pts'].sum()
    total_pnl_rs = tdf['pnl_rs'].sum()
    gross_p = wins['pnl_rs'].sum() if len(wins) > 0 else 0
    gross_l = abs(losses['pnl_rs'].sum()) if len(losses) > 0 else 0
    pf = (gross_p / gross_l) if gross_l > 0 else 99.9
    tdf['cum'] = tdf['pnl_rs'].cumsum()
    max_dd = (tdf['cum'] - tdf['cum'].cummax()).min()

    return {
        'trades': len(tdf),
        'win_rate': win_rate,
        'pf': pf,
        'pts': total_pts,
        'pnl_rs': total_pnl_rs,
        'max_dd': max_dd
    }

if __name__ == '__main__':
    df = yf.download('^NSEI', period='730d', interval='1h', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    df['RSI'] = calculate_rsi(df['Close'], period=14)
    df = df.dropna().copy()

    print("=" * 100)
    print("🔬 COMPARATIVE PARAMETER GRID ANALYSIS (3-Year 1-Hour Nifty Data)")
    print("=" * 100)
    print(f"{'Variation':<32} {'Trades':<8} {'Win Rate':<10} {'PF':<6} {'Points':<10} {'Net PnL (₹)':<15} {'Max DD (₹)':<12}")
    print("-" * 100)

    configs = [
        ("Base (RSI 52/32 Pure Flip)", 52, 32, 0.0, 0.0),
        ("RSI 50/30 Pure Flip", 50, 30, 0.0, 0.0),
        ("RSI 55/35 Pure Flip", 55, 35, 0.0, 0.0),
        ("Base + 75% Profit Target", 52, 32, 0.75, 0.0),
        ("Base + 85% Profit Target", 52, 32, 0.85, 0.0),
        ("Base + 2.0x SL Cap", 52, 32, 0.0, 2.0),
        ("Base + 80% TP + 2.0x SL", 52, 32, 0.80, 2.0),
    ]

    for name, up, lo, tp, sl in configs:
        res = evaluate_variation(df, rsi_upper=up, rsi_lower=lo, profit_target_pct=tp, sl_multiplier=sl)
        print(f"{name:<32} {res['trades']:<8} {res['win_rate']:<9.1f}% {res['pf']:<5.2f} {res['pts']:<+9.1f} ₹{res['pnl_rs']:<+14,.2f} ₹{res['max_dd']:<11,.2f}")
    print("=" * 100)
