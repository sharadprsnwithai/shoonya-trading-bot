"""
Backtest Engine for 10:00 AM Straddle with 30% Stop-Loss:
Rules:
  - 09:16 AM: Buy 20 strikes OTM Call and Put.
  - 10:00 AM: Sell ATM Call and ATM Put.
  - Stop Loss: Exit the leg having 30% rise in premium (SL = Entry * 1.30).
  - Trade Management: Let the surviving leg run until 3:00 PM (15:00 IST).
  - 03:00 PM (15:00 IST): Square off all remaining open positions (surviving short leg + hedges).
  - Test for 1-DTE and 0-DTE across both NIFTY and SENSEX.
"""

import sys
import os
import math
from datetime import datetime, date, time, timedelta
import pandas as pd
import numpy as np
from scipy.stats import norm
import yfinance as yf

# -----------------------------------------------------------------------------
# Black-Scholes Pricing Function
# -----------------------------------------------------------------------------
def get_intraday_tte(trade_date, current_time, expiry_date):
    days_diff = (expiry_date - trade_date).days
    cur_mins = current_time.hour * 60 + current_time.minute
    close_mins = 15 * 60 + 30 # 15:30 close
    
    mins_remaining_today = max(0, close_mins - cur_mins)
    frac_today = mins_remaining_today / 375.0
    
    if days_diff == 0:
        trading_days_remaining = max(0.01, frac_today)
    else:
        trading_days_remaining = (days_diff - 1) + frac_today
        
    return max(trading_days_remaining / 365.25, 1e-5)

def bs_option_price(spot, strike, tte, iv, opt_type='CE', r=0.065):
    if spot <= 0 or strike <= 0 or tte <= 0:
        return 0.05
    sigma = max(iv, 0.05)
    d1 = (math.log(spot / strike) + (r + 0.5 * sigma**2) * tte) / (sigma * math.sqrt(tte))
    d2 = d1 - sigma * math.sqrt(tte)
    
    if opt_type == 'CE':
        price = spot * norm.cdf(d1) - strike * math.exp(-r * tte) * norm.cdf(d2)
    else:
        price = strike * math.exp(-r * tte) * norm.cdf(-d2) - spot * norm.cdf(-d1)
    return max(0.05, float(price))

# -----------------------------------------------------------------------------
# Data Loader
# -----------------------------------------------------------------------------
def fetch_5m_data(ticker):
    print(f"Fetching 5-minute candles for {ticker}...")
    df = yf.download(ticker, period='60d', interval='5m', progress=False)
    if df.empty:
        raise ValueError(f"Failed to fetch data for {ticker}")
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = [c[0] for c in df.columns]
        
    df = df.dropna(subset=['Open', 'High', 'Low', 'Close']).copy()
    df['Datetime'] = df.index
    df['Date'] = df['Datetime'].dt.date
    df['Time'] = df['Datetime'].dt.time
    df['Weekday'] = df['Datetime'].dt.weekday # 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri
    return df

