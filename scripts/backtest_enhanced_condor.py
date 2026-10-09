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

def run_enhanced_condor_backtest():
    df = get_nifty_data()
    expiries = find_monthly_expiries(df)
    
    lot_size = 65
    margin = 100000.0
    target_profit_rs = 3000.0 # +3.0%
    stop_loss_rs = 3000.0     # -3.0%
    r = 0.07
    
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
        
        # Position tracking states
        exit_reason = "Expiry Settlement"
        exit_date = curr_exp
        final_pnl_rs = 0.0
        max_dd_rs = 0.0
        
        cur_k1 = k1_buy
        cur_p1_0 = p1_0
        cur_k2 = k2_sell
        cur_p2_0 = p2_0
        cur_k3 = k3_sell
        cur_p3_0 = p3_0
        cur_k4 = k4_buy
        cur_p4_0 = p4_0
        
        k2_k3_closed = False
        upside_spread_added = False
        up_sell_k = 0
        up_buy_k = 0
        up_sell_p0 = 0.0
        up_buy_p0 = 0.0
        
        adj_booked_pnl = 0.0
        k1_shifted = False
        
        for day_idx, (d, row) in enumerate(cycle_df.iterrows()):
            cur_spot = float(row['Close'])
            cur_vix = float(row['VIX']) / 100.0
            t_rem = max(0.0001, (curr_exp - d).days) / 365.0
            
            # Leg values
            p1 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
            p4 = bs_price(cur_spot, cur_k4, t_rem, r, cur_vix, 'PE')
            
            p2 = bs_price(cur_spot, cur_k2, t_rem, r, cur_vix, 'PE') if not k2_k3_closed else 0.0
            p3 = bs_price(cur_spot, cur_k3, t_rem, r, cur_vix, 'PE') if not k2_k3_closed else 0.0
            
            # Unrealized MTM from condor
            long_pnl = (p1 - cur_p1_0) + (p4 - cur_p4_0)
            short_pnl = ((cur_p2_0 - p2) + (cur_p3_0 - p3)) if not k2_k3_closed else 0.0
            
            # Upside spread MTM
            up_pnl = 0.0
            if upside_spread_added:
                up_s_p = bs_price(cur_spot, up_sell_k, t_rem, r, cur_vix, 'PE')
                up_b_p = bs_price(cur_spot, up_buy_k, t_rem, r, cur_vix, 'PE')
                up_pnl = (up_sell_p0 - up_s_p) + (up_b_p - up_buy_p0)
                
            cur_mtm_rs = (adj_booked_pnl + long_pnl + short_pnl + up_pnl) * lot_size
            if cur_mtm_rs < max_dd_rs:
                max_dd_rs = cur_mtm_rs
                
            # 1. Target Profit Check (+3.0%)
            if cur_mtm_rs >= target_profit_rs:
                exit_reason = f"Target Hit (+Rs {round(cur_mtm_rs)})"
                exit_date = d
                final_pnl_rs = cur_mtm_rs
                break
                
            # 2. Hard Stop Loss Check (-3.0%)
            if cur_mtm_rs <= -stop_loss_rs:
                exit_reason = f"SL Hit (-Rs {round(abs(cur_mtm_rs))})"
                exit_date = d
                final_pnl_rs = cur_mtm_rs
                break
                
            # --- DETERMINISTIC ADJUSTMENT 1: UPSIDE FINANCING ---
            # If spot rallies by +0.75% (or by Day 7 spot >= ATM + 100), fund the debit!
            if not upside_spread_added and (cur_spot >= atm + 150 or (day_idx >= 7 and cur_spot >= atm)):
                upside_spread_added = True
                # Sell Put 300 pts OTM, Buy Put 400 pts OTM
                up_sell_k = int(round((cur_spot - 300) / 100.0) * 100.0)
                up_buy_k = up_sell_k - 100
                up_sell_p0 = bs_price(cur_spot, up_sell_k, t_rem, r, cur_vix, 'PE')
                up_buy_p0 = bs_price(cur_spot, up_buy_k, t_rem, r, cur_vix, 'PE')
                
            # --- DETERMINISTIC ADJUSTMENT 2: INSIDE CONDOR ZONE SHIFT ---
            # When spot drops to K2 (e.g. 24,600)
            if not k1_shifted and not k2_k3_closed and cur_spot <= cur_k2:
                k1_shifted = True
                adj_booked_pnl += (p1 - cur_p1_0)
                cur_k1 = cur_k1 - 100
                cur_p1_0 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
                
            # --- DETERMINISTIC ADJUSTMENT 3: DEEP CRASH DEFENSE ---
            # When spot plunges past K3 (e.g. 24,400)
            if not k2_k3_closed and cur_spot <= k3_sell:
                k2_k3_closed = True
                # Close Short Puts K2 and K3 to eliminate downside risk
                p2_cur = bs_price(cur_spot, cur_k2, t_rem, r, cur_vix, 'PE')
                p3_cur = bs_price(cur_spot, cur_k3, t_rem, r, cur_vix, 'PE')
                adj_booked_pnl += (cur_p2_0 - p2_cur) + (cur_p3_0 - p3_cur)
                # Keep Long Put 1 open to ride the crash, and book on target
                
        if exit_reason == "Expiry Settlement":
            exp_spot = float(cycle_df.loc[curr_exp, 'Close'])
            pay_1 = max(0.0, cur_k1 - exp_spot)
            pay_4 = max(0.0, cur_k4 - exp_spot)
            pay_2 = max(0.0, cur_k2 - exp_spot) if not k2_k3_closed else 0.0
            pay_3 = max(0.0, cur_k3 - exp_spot) if not k2_k3_closed else 0.0
            
            up_pay = 0.0
            if upside_spread_added:
                up_s_pay = max(0.0, up_sell_k - exp_spot)
                up_b_pay = max(0.0, up_buy_k - exp_spot)
                up_pay = (up_sell_p0 - up_s_pay) + (up_b_pay - up_buy_p0)
                
            pnl_pts = adj_booked_pnl + (pay_1 - cur_p1_0) + (pay_4 - cur_p4_0) - \
                      ((pay_2 - cur_p2_0) + (pay_3 - cur_p3_0) if not k2_k3_closed else 0.0) + up_pay
            final_pnl_rs = pnl_pts * lot_size
            
        nifty_move = round(((float(cycle_df.iloc[-1]['Close']) - entry_spot) / entry_spot) * 100.0, 2)
        roi_pct = round((final_pnl_rs / margin) * 100.0, 2)
        
        results.append({
            'Month': curr_exp.strftime('%b %Y'),
            'Entry_Date': entry_date.strftime('%Y-%m-%d'),
            'Exit_Date': exit_date.strftime('%Y-%m-%d'),
            'Entry_Spot': round(entry_spot, 0),
            'Month_End_Spot': round(float(cycle_df.iloc[-1]['Close']), 0),
            'Nifty_Move_Pct': nifty_move,
            'Adjustments': f"{'UpFund ' if upside_spread_added else ''}{'K1Shift ' if k1_shifted else ''}{'CrashClose' if k2_k3_closed else ''}".strip() or 'None',
            'Exit_Reason': exit_reason,
            'PnL_Rs': round(final_pnl_rs, 0),
            'ROI_Pct': roi_pct,
            'Max_DD_Rs': round(max_dd_rs, 0)
        })
        
    return pd.DataFrame(results)

if __name__ == '__main__':
    df = run_enhanced_condor_backtest()
    print("=== ENHANCED SYSTEMATIC PUT CONDOR (ALL 13 MONTHS) ===")
    print(df.to_string(index=False))
