import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

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

def run_lvr_active_sessions(ticker, name, mcx_lot_size, tick_size, min_sl_pct=0.25):
    df_5m = yf.download(ticker, period='60d', interval='5m', progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)
    df_5m = df_5m.dropna().copy()
    if len(df_5m) < 100: return None
    
    df_5m['VWAP'] = calculate_intraday_vwap(df_5m)
    df_5m['EMA10'] = calculate_ema(df_5m['Close'], 10)
    
    dates = np.unique(df_5m.index.date)
    trades = []
    
    for session_date in dates:
        day_bars = df_5m[df_5m.index.date == session_date].copy()
        if len(day_bars) < 20: continue
        
        # Only trade during London & US Active Sessions (13:30 to 22:00 IST)
        active_bars = day_bars.between_time('13:30', '22:00')
        if len(active_bars) < 10: continue
        
        # Determine Session Sentiment at 13:30 from first 3 bars of London session
        c_open = active_bars.iloc[2]['Close']
        o_open = active_bars.iloc[0]['Open']
        vwap_open = active_bars.iloc[2]['VWAP']
        direction = 'LONG' if (c_open >= o_open and c_open >= vwap_open) else 'SHORT'
        
        # Baseline lowest volume from first 3 London bars
        baseline_v = active_bars.iloc[:3]['Volume'].replace(0, np.nan).dropna().min()
        if np.isnan(baseline_v) or baseline_v <= 0:
            baseline_v = active_bars.iloc[0]['Volume']
            if baseline_v <= 0: baseline_v = 100
        rolling_lowest_v = baseline_v
        
        armed_trigger = None
        armed_sl = None
        armed_target1 = None
        open_position = None
        day_trades_count = 0
        
        for idx in range(3, len(active_bars)):
            bar = active_bars.iloc[idx]
            ts = active_bars.index[idx]
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
                    if l <= curr_sl:
                        exit_price = curr_sl
                        exit_reason = "TRAILING_COST_SL_HIT" if partial_booked else "SPOT_SL_HIT"
                    elif not partial_booked and h >= t1:
                        pos['partial_booked'] = True
                        pos['partial_pnl'] = (t1 - entry_p) * 0.5
                        pos['current_sl'] = entry_p
                    elif partial_booked and c < ema10:
                        exit_price = c
                        exit_reason = "10_EMA_TRAIL_EXIT"
                    elif idx == len(active_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                else:
                    if h >= curr_sl:
                        exit_price = curr_sl
                        exit_reason = "TRAILING_COST_SL_HIT" if partial_booked else "SPOT_SL_HIT"
                    elif not partial_booked and l <= t1:
                        pos['partial_booked'] = True
                        pos['partial_pnl'] = (entry_p - t1) * 0.5
                        pos['current_sl'] = entry_p
                    elif partial_booked and c > ema10:
                        exit_price = c
                        exit_reason = "10_EMA_TRAIL_EXIT"
                    elif idx == len(active_bars) - 1:
                        exit_price = c
                        exit_reason = "SESSION_EOD_EXIT"
                        
                if exit_price > 0:
                    runner_pnl = ((exit_price - entry_p) if p_dir == 'LONG' else (entry_p - exit_price)) * (0.5 if partial_booked else 1.0)
                    total_pts = (pos['partial_pnl'] + runner_pnl) if partial_booked else runner_pnl
                    pnl_rs = total_pts * mcx_lot_size
                    trades.append({'total_pts': total_pts, 'pnl_rs': pnl_rs})
                    open_position = None
                    day_trades_count += 1
                    break
                    
            elif armed_trigger is not None and day_trades_count == 0:
                triggered = False
                if direction == 'LONG' and h >= armed_trigger and c > vwap:
                    triggered = True
                    spot_entry = armed_trigger
                elif direction == 'SHORT' and l <= armed_trigger and c < vwap:
                    triggered = True
                    spot_entry = armed_trigger
                    
                if triggered:
                    open_position = {
                        'direction': direction,
                        'entry_price': spot_entry,
                        'initial_sl': armed_sl,
                        'current_sl': armed_sl,
                        'target1': armed_target1,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    armed_trigger = None
                    continue
                    
            if is_opp and v > 0 and v < rolling_lowest_v and day_trades_count == 0 and open_position is None:
                if direction == 'LONG':
                    trig = h + tick_size
                    sl = l - tick_size
                    min_risk = trig * (min_sl_pct / 100.0)
                    risk = max(trig - sl, min_risk)
                    sl = trig - risk
                    t1 = trig + (risk * 2.0)
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
                rolling_lowest_v = v
            elif v > 0 and v < rolling_lowest_v:
                rolling_lowest_v = v
                
    return pd.DataFrame(trades)

if __name__ == '__main__':
    print("=" * 105)
    print("⚡ LONDON & US ACTIVE SESSIONS (13:30 to 22:00 IST) COMMODITY LVR BACKTEST")
    print("=" * 105)
    
    commodities = [
        ('CL=F', 'Crude Oil (MCX 100 bbl)', 100, 0.05, 0.35),
        ('GC=F', 'Gold (MCX 1 kg / 100g unit)', 100, 0.10, 0.25),
        ('SI=F', 'Silver (MCX 30 kg lot)', 30, 0.01, 0.35),
        ('NG=F', 'Natural Gas (MCX 1250 mmBtu)', 1250, 0.01, 0.50),
        ('HG=F', 'Copper (MCX 2500 kg lot)', 2500, 0.005, 0.30)
    ]
    
    total_pnl = 0.0
    total_trades = 0
    total_wins = 0
    
    for ticker, name, lot_size, tick_size, min_sl in commodities:
        df_res = run_lvr_active_sessions(ticker, name, lot_size, tick_size, min_sl)
        if df_res is None or len(df_res) == 0: continue
        wins = df_res[df_res['pnl_rs'] > 0]
        losses = df_res[df_res['pnl_rs'] <= 0]
        wr = len(wins) / len(df_res) * 100.0
        tot_pts = df_res['total_pts'].sum()
        tot_pnl = df_res['pnl_rs'].sum()
        pf = (wins['pnl_rs'].sum() / abs(losses['pnl_rs'].sum())) if len(losses) > 0 else 99.9
        total_pnl += tot_pnl
        total_trades += len(df_res)
        total_wins += len(wins)
        print(f"{name:<28} Trades: {len(df_res):<2} | WinRate: {wr:.1f}% | ProfitFactor: {pf:.2f} | Points: {tot_pts:<+7.2f} | PnL: ₹{tot_pnl:<+11,.2f}")
    
    print("=" * 105)
    print(f"🏆 TOTAL COMBINED COMMODITY P&L: ₹{total_pnl:+,.2f} across {total_trades} trades (Win Rate: {(total_wins/total_trades)*100:.1f}%)")
    print("=" * 105)
