import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_ema(series, span):
    return series.ewm(span=span, adjust=False).mean()

def calculate_intraday_vwap(df):
    vwaps = []
    for date, group in df.groupby(df.index.date):
        vol = group['Volume'].replace(0, 1)
        typical = (group['High'] + group['Low'] + group['Close']) / 3.0
        cum_vp = (typical * vol).cumsum()
        cum_vol = vol.cumsum()
        vwap = cum_vp / cum_vol
        vwaps.append(vwap)
    return pd.concat(vwaps)

def run_lvr_on_commodity(ticker, name, mcx_lot_size, tick_size, min_sl_pct=0.25):
    # Fetch 60 days of 5m candles
    df_5m = yf.download(ticker, period='60d', interval='5m', progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)
    df_5m = df_5m.dropna().copy()
    
    if len(df_5m) < 100:
        return None
        
    df_5m['VWAP'] = calculate_intraday_vwap(df_5m)
    df_5m['EMA10'] = calculate_ema(df_5m['Close'], 10)
    
    dates = np.unique(df_5m.index.date)
    trades = []
    
    for session_date in dates:
        day_bars = df_5m[df_5m.index.date == session_date].copy()
        if len(day_bars) < 15:
            continue
            
        # Determine Session Sentiment from opening candles vs VWAP
        open_bars = day_bars.iloc[:6]
        open_price = open_bars.iloc[0]['Open']
        c_open = open_bars.iloc[-1]['Close']
        vwap_open = open_bars.iloc[-1]['VWAP']
        
        direction = 'LONG' if (c_open >= open_price and c_open >= vwap_open) else 'SHORT'
        
        # LVR Candle Engine
        # 1. Baseline Lowest Volume from first 3 bars
        baseline_v = day_bars.iloc[:3]['Volume'].replace(0, np.nan).dropna().min()
        if np.isnan(baseline_v) or baseline_v <= 0:
            baseline_v = day_bars.iloc[0]['Volume']
            if baseline_v <= 0: baseline_v = 100
            
        rolling_lowest_v = baseline_v
        
        armed_trigger = None
        armed_sl = None
        armed_target1 = None
        armed_time = None
        
        open_position = None
        day_trades_count = 0
        
        for idx in range(3, len(day_bars)):
            bar = day_bars.iloc[idx]
            ts = day_bars.index[idx]
            c = bar['Close']
            o = bar['Open']
            h = bar['High']
            l = bar['Low']
            v = bar['Volume']
            vwap = bar['VWAP']
            ema10 = bar['EMA10']
            
            is_red = (c < o)
            is_green = (c > o)
            is_opp = (direction == 'SHORT' and is_green) or (direction == 'LONG' and is_red)
            
            # A. Manage Active Open Position
            if open_position is not None:
                pos = open_position
                p_dir = pos['direction']
                entry_p = pos['entry_price']
                curr_sl = pos['current_sl']
                t1 = pos['target1']
                partial_booked = pos['partial_booked']
                
                exit_price = 0.0
                exit_reason = ""
                
                if p_dir == 'LONG':
                    # 1. Check Stop Loss
                    if l <= curr_sl:
                        exit_price = curr_sl
                        exit_reason = "TRAILING_COST_SL_HIT" if partial_booked else "SPOT_SL_HIT"
                    # 2. Check 1:2 Target (Partial 50% Book)
                    elif not partial_booked and h >= t1:
                        pos['partial_booked'] = True
                        pos['partial_pnl'] = (t1 - entry_p) * 0.5
                        pos['current_sl'] = entry_p # Move SL to Cost
                    # 3. Trailing Exit on 10 EMA (Post Partial Book)
                    elif partial_booked and c < ema10:
                        exit_price = c
                        exit_reason = "10_EMA_TRAIL_EXIT"
                    # 4. EOD Exit at session close
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "EOD_SESSION_EXIT"
                else: # SHORT
                    if h >= curr_sl:
                        exit_price = curr_sl
                        exit_reason = "TRAILING_COST_SL_HIT" if partial_booked else "SPOT_SL_HIT"
                    elif not partial_booked and l <= t1:
                        pos['partial_booked'] = True
                        pos['partial_pnl'] = (entry_p - t1) * 0.5
                        pos['current_sl'] = entry_p # Move SL to Cost
                    elif partial_booked and c > ema10:
                        exit_price = c
                        exit_reason = "10_EMA_TRAIL_EXIT"
                    elif idx == len(day_bars) - 1:
                        exit_price = c
                        exit_reason = "EOD_SESSION_EXIT"
                        
                if exit_price > 0:
                    runner_pnl = ((exit_price - entry_p) if p_dir == 'LONG' else (entry_p - exit_price)) * (0.5 if partial_booked else 1.0)
                    total_pts = (pos['partial_pnl'] + runner_pnl) if partial_booked else runner_pnl
                    pnl_rs = total_pts * mcx_lot_size
                    
                    trades.append({
                        'date': session_date,
                        'name': name,
                        'direction': p_dir,
                        'entry_time': pos['entry_time'],
                        'exit_time': ts,
                        'entry_price': entry_p,
                        'exit_price': exit_price,
                        'partial_booked': partial_booked,
                        'total_pts': total_pts,
                        'pnl_rs': pnl_rs,
                        'exit_reason': exit_reason
                    })
                    open_position = None
                    day_trades_count += 1
                    break # Max 1 trade per commodity per day
                    
            # B. Check Armed Trigger Execution
            elif armed_trigger is not None and day_trades_count == 0:
                triggered = False
                spot_entry = 0.0
                
                if direction == 'LONG' and h >= armed_trigger:
                    # VWAP Confirmation: Spot > VWAP
                    if c > vwap:
                        triggered = True
                        spot_entry = armed_trigger
                elif direction == 'SHORT' and l <= armed_trigger:
                    # VWAP Confirmation: Spot < VWAP
                    if c < vwap:
                        triggered = True
                        spot_entry = armed_trigger
                        
                if triggered:
                    open_position = {
                        'direction': direction,
                        'entry_price': spot_entry,
                        'entry_time': ts,
                        'initial_sl': armed_sl,
                        'current_sl': armed_sl,
                        'target1': armed_target1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    armed_trigger = None
                    continue
                    
            # C. Discover & Arm Lowest Volume Candle
            if is_opp and v > 0 and v < rolling_lowest_v and day_trades_count == 0 and open_position is None:
                if direction == 'LONG':
                    trig = h + tick_size
                    sl = l - tick_size
                    min_risk = trig * (min_sl_pct / 100.0)
                    risk = max(trig - sl, min_risk)
                    sl = trig - risk
                    t1 = trig + (risk * 2.0) # 1:2 RR
                else:
                    trig = l - tick_size
                    sl = h + tick_size
                    min_risk = trig * (min_sl_pct / 100.0)
                    risk = max(sl - trig, min_risk)
                    sl = trig + risk
                    t1 = trig - (risk * 2.0)
                    
                armed_trigger = trig
                armed_sl = sl
                armed_target1 = t1
                armed_time = ts
                rolling_lowest_v = v
            elif v > 0 and v < rolling_lowest_v:
                rolling_lowest_v = v
                
    return pd.DataFrame(trades)

if __name__ == '__main__':
    print("=" * 105)
    print("🛢️💰 LOWEST VOLUME REVERSAL (LVR) APPLIED TO GLOBAL / MCX COMMODITIES")
    print("=" * 105)
    print("⚙️ Rules: 5m Lowest Volume Setup | VWAP Confirmation | 1:2 RR (50% Partial Book) + 10 EMA Trail")
    print("🛡️ Guardrails: Max 1 Trade/Day | Min 0.25% SL Noise Floor | Hard EOD Session Close")
    print("=" * 105 + "\n")
    
    commodities = [
        ('CL=F', 'Crude Oil (MCX 100 bbl)', 100, 0.05, 0.35),
        ('GC=F', 'Gold (MCX 1 kg / 100g unit)', 100, 0.10, 0.25),
        ('SI=F', 'Silver (MCX 30 kg lot)', 30, 0.01, 0.35),
        ('NG=F', 'Natural Gas (MCX 1250 mmBtu)', 1250, 0.01, 0.50),
        ('HG=F', 'Copper (MCX 2500 kg lot)', 2500, 0.005, 0.30)
    ]
    
    overall_pnl = 0.0
    overall_trades = 0
    overall_wins = 0
    overall_losses = 0
    
    print(f"{'Commodity':<28} {'Trades':<8} {'Win Rate':<10} {'Profit Factor':<14} {'Points':<10} {'Net PnL (₹)':<14} {'Avg Trade (₹)':<12}")
    print("-" * 105)
    
    for ticker, name, lot_size, tick_size, min_sl in commodities:
        df_res = run_lvr_on_commodity(ticker, name, lot_size, tick_size, min_sl)
        if df_res is None or len(df_res) == 0:
            print(f"{name:<28} No trades generated.")
            continue
            
        wins = df_res[df_res['pnl_rs'] > 0]
        losses = df_res[df_res['pnl_rs'] <= 0]
        wr = len(wins) / len(df_res) * 100.0
        tot_pts = df_res['total_pts'].sum()
        tot_pnl = df_res['pnl_rs'].sum()
        
        gross_w = wins['pnl_rs'].sum() if len(wins) > 0 else 0
        gross_l = abs(losses['pnl_rs'].sum()) if len(losses) > 0 else 0
        pf = gross_w / gross_l if gross_l > 0 else 99.9
        avg_trade = tot_pnl / len(df_res)
        
        overall_pnl += tot_pnl
        overall_trades += len(df_res)
        overall_wins += len(wins)
        overall_losses += len(losses)
        
        print(f"{name:<28} {len(df_res):<8} {wr:<9.1f}% {pf:<13.2f} {tot_pts:<+9.2f} ₹{tot_pnl:<+13,.2f} ₹{avg_trade:<+11,.2f}")
        
    print("=" * 105)
    total_wr = (overall_wins / overall_trades * 100.0) if overall_trades > 0 else 0
    print(f"🏆 ALL COMMODITIES COMBINED: {overall_trades} Trades | Win Rate: {total_wr:.1f}% | Total Net P&L: ₹{overall_pnl:+,.2f} (1 Lot per commodity)")
    print("=" * 105)
