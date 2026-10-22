"""
Backtest Engine for "The 1 and 0 NS 916 TTC Strategy" (Atul Jain / Traders Conclave)
Strategy Rules:
  - Monday: NIFTY (1 DTE, Tuesday Expiry)
  - Tuesday: NIFTY (0 DTE, Same-Day Expiry)
  - Wednesday: SENSEX (1 DTE, Thursday Expiry)
  - Thursday: SENSEX (0 DTE, Same-Day Expiry)
  - Friday: No trade.

Session 1 (Morning):
  - 09:16 AM: Buy deep OTM Protection (NIFTY: 20 strikes / ~1000 pts away; SENSEX: ~Rs. 5 OTM)
  - 09:22 AM: Sell ATM Straddle (1x CE, 1x PE) with 50% SL on each leg independently.
  - 10:16 AM (NIFTY) / 10:30 AM (SENSEX): Square off Short Straddle legs. Keep long hedges open.

Session 2 (Afternoon):
  - 14:17 PM (NIFTY) / 13:35 PM (SENSEX): Sell fresh ATM Straddle with 50% SL per leg.
  - 15:35 PM: Square off ALL 4 legs (completely flat EOD).
"""

import sys
import math
from datetime import datetime, date, time, timedelta
import pandas as pd
import numpy as np
from scipy.stats import norm
import yfinance as yf

# -----------------------------------------------------------------------------
# Intraday Black-Scholes Pricing Model
# -----------------------------------------------------------------------------
def get_intraday_tte(trade_date, current_time, expiry_date):
    """
    Calculates precise annualized Time-To-Expiry (TTE) accounting for trading hours.
    Market closes at 15:30 IST.
    """
    days_diff = (expiry_date - trade_date).days
    
    # Calculate fraction of current trading day remaining (09:15 to 15:30 = 375 mins)
    cur_mins = current_time.hour * 60 + current_time.minute
    close_mins = 15 * 60 + 30 # 930
    open_mins = 9 * 60 + 15   # 555
    
    mins_remaining_today = max(0, close_mins - cur_mins)
    frac_today = mins_remaining_today / 375.0
    
    if days_diff == 0:
        # Expiry day (0 DTE)
        trading_days_remaining = max(0.01, frac_today)
    else:
        # 1 DTE or more
        trading_days_remaining = (days_diff - 1) + frac_today
        
    return max(trading_days_remaining / 365.25, 1e-5)

