import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import numpy as np
import pandas as pd
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

def simulate_rally_scenarios():
    spot_0 = 25000.0
    r = 0.07
    vix = 0.13
    t_0 = 30.0 / 365.0
    lot_size = 65
    
    # Put Condor Strikes at entry
    k1 = 24800 # Buy
    k2 = 24600 # Sell
    k3 = 24400 # Sell
    k4 = 24200 # Buy
    
    p1_0 = bs_price(spot_0, k1, t_0, r, vix, 'PE')
    p2_0 = bs_price(spot_0, k2, t_0, r, vix, 'PE')
    p3_0 = bs_price(spot_0, k3, t_0, r, vix, 'PE')
    p4_0 = bs_price(spot_0, k4, t_0, r, vix, 'PE')
    
    init_debit_pts = (p1_0 + p4_0) - (p2_0 + p3_0)
    init_debit_rs = init_debit_pts * lot_size
    
    # Upside Spread added when spot hits 25187 (+0.75% / day 3)
    t_adj = 27.0 / 365.0
    spot_adj = 25187.5
    up_sell_k = 24900
    up_buy_k = 24800
    up_sell_p0 = bs_price(spot_adj, up_sell_k, t_adj, r, vix, 'PE')
    up_buy_p0 = bs_price(spot_adj, up_buy_k, t_adj, r, vix, 'PE')
    up_credit_pts = up_sell_p0 - up_buy_p0
    up_credit_rs = up_credit_pts * lot_size
    
    rally_levels = [
        (0.0, spot_0, "Flat (No Move)"),
        (1.0, 25250.0, "+1.0% Rally (+250 pts)"),
        (2.0, 25500.0, "+2.0% Rally (+500 pts)"),
        (3.5, 25875.0, "+3.5% Rally (+875 pts)"),
        (5.0, 26250.0, "+5.0% Mega Rally (+1,250 pts)"),
        (8.0, 27000.0, "+8.0% Monster Bull Run (+2,000 pts)"),
        (12.0, 28000.0, "+12.0% Historical Surge (+3,000 pts)")
    ]
    
    results = []
    for pct, spot_exp, desc in rally_levels:
        # Expiry values of Put Condor
        pay_1 = max(0.0, k1 - spot_exp)
        pay_2 = max(0.0, k2 - spot_exp)
        pay_3 = max(0.0, k3 - spot_exp)
        pay_4 = max(0.0, k4 - spot_exp)
        
        base_condor_payoff_pts = (pay_1 - p1_0) + (pay_4 - p4_0) - (pay_2 - p2_0) - (pay_3 - p3_0)
        base_condor_pnl_rs = base_condor_payoff_pts * lot_size
        
        # Expiry values of Upside Financing Spread
        up_s_pay = max(0.0, up_sell_k - spot_exp)
        up_b_pay = max(0.0, up_buy_k - spot_exp)
        up_spread_payoff_pts = (up_sell_p0 - up_s_pay) + (up_b_pay - up_buy_p0)
        up_spread_pnl_rs = up_spread_payoff_pts * lot_size
        
        total_adjusted_pnl_rs = base_condor_pnl_rs + up_spread_pnl_rs
        
        results.append({
            'Rally Scenario': desc,
            'Expiry Spot': spot_exp,
            'Base Condor PnL (Rs)': round(base_condor_pnl_rs, 0),
            'Upside Spread Credit (Rs)': round(up_credit_rs, 0),
            'Final Strategy PnL (Rs)': round(total_adjusted_pnl_rs, 0),
            'ROI on Capital': f"{(total_adjusted_pnl_rs / 100000.0) * 100:.2f}%"
        })
        
    return pd.DataFrame(results)

if __name__ == '__main__':
    df = simulate_rally_scenarios()
    print("=== BEHAVIOR DURING CONTINUOUS UPWARD RALLIES ===")
    print(df.to_string(index=False))
