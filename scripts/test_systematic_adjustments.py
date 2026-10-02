import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

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

def test_systematic_adjustments():
    df = get_nifty_data()
    expiries = find_monthly_expiries(df)
    
    lot_size = 65
    margin = 100000.0
    r = 0.07
    target_profit_rs = 3000.0 # +3.0% (~₹3,000)
    
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
        
        # 4 Put Condor Strikes (200 pts width)
        k1_buy = atm - 200
        k2_sell = k1_buy - 200
        k3_sell = k2_sell - 200
        k4_buy = k3_sell - 200
        
        p1_0 = bs_price(entry_spot, k1_buy, t_total, r, entry_vix, 'PE')
        p2_0 = bs_price(entry_spot, k2_sell, t_total, r, entry_vix, 'PE')
        p3_0 = bs_price(entry_spot, k3_sell, t_total, r, entry_vix, 'PE')
        p4_0 = bs_price(entry_spot, k4_buy, t_total, r, entry_vix, 'PE')
        
        initial_net_debit_pts = (p1_0 + p4_0) - (p2_0 + p3_0)
        
        # Tracking variables
        cur_k1 = k1_buy
        cur_p1_0 = p1_0
        cur_k2 = k2_sell
        cur_p2_0 = p2_0
        cur_k3 = k3_sell
        cur_p3_0 = p3_0
        cur_k4 = k4_buy
        cur_p4_0 = p4_0
        
        adj_booked_pnl = 0.0
        upside_spread_active = False
        up_sell_k = 0
        up_buy_k = 0
        up_sell_p0 = 0.0
        up_buy_p0 = 0.0
        
        downside_rolled = False
        
        exit_reason = "Expiry Settlement"
        exit_date = curr_exp
        final_pnl_rs = 0.0
        max_dd_rs = 0.0
        
        for day_idx, (d, row) in enumerate(cycle_df.iterrows()):
            cur_spot = float(row['Close'])
            cur_vix = float(row['VIX']) / 100.0
            t_rem = max(0.0001, (curr_exp - d).days) / 365.0
            
            # Current values
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
                
            # 1. Target Profit (+3.0%)
            if total_mtm_rs >= target_profit_rs:
                exit_reason = f"Target Hit (+Rs {round(total_mtm_rs)})"
                exit_date = d
                final_pnl_rs = total_mtm_rs
                break
                
            # --- ADJUSTMENT A: UPSIDE DEBIT FINANCING ---
            # If spot moves UP +0.75% from entry, sell a 100-pt Put Credit Spread 300 pts OTM
            if not upside_spread_active and (cur_spot >= atm + 150 or (day_idx >= 7 and cur_spot >= atm)):
                upside_spread_active = True
                up_sell_k = int(round((cur_spot - 300) / 100.0) * 100.0)
                up_buy_k = up_sell_k - 100
                up_sell_p0 = bs_price(cur_spot, up_sell_k, t_rem, r, cur_vix, 'PE')
                up_buy_p0 = bs_price(cur_spot, up_buy_k, t_rem, r, cur_vix, 'PE')
                
            # --- ADJUSTMENT B: DOWNSIDE CRASH ROLL ---
            # If spot drops into the condor and breaches K2 (e.g. 24,600), book profit on K1 and trail
            if not downside_rolled and cur_spot <= cur_k2:
                downside_rolled = True
                # Book K1 long put profit
                adj_booked_pnl += (p1 - cur_p1_0)
                cur_k1 = cur_k1 - 100
                cur_p1_0 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
                
        if exit_reason == "Expiry Settlement":
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
            
        nifty_move = round(((float(cycle_df.iloc[-1]['Close']) - entry_spot) / entry_spot) * 100.0, 2)
        roi_pct = round((final_pnl_rs / margin) * 100.0, 2)
        
        results.append({
            'Month': curr_exp.strftime('%b %Y'),
            'Entry_Spot': round(entry_spot, 0),
            'Month_End_Spot': round(float(cycle_df.iloc[-1]['Close']), 0),
            'Nifty_Move_Pct': nifty_move,
            'Adjustments': f"{'UpSpread ' if upside_spread_active else ''}{'DownRoll ' if downside_rolled else ''}".strip() or 'None',
            'Exit_Reason': exit_reason,
            'PnL_Rs': round(final_pnl_rs, 0),
            'ROI_Pct': roi_pct,
            'Max_DD_Rs': round(max_dd_rs, 0)
        })
        
    return pd.DataFrame(results)

if __name__ == '__main__':
    df = test_systematic_adjustments()
    print("=== SYSTEMATIC ADJUSTMENT BACKTEST RESULTS ===")
    print(df.to_string(index=False))
