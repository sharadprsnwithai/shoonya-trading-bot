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

def fetch_dxy_trend():
    d = yf.download('DX-Y.NYB', period='60d', interval='1h', progress=False)
    if isinstance(d.columns, pd.MultiIndex):
        d.columns = d.columns.get_level_values(0)
    d = d.dropna().copy()
    d['VWAP'] = calculate_intraday_vwap(d)
    return d

def run_optimized_commodity_lvr():
    print("=" * 115)
    print("💎🛢️ OPTIMIZED COMMODITY LVR BACKTEST (LAST 30 TRADING SESSIONS)")
    print("=" * 115)
    print("⚙️ Rule 1: Focused High-Performance Universe: Crude Oil, Copper, Gold, Silver, Natural Gas")
    print("⚙️ Rule 2: Active Session Timing Window: 13:30 to 21:00 IST (London & Early US Sessions)")
    print("⚙️ Rule 3: Macro DXY Alignment Filter on Bullion + Intraday VWAP & 10 EMA Trailing Exit")
    print("🛡️ Guardrails: Max 1 Attempt/Day | Min 0.25% Stop Loss Noise Floor | 1:2 RR (50% Partial Book)")
    print("=" * 115 + "\n")

    # Fetch DXY for macro filter
    dxy_df = fetch_dxy_trend()

    commodities = [
        ('CL=F', 'CRUDEOIL', 'Crude Oil (MCX 100 bbl)', 100, 0.05, 0.35),
        ('HG=F', 'COPPER', 'Copper (MCX 2500 kg lot)', 2500, 0.005, 0.30),
        ('NG=F', 'NATURALGAS', 'Natural Gas (MCX 1250 mmBtu)', 1250, 0.01, 0.50),
        ('GC=F', 'GOLD', 'Gold (MCX 100g unit)', 100, 0.10, 0.25),
        ('SI=F', 'SILVER', 'Silver (MCX 30 kg lot)', 30, 0.01, 0.35)
    ]

    all_results = []
    all_trade_list = []

    for ticker, symbol, name, mcx_lot, tick_size, min_sl_pct in commodities:
        df_5m = yf.download(ticker, period='60d', interval='5m', progress=False)
        if isinstance(df_5m.columns, pd.MultiIndex):
            df_5m.columns = df_5m.columns.get_level_values(0)
        df_5m = df_5m.dropna().copy()
        if len(df_5m) < 100: continue
        
        df_5m['VWAP'] = calculate_intraday_vwap(df_5m)
        df_5m['EMA10'] = calculate_ema(df_5m['Close'], 10)
        
        dates = np.unique(df_5m.index.date)
        # Select last 30 trading sessions
        eval_dates = dates[-30:] if len(dates) >= 30 else dates
        
        trades = []
        
        for session_date in eval_dates:
            day_bars = df_5m[df_5m.index.date == session_date].copy()
            if len(day_bars) < 20: continue
            
            # Active Session: 13:30 to 21:00 IST (London & US Active Hours)
            active_bars = day_bars.between_time('13:30', '21:00')
            if len(active_bars) < 6: continue
            
            # Session Sentiment at 13:30 from first 3 London bars
            c_open = active_bars.iloc[2]['Close']
            o_open = active_bars.iloc[0]['Open']
            vwap_open = active_bars.iloc[2]['VWAP']
            direction = 'LONG' if (c_open >= o_open and c_open >= vwap_open) else 'SHORT'
            
            # Macro DXY Filter for Gold & Silver
            if symbol in ['GOLD', 'SILVER']:
                dxy_day = dxy_df[dxy_df.index.date == session_date]
                if len(dxy_day) > 0:
                    dxy_last = dxy_day.iloc[-1]
                    dxy_bullish = (dxy_last['Close'] > dxy_last['VWAP'])
                    # If DXY is bullish (rising dollar), do not take LONG Gold/Silver
                    if direction == 'LONG' and dxy_bullish:
                        continue # Skip trade, macro headwind
                    # If DXY is bearish (falling dollar), do not take SHORT Gold/Silver
                    elif direction == 'SHORT' and not dxy_bullish:
                        continue # Skip trade, macro headwind
                        
            # Baseline lowest volume from first 3 London bars (13:30-13:45)
            baseline_v = active_bars.iloc[:3]['Volume'].replace(0, np.nan).dropna().min()
            if np.isnan(baseline_v) or baseline_v <= 0:
                baseline_v = active_bars.iloc[0]['Volume']
                if baseline_v <= 0: baseline_v = 100
            rolling_lowest_v = baseline_v
            
            armed_trigger = None
            armed_sl = None
            armed_target1 = None
            open_position = None
            
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
                time_str = ts.strftime('%H:%M')
                
                # Rule: Skip entry within EIA inventory spike (Wednesdays 19:45 to 20:15) for Crude
                is_wednesday = (session_date.weekday() == 2)
                if symbol == 'CRUDEOIL' and is_wednesday and '19:45' <= time_str <= '20:15':
                    continue
                
                is_red = (c < o)
                is_green = (c > o)
                is_opp = (direction == 'SHORT' and is_green) or (direction == 'LONG' and is_red)
                
                # A. Manage Position
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
                            exit_reason = "TRAILING_COST_SL" if partial_booked else "SPOT_SL_HIT"
                        elif not partial_booked and h >= t1:
                            pos['partial_booked'] = True
                            pos['partial_pnl'] = (t1 - entry_p) * 0.5
                            pos['current_sl'] = entry_p
                        elif partial_booked and c < ema10:
                            exit_price = c
                            exit_reason = "10_EMA_TRAIL_EXIT"
                        elif idx == len(active_bars) - 1 or time_str >= '21:00':
                            exit_price = c
                            exit_reason = "SESSION_EOD_EXIT"
                    else: # SHORT
                        if h >= curr_sl:
                            exit_price = curr_sl
                            exit_reason = "TRAILING_COST_SL" if partial_booked else "SPOT_SL_HIT"
                        elif not partial_booked and l <= t1:
                            pos['partial_booked'] = True
                            pos['partial_pnl'] = (entry_p - t1) * 0.5
                            pos['current_sl'] = entry_p
                        elif partial_booked and c > ema10:
                            exit_price = c
                            exit_reason = "10_EMA_TRAIL_EXIT"
                        elif idx == len(active_bars) - 1 or time_str >= '21:00':
                            exit_price = c
                            exit_reason = "SESSION_EOD_EXIT"
                            
                    if exit_price > 0:
                        runner_pnl = ((exit_price - entry_p) if p_dir == 'LONG' else (entry_p - exit_price)) * (0.5 if partial_booked else 1.0)
                        total_pts = (pos['partial_pnl'] + runner_pnl) if partial_booked else runner_pnl
                        pnl_rs = total_pts * mcx_lot
                        
                        trade_rec = {
                            'date': session_date,
                            'symbol': symbol,
                            'name': name,
                            'direction': p_dir,
                            'entry_time': pos['entry_time'],
                            'exit_time': ts,
                            'entry_price': entry_p,
                            'exit_price': exit_price,
                            'total_pts': total_pts,
                            'pnl_rs': pnl_rs,
                            'exit_reason': exit_reason
                        }
                        trades.append(trade_rec)
                        all_trade_list.append(trade_rec)
                        open_position = None
                        break # Max 1 trade per commodity per session
                        
                # B. Trigger Execution
                elif armed_trigger is not None:
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
                            'entry_time': ts,
                            'initial_sl': armed_sl,
                            'current_sl': armed_sl,
                            'target1': armed_target1,
                            'partial_booked': False,
                            'partial_pnl': 0.0
                        }
                        armed_trigger = None
                        continue
                        
                # C. Setup Arming on Lowest Volume Candle
                if is_opp and v > 0 and v < rolling_lowest_v and open_position is None:
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
                    
        tdf = pd.DataFrame(trades)
        if len(tdf) > 0:
            wins = tdf[tdf['pnl_rs'] > 0]
            losses = tdf[tdf['pnl_rs'] <= 0]
            wr = len(wins) / len(tdf) * 100.0
            tot_pnl = tdf['pnl_rs'].sum()
            tot_pts = tdf['total_pts'].sum()
            gross_w = wins['pnl_rs'].sum() if len(wins) > 0 else 0
            gross_l = abs(losses['pnl_rs'].sum()) if len(losses) > 0 else 0
            pf = gross_w / gross_l if gross_l > 0 else 99.9
            
            all_results.append({
                'name': name,
                'trades': len(tdf),
                'wins': len(wins),
                'losses': len(losses),
                'wr': wr,
                'pf': pf,
                'pts': tot_pts,
                'pnl': tot_pnl,
                'avg': tot_pnl / len(tdf)
            })

    # Print Summary Table
    print(f"{'Commodity':<30} {'Trades':<8} {'Win Rate':<10} {'Profit Factor':<14} {'Points':<10} {'Net PnL (₹)':<15} {'Avg/Trade (₹)':<12}")
    print("-" * 115)
    
    tot_trades_all = sum(r['trades'] for r in all_results)
    tot_wins_all = sum(r['wins'] for r in all_results)
    tot_pnl_all = sum(r['pnl'] for r in all_results)
    
    for r in all_results:
        print(f"{r['name']:<30} {r['trades']:<8} {r['wr']:<9.1f}% {r['pf']:<13.2f} {r['pts']:<+9.2f} ₹{r['pnl']:<+14,.2f} ₹{r['avg']:<+11,.2f}")
    
    print("=" * 115)
    print(f"🏆 ALL COMMODITIES COMBINED: {tot_trades_all} Trades across 30 Days | Win Rate: {(tot_wins_all/tot_trades_all)*100:.1f}% | Total Net P&L: ₹{tot_pnl_all:+,.2f}")
    print("=" * 115)

if __name__ == '__main__':
    run_optimized_commodity_lvr()
