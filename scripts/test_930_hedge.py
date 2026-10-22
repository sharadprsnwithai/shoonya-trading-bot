"""
Comparative Backtest: 09:16 AM Hedge vs 09:30 AM Hedge Purchase
Rules:
  - Hedge Option: Buy 20 strikes OTM Call and Put at either:
      Option A: 09:16 AM
      Option B: 09:30 AM
  - 10:00 AM: Sell ATM Call and Put (1x CE, 1x PE)
  - 30% Stop-loss on each leg independently (if other also increases 30%, exit that as well)
  - Surviving leg runs until 3:00 PM (15:00 IST)
  - 15:00 PM: Hard square-off of surviving leg and hedges.
  - Tested on NIFTY (Mon 1-DTE / Tue 0-DTE) and SENSEX (Wed 1-DTE / Thu 0-DTE).
"""

import sys
import os
sys.path.append(os.getcwd())
import math
from datetime import datetime, date, time, timedelta
import pandas as pd
import numpy as np
import scripts.backtest_10am_straddle_30sl as bt

def run_comparison():
    print("Fetching 5-minute candles for NIFTY and SENSEX...")
    df_nifty = bt.fetch_5m_data('^NSEI')
    df_sensex = bt.fetch_5m_data('^BSESN')
    
    nifty_days = {d: g for d, g in df_nifty.groupby('Date')}
    sensex_days = {d: g for d, g in df_sensex.groupby('Date')}
    
    def simulate_hedge_timing(hedge_time_choice):
        results = []
        for d in sorted(list(set(list(nifty_days.keys()) + list(sensex_days.keys())))):
            wd = d.weekday()
            if wd in (0, 1) and d in nifty_days:
                inst = 'NIFTY'
                mode = '1DTE' if wd == 0 else '0DTE'
                g = nifty_days[d]
                strike_step = 50.0
                base_iv = 0.13
                fee = 1.0
                qty = 250
            elif wd in (2, 3) and d in sensex_days:
                inst = 'SENSEX'
                mode = '1DTE' if wd == 2 else '0DTE'
                g = sensex_days[d]
                strike_step = 100.0
                base_iv = 0.125
                fee = 2.5
                qty = 100
            else:
                continue
                
            trade_date = g['Date'].iloc[0]
            expiry_date = trade_date if mode == '0DTE' else trade_date + timedelta(days=1)
            hedge_offset = 20 * strike_step
            
            t_hedge = hedge_time_choice
            t_entry = time(10, 0)
            t_exit = time(15, 0)
            
            long_ce_strike, long_ce_entry = None, 0.0
            long_pe_strike, long_pe_entry = None, 0.0
            short_ce_strike, short_ce_entry, short_ce_sl = None, 0.0, 0.0
            short_pe_strike, short_pe_entry, short_pe_sl = None, 0.0, 0.0
            short_ce_active, short_pe_active = False, False
            short_ce_pnl, short_pe_pnl = 0.0, 0.0
            short_ce_sl_hit, short_pe_sl_hit = False, False
            entered = False
            
            for idx in range(len(g)):
                bar = g.iloc[idx]
                t = bar['Time']
                spot = bar['Close']
                high = bar['High']
                low = bar['Low']
                tte = bt.get_intraday_tte(trade_date, t, expiry_date)
                
                # Hedge entry at chosen time (09:16 vs 09:30)
                if long_ce_strike is None and t >= t_hedge:
                    atm = round(spot / strike_step) * strike_step
                    long_pe_strike = atm - hedge_offset
                    long_ce_strike = atm + hedge_offset
                    long_ce_entry = bt.bs_option_price(spot, long_ce_strike, tte, base_iv, 'CE') + fee
                    long_pe_entry = bt.bs_option_price(spot, long_pe_strike, tte, base_iv, 'PE') + fee
                    
                # 10:00 AM Entry
                if not entered and t >= t_entry and t < t_exit:
                    atm = round(spot / strike_step) * strike_step
                    short_ce_strike = atm
                    short_pe_strike = atm
                    short_ce_entry = bt.bs_option_price(spot, short_ce_strike, tte, base_iv, 'CE') - fee
                    short_pe_entry = bt.bs_option_price(spot, short_pe_strike, tte, base_iv, 'PE') - fee
                    short_ce_sl = short_ce_entry * 1.30
                    short_pe_sl = short_pe_entry * 1.30
                    short_ce_active, short_pe_active, entered = True, True, True
                    
                # SL Monitor
                if entered and t < t_exit:
                    cur_ce_high = bt.bs_option_price(high, short_ce_strike, tte, base_iv, 'CE')
                    cur_pe_low = bt.bs_option_price(low, short_pe_strike, tte, base_iv, 'PE')
                    
                    if short_ce_active and cur_ce_high >= short_ce_sl:
                        short_ce_pnl = (short_ce_entry - short_ce_sl) - fee
                        short_ce_active = False
                        short_ce_sl_hit = True
                        
                    if short_pe_active and cur_pe_low >= short_pe_sl:
                        short_pe_pnl = (short_pe_entry - short_pe_sl) - fee
                        short_pe_active = False
                        short_pe_sl_hit = True
                        
                if entered and t >= t_exit:
                    break
                    
            # 15:00 Exit
            exit_bar = g[g['Time'] >= t_exit]
            if exit_bar.empty:
                exit_bar = g.iloc[-1:]
            spot_3pm = exit_bar.iloc[0]['Close']
            tte_3pm = bt.get_intraday_tte(trade_date, t_exit, expiry_date)
            
            if short_ce_active:
                exit_p = bt.bs_option_price(spot_3pm, short_ce_strike, tte_3pm, base_iv, 'CE')
                short_ce_pnl = (short_ce_entry - exit_p) - fee
            if short_pe_active:
                exit_p = bt.bs_option_price(spot_3pm, short_pe_strike, tte_3pm, base_iv, 'PE')
                short_pe_pnl = (short_pe_entry - exit_p) - fee
                
            long_ce_exit = bt.bs_option_price(spot_3pm, long_ce_strike, tte_3pm, base_iv, 'CE')
            long_ce_pnl = (long_ce_exit - long_ce_entry) - fee
            long_pe_exit = bt.bs_option_price(spot_3pm, long_pe_strike, tte_3pm, base_iv, 'PE')
            long_pe_pnl = (long_pe_exit - long_pe_entry) - fee
            
            hedge_pnl = long_ce_pnl + long_pe_pnl
            short_pnl = short_ce_pnl + short_pe_pnl
            net_points = short_pnl + hedge_pnl
            
            results.append({
                'date': trade_date,
                'inst': inst,
                'mode': mode,
                'net_points': net_points,
                'hedge_pnl': hedge_pnl,
                'short_pnl': short_pnl,
                'rupee_net': net_points * qty - 180.0,
                'win': net_points > 0
            })
            
        return pd.DataFrame(results)

    df_916 = simulate_hedge_timing(time(9, 16))
    df_930 = simulate_hedge_timing(time(9, 30))
    
    print("\n" + "="*95)
    print("   HEAD-TO-HEAD COMPARISON: 09:16 AM HEDGE vs 09:30 AM HEDGE")
    print("="*95)
    print(f"{'Hedge Timing':<18} | {'WinRate':<8} | {'Total Pts':<12} | {'Hedge Drag':<14} | {'Net Take-Home (10L)':<22} | {'Difference'}")
    print("-" * 95)
    
    pnl_916 = df_916['rupee_net'].sum()
    pnl_930 = df_930['rupee_net'].sum()
    drag_916 = df_916['hedge_pnl'].sum()
    drag_930 = df_930['hedge_pnl'].sum()
    
    print(f"{'09:16 AM (Baseline)':<18} | {df_916['win'].sum()/len(df_916)*100:>6.1f}% | {df_916['net_points'].sum():>+10.1f} pt | {drag_916:>+12.1f} pt | Rs. {pnl_916:>+16,.0f} | Baseline")
    print(f"{'09:30 AM (Optimized)':<18} | {df_930['win'].sum()/len(df_930)*100:>6.1f}% | {df_930['net_points'].sum():>+10.1f} pt | {drag_930:>+12.1f} pt | Rs. {pnl_930:>+16,.0f} | Rs. {pnl_930 - pnl_916:>+10,.0f}")
    print("-" * 95)
    
    print("\n" + "-"*95)
    print("   BREAKDOWN BY INSTRUMENT (09:30 AM HEDGE)")
    print("-"*95)
    for (inst, mode), grp in df_930.groupby(['inst', 'mode']):
        g_wins = grp['win'].sum()
        g_rate = g_wins / len(grp) * 100.0
        g_pts = grp['net_points'].sum()
        g_rs = grp['rupee_net'].sum()
        g_drag = grp['hedge_pnl'].sum()
        print(f"• {inst} {mode:<5} : {len(grp):>2} days | WinRate: {g_rate:>5.1f}% | Points: {g_pts:>+8.1f} pt | Hedge Drag: {g_drag:>+7.1f} pt | Net: Rs. {g_rs:>+10,.0f}")
    print("="*95 + "\n")

if __name__ == '__main__':
    run_comparison()
