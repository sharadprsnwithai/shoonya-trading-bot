"""
Optimization Comparison Engine for "The 1 and 0 NS 916 TTC Strategy"
Testing 6 targeted scenarios:
  1. Baseline (Original Video Rules: Sensex 13:35 entry, standard hedges)
  2. Sensex Afternoon Entry shifted to 14:17 PM (Synchronized with Nifty)
  3. Lean Hedges (Deeper OTM wings to cut daily protection drag)
  4. Full Intraday Hybrid (Sensex 14:17 + Lean Hedges across Mon-Thu)
  5. 1-DTE ONLY (Monday Nifty + Wednesday Sensex, Baseline)
  6. 1-DTE ONLY + Full Optimization (Best statistical edge)
"""

import sys
import os
sys.path.append(os.getcwd())
from datetime import time, timedelta
import pandas as pd
import numpy as np
import scripts.backtest_10ns_straddle as bt

def run_suite():
    print("Fetching 5-minute candles for NIFTY and SENSEX...")
    df_nifty = bt.fetch_5m_data('^NSEI')
    df_sensex = bt.fetch_5m_data('^BSESN')
    
    nifty_days = {d: g for d, g in df_nifty.groupby('Date')}
    sensex_days = {d: g for d, g in df_sensex.groupby('Date')}
    all_dates = sorted(list(set(list(nifty_days.keys()) + list(sensex_days.keys()))))
    
    def evaluate_scenario(name, sensex_aft_time, sensex_hedge_dist, nifty_hedge_dist, days_allowed):
        results = []
        for d in all_dates:
            wd = d.weekday() # 0=Mon, 1=Tue, 2=Wed, 3=Thu
            if wd not in days_allowed:
                continue
            
            if wd in (0, 1) and d in nifty_days:
                inst = 'NIFTY'
                mode = '1DTE' if wd == 0 else '0DTE'
                g = nifty_days[d]
                strike_step = 50.0
                t_aft_entry = time(14, 17)
                hedge_offset = nifty_hedge_dist
                fee = 1.0
                base_iv = 0.13
            elif wd in (2, 3) and d in sensex_days:
                inst = 'SENSEX'
                mode = '1DTE' if wd == 2 else '0DTE'
                g = sensex_days[d]
                strike_step = 100.0
                t_aft_entry = sensex_aft_time
                hedge_offset = sensex_hedge_dist
                fee = 2.5
                base_iv = 0.125
            else:
                continue
                
            trade_date = g['Date'].iloc[0]
            expiry_date = trade_date if mode == '0DTE' else trade_date + timedelta(days=1)
            
            t_hedge_buy = time(9, 16)
            t_morn_entry = time(9, 22)
            t_morn_exit = time(10, 16) if inst == 'NIFTY' else time(10, 30)
            t_eod_exit = time(15, 30)
            
            long_ce_strike, long_ce_entry = None, 0.0
            long_pe_strike, long_pe_entry = None, 0.0
            morn_ce_strike, morn_ce_entry, morn_ce_sl, morn_ce_active, morn_ce_pnl = None, 0, 0, False, 0
            morn_pe_strike, morn_pe_entry, morn_pe_sl, morn_pe_active, morn_pe_pnl = None, 0, 0, False, 0
            aft_ce_strike, aft_ce_entry, aft_ce_sl, aft_ce_active, aft_ce_pnl = None, 0, 0, False, 0
            aft_pe_strike, aft_pe_entry, aft_pe_sl, aft_pe_active, aft_pe_pnl = None, 0, 0, False, 0
            morn_traded, morn_exited, aft_traded = False, False, False
            
            for idx in range(len(g)):
                bar = g.iloc[idx]
                t = bar['Time']
                spot = bar['Close']
                high = bar['High']
                low = bar['Low']
                tte = bt.get_intraday_tte(trade_date, t, expiry_date)
                
                # Hedge
                if long_ce_strike is None and t >= t_hedge_buy:
                    atm = round(spot / strike_step) * strike_step
                    long_pe_strike = atm - hedge_offset
                    long_ce_strike = atm + hedge_offset
                    long_ce_entry = bt.bs_option_price(spot, long_ce_strike, tte, base_iv, 'CE') + fee
                    long_pe_entry = bt.bs_option_price(spot, long_pe_strike, tte, base_iv, 'PE') + fee
                    
                # Morn Entry
                if not morn_traded and t >= t_morn_entry and t < t_morn_exit:
                    atm = round(spot / strike_step) * strike_step
                    morn_ce_strike = atm
                    morn_pe_strike = atm
                    morn_ce_entry = bt.bs_option_price(spot, morn_ce_strike, tte, base_iv, 'CE') - fee
                    morn_pe_entry = bt.bs_option_price(spot, morn_pe_strike, tte, base_iv, 'PE') - fee
                    morn_ce_sl = morn_ce_entry * 1.50
                    morn_pe_sl = morn_pe_entry * 1.50
                    morn_ce_active, morn_pe_active, morn_traded = True, True, True
                    
                # Morn SL
                if morn_traded and not morn_exited:
                    cur_ce = bt.bs_option_price(high, morn_ce_strike, tte, base_iv, 'CE')
                    cur_pe = bt.bs_option_price(low, morn_pe_strike, tte, base_iv, 'PE')
                    if morn_ce_active and cur_ce >= morn_ce_sl:
                        morn_ce_pnl = morn_ce_entry - morn_ce_sl - fee
                        morn_ce_active = False
                    if morn_pe_active and cur_pe >= morn_pe_sl:
                        morn_pe_pnl = morn_pe_entry - morn_pe_sl - fee
                        morn_pe_active = False
                        
                # Morn Exit
                if morn_traded and not morn_exited and t >= t_morn_exit:
                    if morn_ce_active:
                        morn_ce_pnl = (morn_ce_entry - bt.bs_option_price(spot, morn_ce_strike, tte, base_iv, 'CE')) - fee
                        morn_ce_active = False
                    if morn_pe_active:
                        morn_pe_pnl = (morn_pe_entry - bt.bs_option_price(spot, morn_pe_strike, tte, base_iv, 'PE')) - fee
                        morn_pe_active = False
                    morn_exited = True
                    
                # Aft Entry
                if not aft_traded and t >= t_aft_entry and t < t_eod_exit:
                    atm = round(spot / strike_step) * strike_step
                    aft_ce_strike = atm
                    aft_pe_strike = atm
                    aft_ce_entry = bt.bs_option_price(spot, aft_ce_strike, tte, base_iv, 'CE') - fee
                    aft_pe_entry = bt.bs_option_price(spot, aft_pe_strike, tte, base_iv, 'PE') - fee
                    aft_ce_sl = aft_ce_entry * 1.50
                    aft_pe_sl = aft_pe_entry * 1.50
                    aft_ce_active, aft_pe_active, aft_traded = True, True, True
                    
                # Aft SL
                if aft_traded:
                    cur_ce = bt.bs_option_price(high, aft_ce_strike, tte, base_iv, 'CE')
                    cur_pe = bt.bs_option_price(low, aft_pe_strike, tte, base_iv, 'PE')
                    if aft_ce_active and cur_ce >= aft_ce_sl:
                        aft_ce_pnl = aft_ce_entry - aft_ce_sl - fee
                        aft_ce_active = False
                    if aft_pe_active and cur_pe >= aft_pe_sl:
                        aft_pe_pnl = aft_pe_entry - aft_pe_sl - fee
                        aft_pe_active = False
                        
            # EOD
            final_spot = g.iloc[-1]['Close']
            final_tte = bt.get_intraday_tte(trade_date, time(15, 30), expiry_date)
            if aft_ce_active:
                aft_ce_pnl = (aft_ce_entry - bt.bs_option_price(final_spot, aft_ce_strike, final_tte, base_iv, 'CE')) - fee
            if aft_pe_active:
                aft_pe_pnl = (aft_pe_entry - bt.bs_option_price(final_spot, aft_pe_strike, final_tte, base_iv, 'PE')) - fee
                
            long_ce_pnl = (bt.bs_option_price(final_spot, long_ce_strike, final_tte, base_iv, 'CE') - long_ce_entry) - fee
            long_pe_pnl = (bt.bs_option_price(final_spot, long_pe_strike, final_tte, base_iv, 'PE') - long_pe_entry) - fee
            
            hedge_pnl = long_ce_pnl + long_pe_pnl
            short_pnl = morn_ce_pnl + morn_pe_pnl + aft_ce_pnl + aft_pe_pnl
            net_pts = short_pnl + hedge_pnl
            
            results.append({
                'inst': inst,
                'mode': mode,
                'net_pts': net_pts,
                'hedge_pnl': hedge_pnl,
                'short_pnl': short_pnl,
                'win': net_pts > 0
            })
            
        df_r = pd.DataFrame(results)
        n_pts = df_r[df_r['inst']=='NIFTY']['net_pts'].sum()
        s_pts = df_r[df_r['inst']=='SENSEX']['net_pts'].sum()
        win_rate = (df_r['win'].sum() / len(df_r)) * 100
        
        # Financial P&L on 5 lots: Nifty * 125, Sensex * 50
        total_rs = n_pts * 125 + s_pts * 50
        hedge_pts = df_r['hedge_pnl'].sum()
        short_pts = df_r['short_pnl'].sum()
        
        return {
            'name': name,
            'sessions': len(df_r),
            'win_rate': win_rate,
            'nifty_pts': n_pts,
            'sensex_pts': s_pts,
            'short_pts': short_pts,
            'hedge_pts': hedge_pts,
            'total_rs': total_rs
        }
        
    print("\n" + "="*115)
    print(f"{'Scenario Name':<35} | {'Days':<5} | {'WinRate':<7} | {'Nifty Pts':<10} | {'Sensex Pts':<11} | {'Hedge Drag':<11} | {'Net P&L (Rs.)'}")
    print("="*115)
    
    scenarios = [
        ('1. Baseline (Original Video Rules)', time(13, 35), 1500.0, 1000.0, [0, 1, 2, 3]),
        ('2. Shift Sensex Aft Entry to 14:17', time(14, 17), 1500.0, 1000.0, [0, 1, 2, 3]),
        ('3. Baseline + Lean Hedges', time(13, 35), 3000.0, 1500.0, [0, 1, 2, 3]),
        ('4. Sensex 14:17 + Lean Hedges (Mon-Thu)', time(14, 17), 3000.0, 1500.0, [0, 1, 2, 3]),
        ('5. 1-DTE ONLY (Mon & Wed, Baseline)', time(13, 35), 1500.0, 1000.0, [0, 2]),
        ('6. 1-DTE ONLY + Sensex 14:17 + Lean', time(14, 17), 3000.0, 1500.0, [0, 2]),
    ]
    
    for s in scenarios:
        res = evaluate_scenario(*s)
        print(f"{res['name']:<35} | {res['sessions']:>4}  | {res['win_rate']:>6.1f}% | {res['nifty_pts']:>+9.1f} | {res['sensex_pts']:>+10.1f} | {res['hedge_pts']:>+10.1f} | Rs. {res['total_rs']:>+10,.0f}")
        
    print("="*115 + "\n")

if __name__ == '__main__':
    run_suite()
