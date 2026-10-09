import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def calculate_ema(series, span=10):
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

def run_silver_backtest(period='59d', interval='15m', risk_reward_ratio=2.5, mini_lot_size=5, use_runner_mode=True):
    df = yf.download('SI=F', period=period, interval=interval, progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    
    if len(df) < 50:
        return []
        
    df.index = df.index.tz_convert('Asia/Kolkata')
    df = df[(df.index.time >= datetime.time(9, 0)) & (df.index.time <= datetime.time(23, 30))].copy()
    df['VWAP'] = calculate_intraday_vwap(df)
    df['EMA10'] = calculate_ema(df['Close'], 10)
    
    # Conversion factor for MCX Silver: 1 oz = 31.1035g -> 1kg = 32.15075 oz. Rate USD/INR ~84.0
    price_factor = 32.15075 * 84.0
    
    dates = np.unique(df.index.date)
    trades = []
    
    for session_date in dates:
        day_df = df[df.index.date == session_date].copy()
        if len(day_df) < 8:
            continue
            
        # 1. 1:30 PM IST (13:30) Bias Determination
        morning_bars = day_df[day_df.index.time <= datetime.time(13, 30)]
        if len(morning_bars) < 3:
            continue
            
        c_1330 = morning_bars.iloc[-1]['Close']
        o_0900 = morning_bars.iloc[0]['Open']
        vwap_1330 = morning_bars.iloc[-1]['VWAP']
        
        if c_1330 >= vwap_1330 and c_1330 >= o_0900:
            bias = 'BULLISH'
        elif c_1330 <= vwap_1330 and c_1330 <= o_0900:
            bias = 'BEARISH'
        else:
            bias = 'BULLISH' if c_1330 >= vwap_1330 else 'BEARISH'
            
        post_1330_bars = day_df[day_df.index.time >= datetime.time(13, 30)]
        
        armed_state = None
        trigger_price = None
        setup_vwap = None
        
        active_position = None
        day_trade_completed = False
        
        for i in range(1, len(post_1330_bars)):
            prev_bar = post_1330_bars.iloc[i - 1]
            curr_bar = post_1330_bars.iloc[i]
            bar_time = curr_bar.name.time()
            ema10 = curr_bar['EMA10']
            
            # A. Manage Active In-Trade Position
            if active_position is not None:
                side = active_position['side']
                entry = active_position['entry']
                curr_sl = active_position['current_sl']
                target = active_position['target']
                partial_booked = active_position['partial_booked']
                
                # Phase 1: Check Target 1 (1:2.5 RR)
                if not partial_booked:
                    hit_target = (curr_bar['High'] >= target) if side == 'LONG' else (curr_bar['Low'] <= target)
                    hit_sl = (curr_bar['Low'] <= curr_sl) if side == 'LONG' else (curr_bar['High'] >= curr_sl)
                    
                    if hit_target:
                        if use_runner_mode:
                            # 50% Partial booked at Target 1, SL moved to Cost (Entry)
                            active_position['partial_booked'] = True
                            active_position['partial_exit_time'] = curr_bar.name
                            active_position['partial_exit_price'] = target
                            active_position['partial_pnl'] = (target - entry if side == 'LONG' else entry - target) * price_factor * (mini_lot_size * 0.5)
                            active_position['current_sl'] = entry # SL moved to Cost
                            # Continue to trail runner
                        else:
                            # 100% Full exit
                            pnl_pts = (target - entry) if side == 'LONG' else (entry - target)
                            pnl_inr = pnl_pts * price_factor * mini_lot_size
                            active_position['exit_time'] = curr_bar.name
                            active_position['exit_price_inr'] = target * price_factor
                            active_position['exit_reason'] = 'TARGET_HIT'
                            active_position['pnl_inr'] = pnl_inr
                            trades.append(active_position)
                            active_position = None
                            day_trade_completed = True
                            break
                    elif hit_sl:
                        pnl_pts = (curr_sl - entry) if side == 'LONG' else (entry - curr_sl)
                        pnl_inr = pnl_pts * price_factor * mini_lot_size
                        active_position['exit_time'] = curr_bar.name
                        active_position['exit_price_inr'] = curr_sl * price_factor
                        active_position['exit_reason'] = 'STOP_LOSS'
                        active_position['pnl_inr'] = pnl_inr
                        trades.append(active_position)
                        active_position = None
                        day_trade_completed = True
                        break
                        
                # Phase 2: Trailing Runner (after 50% booked)
                if active_position is not None and active_position['partial_booked']:
                    # Update dynamic 10 EMA SL
                    if side == 'LONG':
                        if ema10 > active_position['current_sl']:
                            active_position['current_sl'] = ema10
                    elif side == 'SHORT':
                        if ema10 < active_position['current_sl']:
                            active_position['current_sl'] = ema10
                            
                    hit_trail_sl = (curr_bar['Low'] <= active_position['current_sl']) if side == 'LONG' else (curr_bar['High'] >= active_position['current_sl'])
                    is_eod = (bar_time >= datetime.time(23, 15))
                    
                    if hit_trail_sl or is_eod:
                        runner_exit = active_position['current_sl'] if hit_trail_sl else curr_bar['Close']
                        runner_reason = 'RUNNER_10EMA_TRAIL' if hit_trail_sl else 'EOD_SQUAREOFF'
                        runner_pnl = (runner_exit - entry if side == 'LONG' else entry - runner_exit) * price_factor * (mini_lot_size * 0.5)
                        total_pnl = active_position['partial_pnl'] + runner_pnl
                        
                        active_position['exit_time'] = curr_bar.name
                        active_position['exit_price_inr'] = runner_exit * price_factor
                        active_position['exit_reason'] = runner_reason
                        active_position['runner_pnl'] = runner_pnl
                        active_position['pnl_inr'] = total_pnl
                        trades.append(active_position)
                        active_position = None
                        day_trade_completed = True
                        break
                        
                continue
                
            if day_trade_completed:
                break
                
            # B. Breakout Execution if ARMED
            if armed_state is not None and active_position is None:
                if armed_state == 'LONG' and curr_bar['High'] >= trigger_price:
                    entry = trigger_price
                    sl = setup_vwap
                    risk = entry - sl
                    if risk <= entry * 0.001:
                        risk = entry * 0.002
                        sl = entry - risk
                    target = entry + (risk_reward_ratio * risk)
                    
                    active_position = {
                        'date': session_date,
                        'symbol': 'SILVERM',
                        'side': 'LONG',
                        'entry_time': curr_bar.name,
                        'entry_inr': entry * price_factor,
                        'entry': entry,
                        'current_sl': sl,
                        'initial_sl': sl,
                        'target': target,
                        'risk_pts': risk,
                        'bias': bias,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    armed_state = None
                    continue
                elif armed_state == 'SHORT' and curr_bar['Low'] <= trigger_price:
                    entry = trigger_price
                    sl = setup_vwap
                    risk = sl - entry
                    if risk <= entry * 0.001:
                        risk = entry * 0.002
                        sl = entry + risk
                    target = entry - (risk_reward_ratio * risk)
                    
                    active_position = {
                        'date': session_date,
                        'symbol': 'SILVERM',
                        'side': 'SHORT',
                        'entry_time': curr_bar.name,
                        'entry_inr': entry * price_factor,
                        'entry': entry,
                        'current_sl': sl,
                        'initial_sl': sl,
                        'target': target,
                        'risk_pts': risk,
                        'bias': bias,
                        'partial_booked': False,
                        'partial_pnl': 0.0
                    }
                    armed_state = None
                    continue
                    
            # C. VWAP Crossover Check
            if bar_time < datetime.time(22, 30) and active_position is None:
                p_close = prev_bar['Close']
                p_vwap = prev_bar['VWAP']
                c_close = curr_bar['Close']
                c_vwap = curr_bar['VWAP']
                
                if bias == 'BULLISH' and p_close <= p_vwap and c_close > c_vwap:
                    armed_state = 'LONG'
                    trigger_price = curr_bar['High']
                    setup_vwap = c_vwap
                elif bias == 'BEARISH' and p_close >= p_vwap and c_close < c_vwap:
                    armed_state = 'SHORT'
                    trigger_price = curr_bar['Low']
                    setup_vwap = c_vwap

    return trades

def print_summary_comparison(trades_partial, trades_full):
    df_p = pd.DataFrame(trades_partial)
    df_f = pd.DataFrame(trades_full)
    
    def calc_metrics(df):
        total = len(df)
        wins = df[df['pnl_inr'] > 0]
        losses = df[df['pnl_inr'] <= 0]
        wr = len(wins) / total * 100.0 if total > 0 else 0
        pnl = df['pnl_inr'].sum()
        gp = wins['pnl_inr'].sum() if len(wins) > 0 else 0
        gl = abs(losses['pnl_inr'].sum()) if len(losses) > 0 else 0
        pf = gp / gl if gl > 0 else 0
        avg_w = wins['pnl_inr'].mean() if len(wins) > 0 else 0
        avg_l = losses['pnl_inr'].mean() if len(losses) > 0 else 0
        cum = df['pnl_inr'].cumsum()
        peak = cum.cummax()
        dd = (peak - cum).max()
        return total, len(wins), wr, pf, pnl, avg_w, avg_l, dd
        
    t_p, w_p, wr_p, pf_p, pnl_p, aw_p, al_p, dd_p = calc_metrics(df_p)
    t_f, w_f, wr_f, pf_f, pnl_f, aw_f, al_f, dd_f = calc_metrics(df_f)
    
    print("\n" + "="*95)
    print(" 📊 SILVER MINI (SILVERM) - 3-MONTH STRATEGY COMPARISON (LAST 60 TRADING DAYS)")
    print("="*95)
    print(f"{'Performance Metric':<30} | {'50% Target 1 + 10 EMA Trailing':<32} | {'100% Full Exit at Target 1':<25}")
    print("-" * 95)
    print(f"{'Total Trades Executed':<30} | {t_p:<32} | {t_f:<25}")
    print(f"{'Winning Trades':<30} | {w_p} ({wr_p:.1f}%)" + " "*19 + f"| {w_f} ({wr_f:.1f}%)")
    print(f"{'Profit Factor':<30} | {pf_p:<32.2f} | {pf_f:<25.2f}")
    print(f"{'Net Realized P&L':<30} | ₹{pnl_p:>28,.2f} | ₹{pnl_f:>21,.2f}")
    print(f"{'Average Winning Trade':<30} | ₹{aw_p:>28,.2f} | ₹{aw_f:>21,.2f}")
    print(f"{'Average Losing Trade':<30} | ₹{al_p:>28,.2f} | ₹{al_f:>21,.2f}")
    print(f"{'Maximum Drawdown (DD)':<30} | ₹{dd_p:>28,.2f} | ₹{dd_f:>21,.2f}")
    print(f"{'Return / Max DD Ratio':<30} | {(pnl_p/dd_p):>28.2f}x | {(pnl_f/dd_f):>21.2f}x")
    print("="*95)

    print("\n📋 DETAILED TRADE LOG WITH 50% PARTIAL BOOKING & 10 EMA RUNNER TRAIL:")
    print(f"{'Date':<12} | {'Time':<6} | {'Side':<6} | {'Entry (₹)':<12} | {'Exit (₹)':<12} | {'Exit Reason':<20} | {'Net P&L (₹)':<12}")
    print("-" * 95)
    for _, t in df_p.iterrows():
        t_time = pd.to_datetime(t['entry_time']).strftime('%H:%M')
        print(f"{str(t['date']):<12} | {t_time:<6} | {t['side']:<6} | ₹{t['entry_inr']:>10,.1f} | ₹{t['exit_price_inr']:>10,.1f} | {t['exit_reason']:<20} | ₹{t['pnl_inr']:>10,.2f}")

if __name__ == '__main__':
    trades_runner = run_silver_backtest(period='59d', interval='15m', risk_reward_ratio=2.5, mini_lot_size=5, use_runner_mode=True)
    trades_full = run_silver_backtest(period='59d', interval='15m', risk_reward_ratio=2.5, mini_lot_size=5, use_runner_mode=False)
    print_summary_comparison(trades_runner, trades_full)