def bs_option_price(spot, strike, tte, iv, opt_type='CE', r=0.065):
    """Computes European Black-Scholes price."""
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
# Single Day Simulator
# -----------------------------------------------------------------------------
def simulate_day(day_df, instrument, mode):
    """
    Simulates one full trading day of the strategy.
    instrument: 'NIFTY' or 'SENSEX'
    mode: '1DTE' or '0DTE'
    """
    trade_date = day_df['Date'].iloc[0]
    strike_step = 50.0 if instrument == 'NIFTY' else 100.0
    
    # Expiry date resolution
    if mode == '0DTE':
        expiry_date = trade_date
    else:
        expiry_date = trade_date + timedelta(days=1)
        
    # Schedule definition
    t_hedge_buy = time(9, 16)
    t_morn_entry = time(9, 22)
    t_morn_exit = time(10, 16) if instrument == 'NIFTY' else time(10, 30)
    t_aft_entry = time(14, 17) if instrument == 'NIFTY' else time(13, 35)
    t_eod_exit = time(15, 30) # 15:30 market close
    
    # Volatility estimation (approx 13% for Nifty, 12.5% for Sensex)
    base_iv = 0.13 if instrument == 'NIFTY' else 0.125
    
    # Positions state
    long_ce_strike = None
    long_ce_entry = 0.0
    long_pe_strike = None
    long_pe_entry = 0.0
    
    # Morning short straddle
    morn_ce_strike = None
    morn_ce_entry = 0.0
    morn_ce_sl = 0.0
    morn_ce_active = False
    morn_ce_pnl = 0.0
    
    morn_pe_strike = None
    morn_pe_entry = 0.0
    morn_pe_sl = 0.0
    morn_pe_active = False
    morn_pe_pnl = 0.0
    
    # Afternoon short straddle
    aft_ce_strike = None
    aft_ce_entry = 0.0
    aft_ce_sl = 0.0
    aft_ce_active = False
    aft_ce_pnl = 0.0
    
    aft_pe_strike = None
    aft_pe_entry = 0.0
    aft_pe_sl = 0.0
    aft_pe_active = False
    aft_pe_pnl = 0.0
    
    morn_traded = False
    morn_exited = False
    aft_traded = False
    
    # Transaction cost in index points per leg
    FEE_PER_LEG = 1.0 if instrument == 'NIFTY' else 2.5
    
    for idx in range(len(day_df)):
        bar = day_df.iloc[idx]
        t = bar['Time']
        spot = bar['Close']
        high = bar['High']
        low = bar['Low']
        
        tte = get_intraday_tte(trade_date, t, expiry_date)
        
        # 1. 09:16 AM: Buy Protection Wings
        if long_ce_strike is None and t >= t_hedge_buy:
            atm = round(spot / strike_step) * strike_step
            if instrument == 'NIFTY':
                # 20 strikes away = 1000 pts
                long_pe_strike = atm - 1000.0
                long_ce_strike = atm + 1000.0
            else:
                # SENSEX: nearest strike with premium >= Rs. 5
                long_pe_strike = atm - 1500.0
                long_ce_strike = atm + 1500.0
                
            long_ce_entry = bs_option_price(spot, long_ce_strike, tte, base_iv, 'CE') + FEE_PER_LEG
            long_pe_entry = bs_option_price(spot, long_pe_strike, tte, base_iv, 'PE') + FEE_PER_LEG
            
        # 2. 09:22 AM: Sell Morning ATM Straddle
        if not morn_traded and t >= t_morn_entry and t < t_morn_exit:
            atm = round(spot / strike_step) * strike_step
            morn_ce_strike = atm
            morn_pe_strike = atm
            
            morn_ce_entry = bs_option_price(spot, morn_ce_strike, tte, base_iv, 'CE') - FEE_PER_LEG
            morn_pe_entry = bs_option_price(spot, morn_pe_strike, tte, base_iv, 'PE') - FEE_PER_LEG
            
            # 50% Stop Loss per leg
            morn_ce_sl = morn_ce_entry * 1.50
            morn_pe_sl = morn_pe_entry * 1.50
            
            morn_ce_active = True
            morn_pe_active = True
            morn_traded = True
            
        # 3. Monitor Morning Straddle Stop Losses
        if morn_traded and not morn_exited:
            # Check high/low extremes during bar for SL triggers
            worst_spot_for_ce = high
            worst_spot_for_pe = low
            
            cur_ce_price = bs_option_price(worst_spot_for_ce, morn_ce_strike, tte, base_iv, 'CE')
            cur_pe_price = bs_option_price(worst_spot_for_pe, morn_pe_strike, tte, base_iv, 'PE')
            
            if morn_ce_active and cur_ce_price >= morn_ce_sl:
                morn_ce_pnl = morn_ce_entry - morn_ce_sl - FEE_PER_LEG
                morn_ce_active = False
                
            if morn_pe_active and cur_pe_price >= morn_pe_sl:
                morn_pe_pnl = morn_pe_entry - morn_pe_sl - FEE_PER_LEG
                morn_pe_active = False
                
        # 4. 10:16 AM (NIFTY) / 10:30 AM (SENSEX): Exit Morning Short Straddle
        if morn_traded and not morn_exited and t >= t_morn_exit:
            if morn_ce_active:
                exit_p = bs_option_price(spot, morn_ce_strike, tte, base_iv, 'CE')
                morn_ce_pnl = (morn_ce_entry - exit_p) - FEE_PER_LEG
                morn_ce_active = False
                
            if morn_pe_active:
                exit_p = bs_option_price(spot, morn_pe_strike, tte, base_iv, 'PE')
                morn_pe_pnl = (morn_pe_entry - exit_p) - FEE_PER_LEG
                morn_pe_active = False
                
            morn_exited = True
            
        # 5. Afternoon Entry: 14:17 (NIFTY) / 13:35 (SENSEX)
        if not aft_traded and t >= t_aft_entry and t < t_eod_exit:
            atm = round(spot / strike_step) * strike_step
            aft_ce_strike = atm
            aft_pe_strike = atm
            
            aft_ce_entry = bs_option_price(spot, aft_ce_strike, tte, base_iv, 'CE') - FEE_PER_LEG
            aft_pe_entry = bs_option_price(spot, aft_pe_strike, tte, base_iv, 'PE') - FEE_PER_LEG
            
            # 50% Stop Loss per leg
            aft_ce_sl = aft_ce_entry * 1.50
            aft_pe_sl = aft_pe_entry * 1.50
            
            aft_ce_active = True
            aft_pe_active = True
            aft_traded = True
            
        # 6. Monitor Afternoon Straddle Stop Losses
        if aft_traded:
            worst_spot_for_ce = high
            worst_spot_for_pe = low
            
            cur_ce_price = bs_option_price(worst_spot_for_ce, aft_ce_strike, tte, base_iv, 'CE')
            cur_pe_price = bs_option_price(worst_spot_for_pe, aft_pe_strike, tte, base_iv, 'PE')
            
            if aft_ce_active and cur_ce_price >= aft_ce_sl:
                aft_ce_pnl = aft_ce_entry - aft_ce_sl - FEE_PER_LEG
                aft_ce_active = False
                
            if aft_pe_active and cur_pe_price >= aft_pe_sl:
                aft_pe_pnl = aft_pe_entry - aft_pe_sl - FEE_PER_LEG
                aft_pe_active = False
                
    # 7. End of Day Square-off (Close surviving afternoon legs & long hedges)
    final_bar = day_df.iloc[-1]
    final_spot = final_bar['Close']
    final_tte = get_intraday_tte(trade_date, time(15, 30), expiry_date)
    
    if aft_ce_active:
        exit_p = bs_option_price(final_spot, aft_ce_strike, final_tte, base_iv, 'CE')
        aft_ce_pnl = (aft_ce_entry - exit_p) - FEE_PER_LEG
        
    if aft_pe_active:
        exit_p = bs_option_price(final_spot, aft_pe_strike, final_tte, base_iv, 'PE')
        aft_pe_pnl = (aft_pe_entry - exit_p) - FEE_PER_LEG
        
    # Close long hedges
    long_ce_exit = bs_option_price(final_spot, long_ce_strike, final_tte, base_iv, 'CE')
    long_ce_pnl = (long_ce_exit - long_ce_entry) - FEE_PER_LEG
    
    long_pe_exit = bs_option_price(final_spot, long_pe_strike, final_tte, base_iv, 'PE')
    long_pe_pnl = (long_pe_exit - long_pe_entry) - FEE_PER_LEG
    
    hedge_total_pnl = long_ce_pnl + long_pe_pnl
    short_total_pnl = morn_ce_pnl + morn_pe_pnl + aft_ce_pnl + aft_pe_pnl
    net_day_points = short_total_pnl + hedge_total_pnl
    
    return {
        'date': trade_date,
        'instrument': instrument,
        'mode': mode,
        'morning_ce_pnl': morn_ce_pnl,
        'morning_pe_pnl': morn_pe_pnl,
        'afternoon_ce_pnl': aft_ce_pnl,
        'afternoon_pe_pnl': aft_pe_pnl,
        'short_total_pnl': short_total_pnl,
        'hedge_pnl': hedge_total_pnl,
        'net_points': net_day_points,
        'win': net_day_points > 0
    }

