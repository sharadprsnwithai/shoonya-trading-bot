import sys
import io

# Set UTF-8 encoding for Windows stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import yfinance as yf
import pandas as pd
import numpy as np
import datetime

def run_recent_backtest(days_back=50, symbol='^NSEI', lot_size=65):
    print("="*80)
    print(f"[BACKTEST] Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy")
    print(f"Target Window: Last 2 Months (~{days_back} trading days) | Symbol: {symbol}")
    print(f"Contract: Monthly ATM Option Buying (1 Lot = {lot_size} qty)")
    print("="*80)

    # Fetch 6 months data to ensure 20-period warmup is ready
    df = yf.download(symbol, period='6mo', interval='1d', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()

    # Heikin-Ashi
    ha_close = (df['Open'] + df['High'] + df['Low'] + df['Close']) / 4.0
    ha_open = [(df['Open'].iloc[0] + df['Close'].iloc[0]) / 2.0]
    for i in range(1, len(df)):
        ha_open.append((ha_open[i-1] + ha_close.iloc[i-1]) / 2.0)
    ha_open = pd.Series(ha_open, index=df.index)
    ha_high = pd.concat([df['High'], ha_open, ha_close], axis=1).max(axis=1)
    ha_low = pd.concat([df['Low'], ha_open, ha_close], axis=1).min(axis=1)

    df['HA_Open'] = ha_open
    df['HA_High'] = ha_high
    df['HA_Low'] = ha_low
    df['HA_Close'] = ha_close

    # Bollinger Bands (20, 2) on HA Close
    df['SMA20'] = df['HA_Close'].rolling(window=20).mean()
    df['STD20'] = df['HA_Close'].rolling(window=20).std()
    df['BB_Upper'] = df['SMA20'] + 2.0 * df['STD20']
    df['BB_Lower'] = df['SMA20'] - 2.0 * df['STD20']

    # Filter to test window
    start_eval_idx = max(20, len(df) - days_back)
    eval_df = df.iloc[start_eval_idx:].copy()
    start_date = eval_df.index[0].strftime('%Y-%m-%d')
    end_date = eval_df.index[-1].strftime('%Y-%m-%d')
    print(f"Evaluation Period: {start_date} to {end_date} ({len(eval_df)} trading sessions)\n")

    # State Machine Variables
    position = 0 # 0: Flat, 1: In Long CE, -1: In Short PE
    active_trade = None
    pending_alert = None
    touched_upper = False
    touched_lower = False

    trades = []
    daily_log = []

    for i in range(len(df)):
        date = df.index[i].strftime('%Y-%m-%d')
        o = df['Open'].iloc[i]
        h = df['High'].iloc[i]
        l = df['Low'].iloc[i]
        c = df['Close'].iloc[i]
        ha_h = df['HA_High'].iloc[i]
        ha_l = df['HA_Low'].iloc[i]
        ha_c = df['HA_Close'].iloc[i]
        bb_u = df['BB_Upper'].iloc[i]
        bb_l = df['BB_Lower'].iloc[i]
        sma = df['SMA20'].iloc[i]

        if np.isnan(bb_u):
            continue

        is_in_eval_window = (i >= start_eval_idx)

        # 1. Manage Active Position
        if position != 0 and active_trade is not None:
            trade_type = active_trade['type'] # 'CE' or 'PE'
            entry_spot = active_trade['entry_spot']
            entry_prem = active_trade['entry_prem']
            sl_spot = active_trade['sl_spot']
            target_bb = bb_u if trade_type == 'CE' else bb_l
            days_held = active_trade['days_held'] + 1
            active_trade['days_held'] = days_held

            exited = False
            exit_spot = 0.0
            exit_reason = ""

            if trade_type == 'CE':
                # Long CE Stop Loss
                if l <= sl_spot:
                    exit_spot = min(o, sl_spot) if o < sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                # Long CE Target (Opposite Upper BB)
                elif h >= bb_u:
                    exit_spot = max(o, bb_u) if o > bb_u else bb_u
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True
            elif trade_type == 'PE':
                # Short PE Stop Loss
                if h >= sl_spot:
                    exit_spot = max(o, sl_spot) if o > sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                # Short PE Target (Opposite Lower BB)
                elif l <= bb_l:
                    exit_spot = min(o, bb_l) if o < bb_l else bb_l
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True

            if exited:
                # Calculate Option PnL
                spot_diff = (exit_spot - entry_spot) if trade_type == 'CE' else (entry_spot - exit_spot)
                delta = 0.50
                theta_decay = 4.0 * days_held
                est_exit_prem = max(5.0, entry_prem + (spot_diff * delta) - theta_decay)
                pnl_pts = est_exit_prem - entry_prem
                total_pnl = pnl_pts * lot_size

                completed_trade = {
                    'trade_id': len(trades) + 1,
                    'type': trade_type,
                    'strike': round(entry_spot / 50) * 50,
                    'entry_date': active_trade['entry_date'],
                    'entry_spot': entry_spot,
                    'entry_prem': entry_prem,
                    'exit_date': date,
                    'exit_spot': exit_spot,
                    'exit_prem': round(est_exit_prem, 2),
                    'sl_spot': sl_spot,
                    'pnl_pts': round(pnl_pts, 2),
                    'pnl_rupees': round(total_pnl, 2),
                    'return_pct': round((pnl_pts / entry_prem) * 100, 2),
                    'days_held': days_held,
                    'exit_reason': exit_reason
                }
                trades.append(completed_trade)
                position = 0
                active_trade = None
                if is_in_eval_window:
                    daily_log.append(f"[{date}] EXIT {trade_type} @ Spot {exit_spot:.1f} | Reason: {exit_reason} | PnL: Rs. {total_pnl:,.2f} ({completed_trade['return_pct']}%)")

        # 2. Manage Pending Alert Trigger / Invalidation
        if position == 0 and pending_alert is not None:
            direction = pending_alert['direction']
            h_alert = pending_alert['h_alert']
            l_alert = pending_alert['l_alert']

            if direction == 'BUY':
                # Invalidation
                if l < l_alert:
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] BUY Alert Invalidated (Spot {l:.1f} < Alert Low {l_alert:.1f})")
                    pending_alert = None
                # Entry Trigger
                elif h >= h_alert:
                    entry_spot = max(o, h_alert)
                    strike = round(entry_spot / 50) * 50
                    entry_prem = round(entry_spot * 0.015, 2)
                    sl_spot = l_alert
                    position = 1
                    active_trade = {
                        'type': 'CE',
                        'strike': strike,
                        'entry_date': date,
                        'entry_spot': entry_spot,
                        'entry_prem': entry_prem,
                        'sl_spot': sl_spot,
                        'days_held': 0
                    }
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] ENTRY BUY {strike} CE @ Spot {entry_spot:.1f} (Prem: Rs. {entry_prem:.1f}) | SL: {sl_spot:.1f} | Target BB: {bb_u:.1f}")
                    pending_alert = None
            elif direction == 'SELL':
                # Invalidation
                if h > h_alert:
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] SELL Alert Invalidated (Spot {h:.1f} > Alert High {h_alert:.1f})")
                    pending_alert = None
                # Entry Trigger
                elif l <= l_alert:
                    entry_spot = min(o, l_alert)
                    strike = round(entry_spot / 50) * 50
                    entry_prem = round(entry_spot * 0.015, 2)
                    sl_spot = h_alert
                    position = -1
                    active_trade = {
                        'type': 'PE',
                        'strike': strike,
                        'entry_date': date,
                        'entry_spot': entry_spot,
                        'entry_prem': entry_prem,
                        'sl_spot': sl_spot,
                        'days_held': 0
                    }
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] ENTRY BUY {strike} PE @ Spot {entry_spot:.1f} (Prem: Rs. {entry_prem:.1f}) | SL: {sl_spot:.1f} | Target BB: {bb_l:.1f}")
                    pending_alert = None

        # 3. Detect Band Touches and Alert Candles
        if position == 0 and pending_alert is None:
            # Check Upper Band Touch
            if ha_h >= bb_u:
                touched_upper = True
                touched_lower = False
            # Check Lower Band Touch
            elif ha_l <= bb_l:
                touched_lower = True
                touched_upper = False
            else:
                # Inside candle without touch
                if touched_upper and ha_h < bb_u:
                    # New SELL Alert
                    pending_alert = {
                        'direction': 'SELL',
                        'date': date,
                        'h_alert': h,
                        'l_alert': l,
                        'target_bb': bb_l
                    }
                    touched_upper = False
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] SELL Alert Candle | H_alert (SL): {h:.1f}, L_alert (Entry): {l:.1f} | Target BB: {bb_l:.1f}")
                elif touched_lower and ha_l > bb_l:
                    # New BUY Alert
                    pending_alert = {
                        'direction': 'BUY',
                        'date': date,
                        'h_alert': h,
                        'l_alert': l,
                        'target_bb': bb_u
                    }
                    touched_lower = False
                    if is_in_eval_window:
                        daily_log.append(f"[{date}] BUY Alert Candle | H_alert (Entry): {h:.1f}, L_alert (SL): {l:.1f} | Target BB: {bb_u:.1f}")

    # Display Daily Event Log for recent 2 months
    print("DAILY EXECUTION & SCAN LOG (Last 2 Months):")
    print("-" * 80)
    for log_entry in daily_log:
        print(log_entry)
    if not daily_log:
        print("  (No alerts or trade triggers occurred in this timeframe)")
    print("-" * 80)

    # Filter trades that occurred within or overlapped with evaluation window
    eval_start_str = eval_df.index[0].strftime('%Y-%m-%d')
    recent_trades = [t for t in trades if t['entry_date'] >= eval_start_str or t['exit_date'] >= eval_start_str]

    print(f"\nCOMPLETED TRADES ({len(recent_trades)} trades in period):")
    print("="*100)
    if recent_trades:
        trades_df = pd.DataFrame(recent_trades)
        cols_to_print = ['trade_id', 'type', 'strike', 'entry_date', 'entry_spot', 'entry_prem', 'exit_date', 'exit_spot', 'exit_prem', 'pnl_rupees', 'return_pct', 'exit_reason']
        print(trades_df[cols_to_print].to_string(index=False))
        
        # Summary Metrics
        total_pnl = sum(t['pnl_rupees'] for t in recent_trades)
        wins = [t for t in recent_trades if t['pnl_rupees'] > 0]
        losses = [t for t in recent_trades if t['pnl_rupees'] <= 0]
        win_rate = (len(wins) / len(recent_trades)) * 100 if recent_trades else 0
        avg_win = np.mean([t['pnl_rupees'] for t in wins]) if wins else 0
        avg_loss = np.mean([t['pnl_rupees'] for t in losses]) if losses else 0
        profit_factor = abs(sum(t['pnl_rupees'] for t in wins) / sum(t['pnl_rupees'] for t in losses)) if losses and sum(t['pnl_rupees'] for t in losses) != 0 else float('inf')

        print("="*100)
        print("PERFORMANCE SUMMARY:")
        print(f"• Total Net P&L: Rs. {total_pnl:,.2f}")
        print(f"• Total Trades: {len(recent_trades)} (Wins: {len(wins)}, Losses: {len(losses)})")
        print(f"• Win Rate: {win_rate:.1f}%")
        print(f"• Profit Factor: {profit_factor:.2f}")
        print(f"• Average Win: Rs. {avg_win:,.2f}")
        print(f"• Average Loss: Rs. {avg_loss:,.2f}")
    else:
        print("No completed trades in the last 2 months.")
    print("="*100)

    # Active status at end of backtest
    print("\nCURRENT STATUS AT END OF PERIOD:")
    if position != 0 and active_trade is not None:
        last_spot = df['Close'].iloc[-1]
        print(f"• Active Trade: IN_{active_trade['type']} ({active_trade['strike']})")
        print(f"• Entry Date: {active_trade['entry_date']} @ Spot {active_trade['entry_spot']:.1f}")
        print(f"• Current Spot: {last_spot:.1f} | SL: {active_trade['sl_spot']:.1f}")
    elif pending_alert is not None:
        print(f"• Pending Alert: {pending_alert['direction']} Alert on {pending_alert['date']}")
        print(f"• Trigger Price: {pending_alert['l_alert'] if pending_alert['direction'] == 'SELL' else pending_alert['h_alert']:.1f}")
    else:
        print("• Status: FLAT (Scanning daily for new Bollinger Band touch)")

if __name__ == '__main__':
    run_recent_backtest(days_back=50)