# -----------------------------------------------------------------------------
# Simulation for 10:00 AM Straddle with 30% SL
# -----------------------------------------------------------------------------
def simulate_day_10am(day_df, instrument, mode):
    trade_date = day_df['Date'].iloc[0]
    strike_step = 50.0 if instrument == 'NIFTY' else 100.0
    expiry_date = trade_date if mode == '0DTE' else trade_date + timedelta(days=1)
    
    t_hedge = time(9, 16)
    t_entry = time(10, 0)
    t_exit = time(15, 0) # 3:00 PM square-off as requested
    
    base_iv = 0.13 if instrument == 'NIFTY' else 0.125
    fee = 1.0 if instrument == 'NIFTY' else 2.5
    
    # 20 strikes offset
    # NIFTY: 20 * 50 = 1000 pts
    # SENSEX: 20 * 100 = 2000 pts
    hedge_offset = 20 * strike_step
    
    long_ce_strike, long_ce_entry = None, 0.0
    long_pe_strike, long_pe_entry = None, 0.0
    
    short_ce_strike, short_ce_entry, short_ce_sl = None, 0.0, 0.0
    short_pe_strike, short_pe_entry, short_pe_sl = None, 0.0, 0.0
    short_ce_active, short_pe_active = False, False
    
    short_ce_pnl, short_pe_pnl = 0.0, 0.0
    short_ce_sl_hit, short_pe_sl_hit = False, False
    entered = False
    
    for idx in range(len(day_df)):
        bar = day_df.iloc[idx]
        t = bar['Time']
        spot = bar['Close']
        high = bar['High']
        low = bar['Low']
        tte = get_intraday_tte(trade_date, t, expiry_date)
        
        # 1. 09:16 AM: Buy 20 OTM hedges
        if long_ce_strike is None and t >= t_hedge:
            atm = round(spot / strike_step) * strike_step
            long_pe_strike = atm - hedge_offset
            long_ce_strike = atm + hedge_offset
            long_ce_entry = bs_option_price(spot, long_ce_strike, tte, base_iv, 'CE') + fee
            long_pe_entry = bs_option_price(spot, long_pe_strike, tte, base_iv, 'PE') + fee
            
        # 2. 10:00 AM: Sell ATM Call and ATM Put
        if not entered and t >= t_entry and t < t_exit:
            atm = round(spot / strike_step) * strike_step
            short_ce_strike = atm
            short_pe_strike = atm
            
            short_ce_entry = bs_option_price(spot, short_ce_strike, tte, base_iv, 'CE') - fee
            short_pe_entry = bs_option_price(spot, short_pe_strike, tte, base_iv, 'PE') - fee
            
            # 30% Stop-loss per leg
            short_ce_sl = short_ce_entry * 1.30
            short_pe_sl = short_pe_entry * 1.30
            
            short_ce_active = True
            short_pe_active = True
            entered = True
            
        # 3. Between 10:00 AM and 15:00 PM: Monitor 30% Stop Loss
        if entered and t < t_exit:
            cur_ce_high = bs_option_price(high, short_ce_strike, tte, base_iv, 'CE')
            cur_pe_low = bs_option_price(low, short_pe_strike, tte, base_iv, 'PE')
            
            if short_ce_active and cur_ce_high >= short_ce_sl:
                short_ce_pnl = (short_ce_entry - short_ce_sl) - fee
                short_ce_active = False
                short_ce_sl_hit = True
                
            if short_pe_active and cur_pe_low >= short_pe_sl:
                short_pe_pnl = (short_pe_entry - short_pe_sl) - fee
                short_pe_active = False
                short_pe_sl_hit = True
                
        # 4. 03:00 PM (15:00 IST): Exit surviving short leg
        if entered and t >= t_exit:
            break
            
    # Settle at 3:00 PM (15:00) bar
    exit_bar = day_df[day_df['Time'] >= t_exit]
    if exit_bar.empty:
        exit_bar = day_df.iloc[-1:]
    spot_at_3pm = exit_bar.iloc[0]['Close']
    tte_at_3pm = get_intraday_tte(trade_date, t_exit, expiry_date)
    
    if short_ce_active:
        exit_p = bs_option_price(spot_at_3pm, short_ce_strike, tte_at_3pm, base_iv, 'CE')
        short_ce_pnl = (short_ce_entry - exit_p) - fee
        
    if short_pe_active:
        exit_p = bs_option_price(spot_at_3pm, short_pe_strike, tte_at_3pm, base_iv, 'PE')
        short_pe_pnl = (short_pe_entry - exit_p) - fee
        
    # Close 20 OTM hedges at 3:00 PM
    long_ce_exit = bs_option_price(spot_at_3pm, long_ce_strike, tte_at_3pm, base_iv, 'CE')
    long_ce_pnl = (long_ce_exit - long_ce_entry) - fee
    
    long_pe_exit = bs_option_price(spot_at_3pm, long_pe_strike, tte_at_3pm, base_iv, 'PE')
    long_pe_pnl = (long_pe_exit - long_pe_entry) - fee
    
    hedge_pnl = long_ce_pnl + long_pe_pnl
    short_pnl = short_ce_pnl + short_pe_pnl
    net_points = short_pnl + hedge_pnl
    
    return {
        'date': trade_date,
        'instrument': instrument,
        'mode': mode,
        'short_ce_entry': short_ce_entry,
        'short_pe_entry': short_pe_entry,
        'short_ce_pnl': short_ce_pnl,
        'short_pe_pnl': short_pe_pnl,
        'short_ce_sl_hit': short_ce_sl_hit,
        'short_pe_sl_hit': short_pe_sl_hit,
        'short_pnl': short_pnl,
        'hedge_pnl': hedge_pnl,
        'net_points': net_points,
        'win': net_points > 0
    }

