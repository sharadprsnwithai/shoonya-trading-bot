import sys
import io
import yfinance as yf
import pandas as pd
import numpy as np
import datetime
import math

def norm_cdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))

def bs_price(spot, strike, t_years, r, sigma, option_type='PE'):
    if t_years <= 0.0001:
        if option_type == 'CE':
            return max(0.0, spot - strike)
        else:
            return max(0.0, strike - spot)
    sigma = max(0.08, sigma)
    d1 = (math.log(spot / strike) + (r + 0.5 * sigma ** 2) * t_years) / (sigma * math.sqrt(t_years))
    d2 = d1 - sigma * math.sqrt(t_years)
    if option_type == 'CE':
        return spot * norm_cdf(d1) - strike * math.exp(-r * t_years) * norm_cdf(d2)
    else:
        return strike * math.exp(-r * t_years) * norm_cdf(-d2) - spot * norm_cdf(-d1)

def get_nifty_data():
    df = yf.download('^NSEI', period='2y', interval='1d', progress=False)
    vix = yf.download('^INDIAVIX', period='2y', interval='1d', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    if isinstance(vix.columns, pd.MultiIndex):
        vix.columns = vix.columns.get_level_values(0)
    df['VIX'] = vix['Close'].ffill().bfill()
    df = df.dropna().copy()
    return df

def find_monthly_expiries(df):
    df['YearMonth'] = df.index.to_period('M')
    expiries = []
    for ym, group in df.groupby('YearMonth'):
        thursdays = group[group.index.dayofweek == 3]
        if len(thursdays) > 0:
            expiries.append(thursdays.index[-1])
        else:
            expiries.append(group.index[-1])
    return expiries

def test_target_pct(df, expiries, target_pct):
    lot_size = 65
    margin = 100000.0
    r = 0.07
    target_profit_rs = (target_pct / 100.0) * margin
    results = []
    start_idx = max(1, len(expiries) - 13)
    
    for i in range(start_idx, len(expiries)):
        prev_exp = expiries[i-1]
        curr_exp = expiries[i]
        cycle_df = df[(df.index > prev_exp) & (df.index <= curr_exp)]
        if len(cycle_df) < 5:
            continue
            
        entry_date = cycle_df.index[0]
        entry_spot = float(cycle_df.loc[entry_date, 'Open'])
        entry_vix = float(cycle_df.loc[entry_date, 'VIX']) / 100.0
        t_total = (curr_exp - entry_date).days / 365.0
        atm = int(round(entry_spot / 100.0) * 100.0)
        
        k1 = atm - 200
        k2 = k1 - 200
        k3 = k2 - 200
        k4 = k3 - 200
        
        p1_0 = bs_price(entry_spot, k1, t_total, r, entry_vix, 'PE')
        p2_0 = bs_price(entry_spot, k2, t_total, r, entry_vix, 'PE')
        p3_0 = bs_price(entry_spot, k3, t_total, r, entry_vix, 'PE')
        p4_0 = bs_price(entry_spot, k4, t_total, r, entry_vix, 'PE')
        
        cur_k1 = k1
        cur_p1_0 = p1_0
        cur_k2 = k2
        cur_p2_0 = p2_0
        cur_k3 = k3
        cur_p3_0 = p3_0
        cur_k4 = k4
        cur_p4_0 = p4_0
        
        adj_booked_pnl = 0.0
        upside_spread_active = False
        up_sell_k = 0
        up_buy_k = 0
        up_sell_p0 = 0.0
        up_buy_p0 = 0.0
        downside_shifted = False
        
        exit_reason = 'Expiry Settlement'
        exit_date = curr_exp
        final_pnl_rs = 0.0
        max_dd_rs = 0.0
        
        for day_idx, (d, row) in enumerate(cycle_df.iterrows()):
            cur_spot = float(row['Close'])
            cur_vix = float(row['VIX']) / 100.0
            t_rem = max(0.0001, (curr_exp - d).days) / 365.0
            
            p1 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
            p2 = bs_price(cur_spot, cur_k2, t_rem, r, cur_vix, 'PE')
            p3 = bs_price(cur_spot, cur_k3, t_rem, r, cur_vix, 'PE')
            p4 = bs_price(cur_spot, cur_k4, t_rem, r, cur_vix, 'PE')
            
            condor_pnl = (p1 - cur_p1_0) + (p4 - cur_p4_0) - (p2 - cur_p2_0) - (p3 - cur_p3_0)
            
            up_pnl = 0.0
            if upside_spread_active:
                up_s = bs_price(cur_spot, up_sell_k, t_rem, r, cur_vix, 'PE')
                up_b = bs_price(cur_spot, up_buy_k, t_rem, r, cur_vix, 'PE')
                up_pnl = (up_sell_p0 - up_s) + (up_b - up_buy_p0)
                
            total_mtm_rs = (adj_booked_pnl + condor_pnl + up_pnl) * lot_size
            if total_mtm_rs < max_dd_rs:
                max_dd_rs = total_mtm_rs
                
            # Target check
            if total_mtm_rs >= target_profit_rs:
                exit_reason = f'Target Hit (+Rs {round(total_mtm_rs)})'
                exit_date = d
                final_pnl_rs = total_mtm_rs
                break
                
            # Deep crash exit
            if cur_spot <= k4 - 100:
                exit_reason = 'Deep Crash Exit (Spot < K4)'
                exit_date = d
                final_pnl_rs = total_mtm_rs
                break
                
            # Upside spread
            if not upside_spread_active and (cur_spot >= atm + 150 or (day_idx >= 7 and cur_spot >= atm)):
                upside_spread_active = True
                up_sell_k = int(round((cur_spot - 300) / 100.0) * 100.0)
                up_buy_k = up_sell_k - 100
                up_sell_p0 = bs_price(cur_spot, up_sell_k, t_rem, r, cur_vix, 'PE')
                up_buy_p0 = bs_price(cur_spot, up_buy_k, t_rem, r, cur_vix, 'PE')
                
            # Downside shift
            if not downside_shifted and cur_spot <= cur_k2:
                downside_shifted = True
                adj_booked_pnl += (p1 - cur_p1_0)
                cur_k1 = cur_k1 - 100
                cur_p1_0 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
                
        if exit_reason == 'Expiry Settlement':
            exp_spot = float(cycle_df.loc[curr_exp, 'Close'])
            pay_1 = max(0.0, cur_k1 - exp_spot)
            pay_2 = max(0.0, cur_k2 - exp_spot)
            pay_3 = max(0.0, cur_k3 - exp_spot)
            pay_4 = max(0.0, cur_k4 - exp_spot)
            c_pay = (pay_1 - cur_p1_0) + (pay_4 - cur_p4_0) - (pay_2 - cur_p2_0) - (pay_3 - cur_p3_0)
            up_pay = 0.0
            if upside_spread_active:
                up_s_pay = max(0.0, up_sell_k - exp_spot)
                up_b_pay = max(0.0, up_buy_k - exp_spot)
                up_pay = (up_sell_p0 - up_s_pay) + (up_b_pay - up_buy_p0)
            final_pnl_rs = (adj_booked_pnl + c_pay + up_pay) * lot_size
            
        results.append({
            'Month': curr_exp.strftime('%b %Y'),
            'Nifty_Move_Pct': round(((float(cycle_df.iloc[-1]['Close']) - entry_spot) / entry_spot) * 100.0, 2),
            'Exit_Reason': exit_reason,
            'PnL_Rs': round(final_pnl_rs, 0),
            'ROI_Pct': round((final_pnl_rs / margin) * 100.0, 2),
            'Max_DD_Rs': round(max_dd_rs, 0)
        })
    return pd.DataFrame(results)

if __name__ == '__main__':
    df = get_nifty_data()
    expiries = find_monthly_expiries(df)
    
    print("=== TARGET PROFIT SENSITIVITY SWEEP (1 YEAR) ===")
    for tp in [3.0, 4.0, 5.0, 6.0, 7.0, 8.0]:
        res = test_target_pct(df, expiries, tp)
        tot = res['PnL_Rs'].sum()
        wins = (res['PnL_Rs'] > 0).sum()
        targets_hit = res['Exit_Reason'].str.contains('Target Hit').sum()
        print(f"Target {tp:.1f}% -> Total PnL: Rs {tot:,.0f} ({tot/1000:.2f}%) | Win Rate: {wins}/13 ({wins/13*100:.1f}%) | Targets Hit: {targets_hit}/13")
        
    print("\n=== MONTH-BY-MONTH BREAKDOWN FOR TARGET = 6.0% (Rs 6,000) ===")
    res6 = test_target_pct(df, expiries, 6.0)
    print(res6.to_string(index=False))