# -----------------------------------------------------------------------------
# Main Simulation Orchestrator
# -----------------------------------------------------------------------------
def run_all():
    print("="*85)
    print("   THE 1 & 0 NS 916 TTC STRATEGY: REALISTIC INTRADAY STRADDLE BACKTEST")
    print("="*85)
    
    df_nifty = fetch_5m_data('^NSEI')
    df_sensex = fetch_5m_data('^BSESN')
    
    results = []
    
    # Group Nifty by Date
    nifty_days = {d: g for d, g in df_nifty.groupby('Date')}
    sensex_days = {d: g for d, g in df_sensex.groupby('Date')}
    
    # All distinct trading dates
    all_dates = sorted(list(set(list(nifty_days.keys()) + list(sensex_days.keys()))))
    
    print(f"\nSimulating {len(all_dates)} calendar trading sessions...")
    
    for d in all_dates:
        weekday = d.weekday() # 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri
        
        if weekday == 0 and d in nifty_days:
            # Monday: NIFTY 1 DTE
            r = simulate_day(nifty_days[d], 'NIFTY', '1DTE')
            results.append(r)
        elif weekday == 1 and d in nifty_days:
            # Tuesday: NIFTY 0 DTE
            r = simulate_day(nifty_days[d], 'NIFTY', '0DTE')
            results.append(r)
        elif weekday == 2 and d in sensex_days:
            # Wednesday: SENSEX 1 DTE
            r = simulate_day(sensex_days[d], 'SENSEX', '1DTE')
            results.append(r)
        elif weekday == 3 and d in sensex_days:
            # Thursday: SENSEX 0 DTE
            r = simulate_day(sensex_days[d], 'SENSEX', '0DTE')
            results.append(r)
            
    df_res = pd.DataFrame(results)
    
    if df_res.empty:
        print("No sessions simulated.")
        return
        
    # Performance metrics
    total_days = len(df_res)
    win_days = df_res[df_res['win']]
    loss_days = df_res[~df_res['win']]
    
    win_rate = (len(win_days) / total_days) * 100.0
    
    nifty_res = df_res[df_res['instrument'] == 'NIFTY']
    sensex_res = df_res[df_res['instrument'] == 'SENSEX']
    
    total_nifty_pts = nifty_res['net_points'].sum()
    total_sensex_pts = sensex_res['net_points'].sum()
    
    # Financial translation:
    # NIFTY lot size: 25. 5 lots = 125 qty.
    # SENSEX lot size: 10. 5 lots = 50 qty.
    # Video used 5 lots (~Rs. 6.3 Lakh margin)
    nifty_pnl_rs = total_nifty_pts * 125
    sensex_pnl_rs = total_sensex_pts * 50
    total_pnl_rs = nifty_pnl_rs + sensex_pnl_rs
    
    print("\n" + "="*85)
    print("   OVERALL PERFORMANCE REPORT")
    print("="*85)
    print(f"• Total Trading Sessions : {total_days} Days")
    print(f"• Win Rate               : {win_rate:.1f}% ({len(win_days)} Green Days / {len(loss_days)} Red Days)")
    print(f"• NIFTY Net Points       : {total_nifty_pts:+,.1f} pts ({len(nifty_res)} sessions, {len(nifty_res[nifty_res['win']])}W/{len(nifty_res[~nifty_res['win']])}L)")
    print(f"• SENSEX Net Points      : {total_sensex_pts:+,.1f} pts ({len(sensex_res)} sessions, {len(sensex_res[sensex_res['win']])}W/{len(sensex_res[~sensex_res['win']])}L)")
    print(f"• Total Realized P&L     : Rs. {total_pnl_rs:+,.0f} (on ~Rs. 6.3L capital)")
    print(f"• Approx Return on Margin: {(total_pnl_rs / 630000.0) * 100.0:+.1f}% across tested period")
    
    # Breakdown by DTE Mode
    print("\n" + "-"*85)
    print("   BREAKDOWN BY INSTRUMENT & DTE MODE")
    print("-"*85)
    for (inst, mode), grp in df_res.groupby(['instrument', 'mode']):
        g_wins = grp[grp['win']]
        g_pts = grp['net_points'].sum()
        g_rate = (len(g_wins) / len(grp)) * 100.0
        print(f"• {inst:<6} {mode:<5} : {len(grp):>2} sessions | Win Rate: {g_rate:>5.1f}% | Net Points: {g_pts:>+8.1f} pts")
        
    # Breakdown by Session Legs
    print("\n" + "-"*85)
    print("   LEG BREAKDOWN (SHORT STRADDLE vs LONG HEDGE BLEED)")
    print("-"*85)
    print(f"• Morning Short Straddles   : {df_res['morning_ce_pnl'].sum() + df_res['morning_pe_pnl'].sum():>+10.1f} pts")
    print(f"• Afternoon Short Straddles : {df_res['afternoon_ce_pnl'].sum() + df_res['afternoon_pe_pnl'].sum():>+10.1f} pts")
    print(f"• Gross Short Straddle Gain : {df_res['short_total_pnl'].sum():>+10.1f} pts")
    print(f"• Long Hedge Decay Cost     : {df_res['hedge_pnl'].sum():>+10.1f} pts (Protection Drag)")
    print(f"• Net Strategy Points       : {df_res['net_points'].sum():>+10.1f} pts")
    print("="*85 + "\n")

if __name__ == '__main__':
    run_all()
