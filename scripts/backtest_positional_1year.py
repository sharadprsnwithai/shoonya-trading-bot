import sys
import io

# Set UTF-8 encoding for Windows stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import yfinance as yf
import pandas as pd
import numpy as np
import datetime

def run_1year_backtest(symbol='^NSEI', lot_size=65):
    print("="*95)
    print("📊 1-YEAR BACKTEST: Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy")
    print(f"🎯 Underlying: NIFTY 50 Spot ({symbol}) | Timeframe: Daily (EOD 3:00 PM IST)")
    print(f"📦 Execution Vehicle: Monthly ATM Option Buying (1 Lot = {lot_size} qty, Delta 0.50, Theta Decay)")
    print("="*95)

    # Fetch 2 years to ensure full 20-day indicator warmup before 1-year window
    df = yf.download(symbol, period='2y', interval='1d', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()

    # Heikin-Ashi calculation
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

    # 1 Year evaluation window (~250 trading sessions)
    start_eval_idx = max(20, len(df) - 250)
    eval_df = df.iloc[start_eval_idx:].copy()
    start_date = eval_df.index[0].strftime('%Y-%m-%d')
    end_date = eval_df.index[-1].strftime('%Y-%m-%d')
    print(f"📅 Evaluation Period: {start_date} to {end_date} ({len(eval_df)} trading sessions)\n")

    # State Machine Variables
    position = 0 # 0: Flat, 1: In Long CE, -1: In Short PE
    active_trade = None
    pending_alert = None
    touched_upper = False
    touched_lower = False

    trades = []

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

        if np.isnan(bb_u):
            continue

        # 1. Manage Active Position
        if position != 0 and active_trade is not None:
            trade_type = active_trade['type'] # 'CE' or 'PE'
            entry_spot = active_trade['entry_spot']
            entry_prem = active_trade['entry_prem']
            sl_spot = active_trade['sl_spot']
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
                # Option PnL Model: Delta 0.50, Theta ~4 pts/day, Floor at 0 (max loss is 100% premium paid)
                spot_diff = (exit_spot - entry_spot) if trade_type == 'CE' else (entry_spot - exit_spot)
                delta = 0.50
                theta_decay = 4.0 * days_held
                est_exit_prem = max(0.0, entry_prem + (spot_diff * delta) - theta_decay)
                pnl_pts = est_exit_prem - entry_prem
                total_pnl = pnl_pts * lot_size

                completed_trade = {
                    'trade_id': len(trades) + 1,
                    'type': trade_type,
                    'strike': round(entry_spot / 50) * 50,
                    'entry_date': active_trade['entry_date'],
                    'entry_spot': round(entry_spot, 1),
                    'entry_prem': round(entry_prem, 1),
                    'exit_date': date,
                    'exit_spot': round(exit_spot, 1),
                    'exit_prem': round(est_exit_prem, 1),
                    'sl_spot': round(sl_spot, 1),
                    'spot_gain': round(spot_diff, 1),
                    'pnl_pts': round(pnl_pts, 1),
                    'pnl_rupees': round(total_pnl, 2),
                    'return_pct': round((pnl_pts / entry_prem) * 100, 1),
                    'days_held': days_held,
                    'exit_reason': exit_reason
                }
                trades.append(completed_trade)
                position = 0
                active_trade = None

        # 2. Manage Pending Alert Trigger / Invalidation
        if position == 0 and pending_alert is not None:
            direction = pending_alert['direction']
            h_alert = pending_alert['h_alert']
            l_alert = pending_alert['l_alert']

            if direction == 'BUY':
                # Invalidation
                if l < l_alert:
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
                    pending_alert = None
            elif direction == 'SELL':
                # Invalidation
                if h > h_alert:
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
                    pending_alert = None

        # 3. Detect Band Touches and Alert Candles
        if position == 0 and pending_alert is None:
            if ha_h >= bb_u:
                touched_upper = True
                touched_lower = False
            elif ha_l <= bb_l:
                touched_lower = True
                touched_upper = False
            else:
                if touched_upper and ha_h < bb_u:
                    pending_alert = {
                        'direction': 'SELL',
                        'date': date,
                        'h_alert': h,
                        'l_alert': l,
                        'target_bb': bb_l
                    }
                    touched_upper = False
                elif touched_lower and ha_l > bb_l:
                    pending_alert = {
                        'direction': 'BUY',
                        'date': date,
                        'h_alert': h,
                        'l_alert': l,
                        'target_bb': bb_u
                    }
                    touched_lower = False

    # Filter to 1-Year trades
    eval_start_str = eval_df.index[0].strftime('%Y-%m-%d')
    year_trades = [t for t in trades if t['entry_date'] >= eval_start_str or t['exit_date'] >= eval_start_str]

    print(f"📋 COMPLETE 1-YEAR TRADE LOG ({len(year_trades)} Trades):")
    print("="*105)
    trades_df = pd.DataFrame(year_trades)
    if not trades_df.empty:
        cols_to_print = ['trade_id', 'type', 'strike', 'entry_date', 'entry_spot', 'exit_date', 'exit_spot', 'days_held', 'entry_prem', 'exit_prem', 'pnl_rupees', 'return_pct', 'exit_reason']
        print(trades_df[cols_to_print].to_string(index=False))

        # Metrics
        total_pnl = sum(t['pnl_rupees'] for t in year_trades)
        wins = [t for t in year_trades if t['pnl_rupees'] > 0]
        losses = [t for t in year_trades if t['pnl_rupees'] <= 0]
        win_rate = (len(wins) / len(year_trades)) * 100
        gross_profit = sum(t['pnl_rupees'] for t in wins)
        gross_loss = abs(sum(t['pnl_rupees'] for t in losses))
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')
        avg_win = np.mean([t['pnl_rupees'] for t in wins]) if wins else 0
        avg_loss = np.mean([t['pnl_rupees'] for t in losses]) if losses else 0
        avg_holding = np.mean([t['days_held'] for t in year_trades])
        best_trade = max([t['pnl_rupees'] for t in year_trades])
        worst_trade = min([t['pnl_rupees'] for t in year_trades])

        # Equity Curve and Drawdown
        equity = [0.0]
        for t in year_trades:
            equity.append(equity[-1] + t['pnl_rupees'])
        equity_series = pd.Series(equity)
        peak_series = equity_series.cummax()
        dd_series = peak_series - equity_series
        max_drawdown = dd_series.max()

        print("="*105)
        print("📈 1-YEAR PERFORMANCE SUMMARY (1 Lot = 65 Qty):")
        print(f"• Total Realized Net P&L: ₹{total_pnl:,.2f}")
        print(f"• Total Trades: {len(year_trades)} (Wins: {len(wins)}, Losses: {len(losses)})")
        print(f"• Win Rate: {win_rate:.1f}%")
        print(f"• Profit Factor: {profit_factor:.2f}")
        print(f"• Gross Profit: ₹{gross_profit:,.2f}")
        print(f"• Gross Loss: -₹{gross_loss:,.2f}")
        print(f"• Average Win per Trade: ₹{avg_win:,.2f}")
        print(f"• Average Loss per Trade: -₹{avg_loss:,.2f}")
        print(f"• Win / Loss Ratio: {(avg_win / avg_loss):.2f}" if avg_loss > 0 else "• Win / Loss Ratio: N/A")
        print(f"• Best Trade: +₹{best_trade:,.2f}")
        print(f"• Worst Trade: -₹{abs(worst_trade):,.2f}")
        print(f"• Max Drawdown: -₹{max_drawdown:,.2f}")
        print(f"• Average Holding Period: {avg_holding:.1f} trading days")
        print("="*105)

        # Monthly P&L Breakdown
        trades_df['month'] = pd.to_datetime(trades_df['exit_date']).dt.strftime('%Y-%m')
        monthly_summary = trades_df.groupby('month').agg(
            trades=('pnl_rupees', 'count'),
            win_count=('pnl_rupees', lambda x: (x > 0).sum()),
            monthly_pnl=('pnl_rupees', 'sum')
        ).reset_index()
        monthly_summary['win_rate'] = (monthly_summary['win_count'] / monthly_summary['trades']) * 100

        print("\n📅 MONTHLY P&L BREAKDOWN:")
        print("-" * 65)
        print(f"{'Month':<10} | {'Trades':<8} | {'Win Rate':<10} | {'Monthly Net P&L (₹)':<20}")
        print("-" * 65)
        for _, row in monthly_summary.iterrows():
            print(f"{row['month']:<10} | {int(row['trades']):<8} | {row['win_rate']:>7.1f}%   | ₹{row['monthly_pnl']:>15,.2f}")
        print("-" * 65)
        print(f"{'TOTAL':<10} | {len(year_trades):<8} | {win_rate:>7.1f}%   | ₹{total_pnl:>15,.2f}")
        print("="*65)
    else:
        print("No trades triggered during the evaluation period.")

if __name__ == '__main__':
    run_1year_backtest()