# -----------------------------------------------------------------------------
# Main Runner & Multi-Instrument Comparison
# -----------------------------------------------------------------------------
def run_backtest():
    print("="*95)
    print("   BACKTEST: 10:00 AM STRADDLE (30% SL, RUN TILL 3:00 PM, 20 OTM HEDGES)")
    print("="*95)
    
    df_nifty = fetch_5m_data('^NSEI')
    df_sensex = fetch_5m_data('^BSESN')
    
    nifty_days = {d: g for d, g in df_nifty.groupby('Date')}
    sensex_days = {d: g for d, g in df_sensex.groupby('Date')}
    
    results = []
    
    # 1. NIFTY Monday (1-DTE) and Tuesday (0-DTE)
    for d, g in sorted(nifty_days.items()):
        wd = d.weekday()
        if wd == 0:
            results.append(simulate_day_10am(g, 'NIFTY', '1DTE'))
        elif wd == 1:
            results.append(simulate_day_10am(g, 'NIFTY', '0DTE'))
            
    # 2. SENSEX Wednesday (1-DTE) and Thursday (0-DTE)
    for d, g in sorted(sensex_days.items()):
        wd = d.weekday()
        if wd == 2:
            results.append(simulate_day_10am(g, 'SENSEX', '1DTE'))
        elif wd == 3:
            results.append(simulate_day_10am(g, 'SENSEX', '0DTE'))
            
    df_res = pd.DataFrame(results)
    
    if df_res.empty:
        print("No sessions simulated.")
        return
        
    print("\n" + "="*95)
    print("   OVERALL PERFORMANCE RESULTS")
    print("="*95)
    
    total_sessions = len(df_res)
    wins = df_res[df_res['win']]
    losses = df_res[~df_res['win']]
    win_rate = len(wins) / total_sessions * 100.0
    
    # Multipliers on 10 lots: Nifty (250 qty), Sensex (100 qty)
    df_res['qty'] = df_res['instrument'].apply(lambda inst: 250 if inst == 'NIFTY' else 100)
    df_res['rupee_pnl'] = df_res['net_points'] * df_res['qty']
    
    # Taxes & broker costs for 10 lots:
    # 09:16: 2 hedge orders
    # 10:00: 2 short orders
    # During day: SL order(s)
    # 15:00: exit orders (surviving short leg + 2 hedges)
    # Total orders = ~6 to 8 orders / day
    # Real taxes + brokerage = ~Rs. 180 on Shoonya / ~Rs. 320 on Zerodha per day
    df_res['shoonya_net'] = df_res['rupee_pnl'] - 180.0
    df_res['zerodha_net'] = df_res['rupee_pnl'] - 320.0
    
    total_rupee_gross = df_res['rupee_pnl'].sum()
    total_shoonya_net = df_res['shoonya_net'].sum()
    total_zerodha_net = df_res['zerodha_net'].sum()
    
    print(f"• Total Trading Sessions : {total_sessions} Days")
    print(f"• Win Rate               : {win_rate:.1f}% ({len(wins)} Wins / {len(losses)} Losses)")
    print(f"• Gross Realized P&L     : Rs. {total_rupee_gross:+,.0f} (10 Lots)")
    print(f"• Net on Shoonya (Rs. 5) : Rs. {total_shoonya_net:+,.0f} (After all taxes & charges)")
    print(f"• Net on Zerodha (Rs. 20): Rs. {total_zerodha_net:+,.0f} (After all taxes & brokerage)")
    
    # -------------------------------------------------------------------------
    # Breakdown by Instrument & DTE Mode
    # -------------------------------------------------------------------------
    print("\n" + "-"*95)
    print("   DETAILED BREAKDOWN BY INSTRUMENT & DTE MODE")
    print("-"*95)
    print(f"{'Category':<15} | {'Days':<5} | {'WinRate':<7} | {'Avg Pts':<10} | {'Total Pts':<11} | {'Gross Rs (10L)':<15} | {'Shoonya Net'}")
    print("-" * 95)
    
    for (inst, mode), grp in df_res.groupby(['instrument', 'mode']):
        g_wins = grp[grp['win']]
        g_rate = (len(g_wins) / len(grp)) * 100.0
        g_pts = grp['net_points'].sum()
        avg_pts = grp['net_points'].mean()
        g_rs = grp['rupee_pnl'].sum()
        g_shoonya = grp['shoonya_net'].sum()
        label = f"{inst} {mode}"
        print(f"{label:<15} | {len(grp):>4}  | {g_rate:>6.1f}% | {avg_pts:>+9.1f} | {g_pts:>+10.1f} | Rs. {g_rs:>+12,.0f} | Rs. {g_shoonya:>+10,.0f}")
        
    print("-" * 95)
    
    # -------------------------------------------------------------------------
    # Stop-Loss Hit Frequency & Leg Analysis
    # -------------------------------------------------------------------------
    single_sl_hit = df_res[(df_res['short_ce_sl_hit'] ^ df_res['short_pe_sl_hit'])]
    both_sl_hit = df_res[(df_res['short_ce_sl_hit'] & df_res['short_pe_sl_hit'])]
    neither_sl_hit = df_res[(~df_res['short_ce_sl_hit'] & ~df_res['short_pe_sl_hit'])]
    
    print("\n" + "-"*95)
    print("   STOP-LOSS HIT DYNAMICS (30% THRESHOLD)")
    print("-"*95)
    print(f"• Days with Neither SL Hit (Both decayed till 3 PM) : {len(neither_sl_hit)} days ({len(neither_sl_hit)/total_sessions*100:.1f}%)")
    print(f"• Days with Exactly One SL Hit (Surviving leg ran)  : {len(single_sl_hit)} days ({len(single_sl_hit)/total_sessions*100:.1f}%)")
    print(f"• Days with Both SLs Hit (Double Whipsaw Day)        : {len(both_sl_hit)} days ({len(both_sl_hit)/total_sessions*100:.1f}%)")
    print("="*95 + "\n")

if __name__ == '__main__':
    run_backtest()
