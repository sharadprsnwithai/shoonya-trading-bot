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

def run_put_condor_backtest():
    df = get_nifty_data()
    expiries = find_monthly_expiries(df)
    
    lot_size = 65
    margin = 100000.0 # ~₹1,00,000 margin
    target_profit_rs = 3000.0 # +3.0% target
    stop_loss_rs = 3000.0     # -3.0% stop loss
    r = 0.07 # 7% risk-free rate
    
    results = []
    
    # Last 12-13 months
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
        
        # Initial Premiums
        p1_0 = bs_price(entry_spot, k1_buy, t_total, r, entry_vix, 'PE')
        p2_0 = bs_price(entry_spot, k2_sell, t_total, r, entry_vix, 'PE')
        p3_0 = bs_price(entry_spot, k3_sell, t_total, r, entry_vix, 'PE')
        p4_0 = bs_price(entry_spot, k4_buy, t_total, r, entry_vix, 'PE')
        
        initial_net_debit_pts = (p1_0 + p4_0) - (p2_0 + p3_0)
        initial_max_loss_rs = initial_net_debit_pts * lot_size
        
        # State tracking
        exit_reason = "Expiry Settlement"
        exit_date = curr_exp
        final_pnl_rs = 0.0
        max_dd_rs = 0.0
        is_adjusted = False
        adj_booked_pnl = 0.0
        
        # Current active strikes
        cur_k1 = k1_buy
        cur_p1_0 = p1_0
        cur_k2 = k2_sell
        cur_p2_0 = p2_0
        
        for d, row in cycle_df.iterrows():
            cur_spot = float(row['Close'])
            cur_vix = float(row['VIX']) / 100.0
            t_rem = max(0.0001, (curr_exp - d).days) / 365.0
            
            p1 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
            p2 = bs_price(cur_spot, cur_k2, t_rem, r, cur_vix, 'PE')
            p3 = bs_price(cur_spot, k3_sell, t_rem, r, cur_vix, 'PE')
            p4 = bs_price(cur_spot, k4_buy, t_rem, r, cur_vix, 'PE')
            
            # Unrealized MTM
            long_pnl = (p1 - cur_p1_0) + (p4 - p4_0)
            short_pnl = (cur_p2_0 - p2) + (p3_0 - p3)
            cur_mtm_rs = (adj_booked_pnl + long_pnl + short_pnl) * lot_size
            
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
                
            # 3. Dynamic Adjustment: When spot falls into condor zone (breaches K2)
            if not is_adjusted and cur_spot <= cur_k2:
                is_adjusted = True
                # Book Leg 1
                adj_booked_pnl += (p1 - cur_p1_0)
                # Shift Long Put 100 pts closer
                cur_k1 = cur_k1 - 100
                cur_p1_0 = bs_price(cur_spot, cur_k1, t_rem, r, cur_vix, 'PE')
                
        # If held till expiry
        if exit_reason == "Expiry Settlement":
            exp_spot = float(cycle_df.loc[curr_exp, 'Close'])
            # Payoff at expiry
            pay_1 = max(0.0, cur_k1 - exp_spot)
            pay_2 = max(0.0, cur_k2 - exp_spot)
            pay_3 = max(0.0, k3_sell - exp_spot)
            pay_4 = max(0.0, k4_buy - exp_spot)
            
            pnl_pts = adj_booked_pnl + (pay_1 - cur_p1_0) - (pay_2 - cur_p2_0) - (pay_3 - p3_0) + (pay_4 - p4_0)
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
            'Strikes': f"{k4_buy}/{k3_sell}/{k2_sell}/{k1_buy} PE",
            'Init_Debit_Rs': round(initial_max_loss_rs, 0),
            'Adjusted': 'Yes' if is_adjusted else 'No',
            'Exit_Reason': exit_reason,
            'PnL_Rs': round(final_pnl_rs, 0),
            'ROI_Pct': roi_pct,
            'Max_DD_Rs': round(max_dd_rs, 0)
        })
        
    return pd.DataFrame(results)

if __name__ == '__main__':
    df = run_put_condor_backtest()
    print("=== MONTHLY PUT CONDOR (1-YEAR BACKTEST) ===")
    print(df.to_string(index=False))
