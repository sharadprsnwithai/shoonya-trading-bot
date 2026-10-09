import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

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

def run_backtest_commodity(symbol, yf_ticker, mini_lot_size, inr_multiplier, price_quote_factor=1.0, risk_reward_ratio=2.0, min_risk_pct=0.001):
    # Fetch 35 days of 15m candles
    df = yf.download(yf_ticker, period='35d', interval='15m', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    
    if len(df) < 50:
        return []
        
    df.index = df.index.tz_convert('Asia/Kolkata')
    
    # Filter MCX session hours: 09:00 to 23:30 IST
    df = df[(df.index.time >= datetime.time(9, 0)) & (df.index.time <= datetime.time(23, 30))].copy()
    df['VWAP'] = calculate_intraday_vwap(df)
    
    dates = np.unique(df.index.date)
    trades = []
    
    for session_date in dates:
        day_df = df[df.index.date == session_date].copy()
        if len(day_df) < 10:
            continue
            
        # 1. 1:30 PM IST (13:30) Bias Determination
        morning_bars = day_df[day_df.index.time <= datetime.time(13, 30)]
        if len(morning_bars) < 4:
            continue
            
        c_1330 = morning_bars.iloc[-1]['Close']
        o_0900 = morning_bars.iloc[0]['Open']
        vwap_1330 = morning_bars.iloc[-1]['VWAP']
        
        # Bias Classification
        if c_1330 >= vwap_1330 and c_1330 >= o_0900:
            bias = 'BULLISH'
        elif c_1330 <= vwap_1330 and c_1330 <= o_0900:
            bias = 'BEARISH'
        else:
            bias = 'BULLISH' if c_1330 >= vwap_1330 else 'BEARISH'
            
        # Strategy Execution: 13:30 to 23:15 IST
        post_1330_bars = day_df[day_df.index.time >= datetime.time(13, 30)]
        
        armed_state = None
        trigger_price = None
        setup_vwap = None
        armed_time = None
        
        active_position = None
        day_trade_completed = False
        
        for i in range(1, len(post_1330_bars)):
            prev_bar = post_1330_bars.iloc[i - 1]
            curr_bar = post_1330_bars.iloc[i]
            bar_time = curr_bar.name.time()
            
            # A. Manage Active In-Trade Position
            if active_position is not None:
                side = active_position['side']
                entry = active_position['entry']
                sl = active_position['sl']
                target = active_position['target']
                
                exit_price = None
                exit_reason = None
                
                if bar_time >= datetime.time(23, 15):
                    exit_price = curr_bar['Close']
                    exit_reason = 'EOD_SQUAREOFF'
                elif side == 'LONG':
                    if curr_bar['Low'] <= sl:
                        exit_price = sl
                        exit_reason = 'STOP_LOSS'
                    elif curr_bar['High'] >= target:
                        exit_price = target
                        exit_reason = 'TARGET_HIT'
                elif side == 'SHORT':
                    if curr_bar['High'] >= sl:
                        exit_price = sl
                        exit_reason = 'STOP_LOSS'
                    elif curr_bar['Low'] <= target:
                        exit_price = target
                        exit_reason = 'TARGET_HIT'
                        
                if exit_price is not None:
                    pnl_pts = (exit_price - entry) if side == 'LONG' else (entry - exit_price)
                    pnl_inr = pnl_pts * inr_multiplier * mini_lot_size * price_quote_factor
                    
                    active_position['exit_time'] = curr_bar.name
                    active_position['exit_price'] = exit_price
                    active_position['exit_reason'] = exit_reason
                    active_position['pnl_pts'] = pnl_pts
                    active_position['pnl_inr'] = pnl_inr
                    trades.append(active_position)
                    
                    active_position = None
                    day_trade_completed = True
                    break
                    
                continue
                
            if day_trade_completed:
                break
                
            # B. Check Breakout Execution if ARMED
            if armed_state is not None and active_position is None:
                if armed_state == 'LONG' and curr_bar['High'] >= trigger_price:
                    entry = trigger_price
                    sl = setup_vwap
                    risk = entry - sl
                    if risk <= entry * min_risk_pct:
                        risk = entry * 0.002
                        sl = entry - risk
                    target = entry + (risk_reward_ratio * risk)
                    
                    active_position = {
                        'date': session_date,
                        'symbol': symbol,
                        'side': 'LONG',
                        'entry_time': curr_bar.name,
                        'entry': entry,
                        'sl': sl,
                        'target': target,
                        'risk_pts': risk,
                        'bias': bias
                    }
                    armed_state = None
                    continue
                elif armed_state == 'SHORT' and curr_bar['Low'] <= trigger_price:
                    entry = trigger_price
                    sl = setup_vwap
                    risk = sl - entry
                    if risk <= entry * min_risk_pct:
                        risk = entry * 0.002
                        sl = entry + risk
                    target = entry - (risk_reward_ratio * risk)
                    
                    active_position = {
                        'date': session_date,
                        'symbol': symbol,
                        'side': 'SHORT',
                        'entry_time': curr_bar.name,
                        'entry': entry,
                        'sl': sl,
                        'target': target,
                        'risk_pts': risk,
                        'bias': bias
                    }
                    armed_state = None
                    continue
                    
            # C. VWAP Crossover Evaluation (before 22:30 IST cutoff)
            if bar_time < datetime.time(22, 30) and active_position is None:
                p_close = prev_bar['Close']
                p_vwap = prev_bar['VWAP']
                c_close = curr_bar['Close']
                c_vwap = curr_bar['VWAP']
                
                if bias == 'BULLISH' and p_close <= p_vwap and c_close > c_vwap:
                    armed_state = 'LONG'
                    trigger_price = curr_bar['High']
                    setup_vwap = c_vwap
                    armed_time = curr_bar.name
                elif bias == 'BEARISH' and p_close >= p_vwap and c_close < c_vwap:
                    armed_state = 'SHORT'
                    trigger_price = curr_bar['Low']
                    setup_vwap = c_vwap
                    armed_time = curr_bar.name

    return trades

def print_summary(all_trades, title="1:2 RR"):
    if not all_trades:
        print("\nNo trades executed across the backtest period.")
        return
        
    df_trades = pd.DataFrame(all_trades)
    
    total_trades = len(df_trades)
    winning_trades = df_trades[df_trades['pnl_inr'] > 0]
    losing_trades = df_trades[df_trades['pnl_inr'] <= 0]
    
    win_rate = (len(winning_trades) / total_trades) * 100.0 if total_trades > 0 else 0.0
    total_pnl = df_trades['pnl_inr'].sum()
    
    gross_profit = winning_trades['pnl_inr'].sum() if len(winning_trades) > 0 else 0.0
    gross_loss = abs(losing_trades['pnl_inr'].sum()) if len(losing_trades) > 0 else 0.0
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else (gross_profit if gross_profit > 0 else 0.0)
    
    avg_win = winning_trades['pnl_inr'].mean() if len(winning_trades) > 0 else 0.0
    avg_loss = losing_trades['pnl_inr'].mean() if len(losing_trades) > 0 else 0.0
    
    df_trades['cum_pnl'] = df_trades['pnl_inr'].cumsum()
    df_trades['peak'] = df_trades['cum_pnl'].cummax()
    df_trades['drawdown'] = df_trades['peak'] - df_trades['cum_pnl']
    max_dd = df_trades['drawdown'].max()
    
    print("\n" + "="*80)
    print(f" 📊 COMMODITY VWAP (1:30 PM) BACKTEST RESULTS ({title}) - PAST 1 MONTH")
    print("="*80)
    print(f" Total Trades Executed : {total_trades}")
    print(f" Winning Trades        : {len(winning_trades)} ({win_rate:.1f}%)")
    print(f" Losing Trades         : {len(losing_trades)} ({100 - win_rate:.1f}%)")
    print(f" Profit Factor         : {profit_factor:.2f}")
    print(f" Net Realized P&L      : ₹{total_pnl:,.2f}")
    print(f" Average Winning Trade : ₹{avg_win:,.2f}")
    print(f" Average Losing Trade  : ₹{avg_loss:,.2f}")
    print(f" Maximum Drawdown (DD) : ₹{max_dd:,.2f}")
    print("="*80)
    
    print("\n🔍 PERFORMANCE BREAKDOWN BY COMMODITY (MINI CONTRACTS):")
    print(f"{'Commodity':<12} | {'Trades':<8} | {'Win Rate':<10} | {'Profit Factor':<14} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12}")
    print("-" * 80)
    
    for symbol, group in df_trades.groupby('symbol'):
        s_total = len(group)
        s_win = len(group[group['pnl_inr'] > 0])
        s_wr = (s_win / s_total) * 100.0 if s_total > 0 else 0.0
        s_pnl = group['pnl_inr'].sum()
        s_gp = group[group['pnl_inr'] > 0]['pnl_inr'].sum()
        s_gl = abs(group[group['pnl_inr'] <= 0]['pnl_inr'].sum())
        s_pf = (s_gp / s_gl) if s_gl > 0 else (s_gp if s_gp > 0 else 0.0)
        
        cum = group['pnl_inr'].cumsum()
        peak = cum.cummax()
        dd = (peak - cum).max()
        
        print(f"{symbol:<12} | {s_total:<8} | {s_wr:>8.1f}% | {s_pf:>14.2f} | ₹{s_pnl:>12,.2f} | ₹{dd:>10,.2f}")

if __name__ == '__main__':
    for rr in [1.5, 2.0, 2.5]:
        all_trades = []
        trades_crude = run_backtest_commodity('CRUDEOILM', 'CL=F', mini_lot_size=10, inr_multiplier=84.0, price_quote_factor=1.0, risk_reward_ratio=rr)
        trades_gold = run_backtest_commodity('GOLDM', 'GC=F', mini_lot_size=10, inr_multiplier=1.0, price_quote_factor=27.006, risk_reward_ratio=rr)
        trades_silver = run_backtest_commodity('SILVERM', 'SI=F', mini_lot_size=5, inr_multiplier=1.0, price_quote_factor=2700.65, risk_reward_ratio=rr)
        all_trades.extend(trades_crude + trades_gold + trades_silver)
        print_summary(all_trades, title=f"Risk:Reward 1:{rr:.1f}")
