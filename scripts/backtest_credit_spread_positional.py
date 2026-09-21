import sys
import io

# Set UTF-8 encoding for Windows stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import yfinance as yf
import pandas as pd
import numpy as np
import datetime

def run_spread_backtest(period_years=1, symbol='^NSEI', lot_size=65):
    print("="*105)
    print("📊 BACKTEST: Positional Bollinger Band (20, 2) & Heikin-Ashi Strategy")
    print(f"📦 STRUCTURE: ATM Option Selling + 0.20 Delta OTM Protective Hedge Leg (Credit Spread)")
    print(f"🎯 Setup Rules:")
    print("   • Long Signal: SELL Monthly ATM PE + BUY Monthly 0.20 Delta OTM PE Hedge (Bull Put Spread)")
    print("   • Short Signal: SELL Monthly ATM CE + BUY Monthly 0.20 Delta OTM CE Hedge (Bear Call Spread)")
    print(f"   • Delta: Net ~+0.30 on Long, ~-0.30 on Short | Theta Benefit: ~+3.0 pts/day")
    print(f"   • Timeframe: Daily EOD (3:00 PM IST) | Lot Size: {lot_size}")
    print("="*105)

    # Fetch data
    fetch_period = '3y' if period_years <= 2 else '12y'
    df = yf.download(symbol, period=fetch_period, interval='1d', progress=False)
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

    # Filter to evaluation window
    trading_days = int(period_years * 250)
    start_eval_idx = max(20, len(df) - trading_days)
    eval_df = df.iloc[start_eval_idx:].copy()
    start_date = eval_df.index[0].strftime('%Y-%m-%d')
    end_date = eval_df.index[-1].strftime('%Y-%m-%d')
    print(f"📅 Evaluation Period: {start_date} to {end_date} ({len(eval_df)} trading sessions)\n")

    # State Machine
    position = 0 # 0: Flat, 1: Bull Put Spread, -1: Bear Call Spread
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

        # 1. Manage Active Spread Position
        if position != 0 and active_trade is not None:
            trade_type = active_trade['type'] # 'BULL_PUT_SPREAD' or 'BEAR_CALL_SPREAD'
            entry_spot = active_trade['entry_spot']
            short_prem = active_trade['short_prem']
            hedge_prem = active_trade['hedge_prem']
            net_credit = active_trade['net_credit']
            spread_width = active_trade['spread_width']
            sl_spot = active_trade['sl_spot']
            days_held = active_trade['days_held'] + 1
            active_trade['days_held'] = days_held

            exited = False
            exit_spot = 0.0
            exit_reason = ""

            if trade_type == 'BULL_PUT_SPREAD':
                # Stop Loss: Spot drops to Alert Low
                if l <= sl_spot:
                    exit_spot = min(o, sl_spot) if o < sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                # Target: Spot reaches Opposite Upper BB
                elif h >= bb_u:
                    exit_spot = max(o, bb_u) if o > bb_u else bb_u
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True
            elif trade_type == 'BEAR_CALL_SPREAD':
                # Stop Loss: Spot rises to Alert High
                if h >= sl_spot:
                    exit_spot = max(o, sl_spot) if o > sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                # Target: Spot reaches Opposite Lower BB
                elif l <= bb_l:
                    exit_spot = min(o, bb_l) if o < bb_l else bb_l
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True

            if exited:
                # Calculate Spread PnL
                # Sold Leg: Delta 0.50, Theta +4 pts/day
                # Hedge Leg (0.2 Delta): Delta 0.20, Theta -1 pt/day
                # Net Delta: 0.30 in trade favor, Net Theta: +3.0 pts/day in seller favor
                spot_move = (exit_spot - entry_spot) if trade_type == 'BULL_PUT_SPREAD' else (entry_spot - exit_spot)
                
                # Option exit pricing
                sold_leg_change = - (spot_move * 0.50) - (4.0 * days_held)
                sold_exit_prem = max(0.0, short_prem + sold_leg_change)
                sold_pnl = short_prem - sold_exit_prem

                hedge_leg_change = - (spot_move * 0.20) - (1.0 * days_held)
                hedge_exit_prem = max(0.0, hedge_prem + hedge_leg_change)
                hedge_pnl = hedge_exit_prem - hedge_prem

                net_pnl_pts = sold_pnl + hedge_pnl
                
                # Cap maximum profit at Net Credit, maximum loss at (Spread Width - Net Credit)
                max_profit = net_credit
                max_loss = - (spread_width - net_credit)
                net_pnl_pts = max(max_loss, min(max_profit, net_pnl_pts))
                total_pnl_rupees = net_pnl_pts * lot_size

                completed_trade = {
                    'trade_id': len(trades) + 1,
                    'type': 'BULL_PUT' if trade_type == 'BULL_PUT_SPREAD' else 'BEAR_CALL',
                    'short_strike': active_trade['short_strike'],
                    'hedge_strike': active_trade['hedge_strike'],
                    'entry_date': active_trade['entry_date'],
                    'entry_spot': round(entry_spot, 1),
                    'exit_date': date,
                    'exit_spot': round(exit_spot, 1),
                    'days_held': days_held,
                    'net_credit': round(net_credit, 1),
                    'pnl_pts': round(net_pnl_pts, 1),
                    'pnl_rupees': round(total_pnl_rupees, 2),
                    'return_pct': round((net_pnl_pts / (spread_width - net_credit)) * 100, 1),
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
                if l < l_alert:
                    pending_alert = None
                elif h >= h_alert:
                    entry_spot = max(o, h_alert)
                    short_strike = round(entry_spot / 50) * 50
                    hedge_strike = short_strike - 300 # 0.20 Delta hedge (~300 pts OTM)
                    spread_width = short_strike - hedge_strike # 300 pts
                    short_prem = round(entry_spot * 0.015, 1) # ~360 pts
                    hedge_prem = round(entry_spot * 0.0038, 1) # ~90 pts (0.2 delta)
                    net_credit = short_prem - hedge_prem # ~270 pts

                    position = 1
                    active_trade = {
                        'type': 'BULL_PUT_SPREAD',
                        'short_strike': short_strike,
                        'hedge_strike': hedge_strike,
                        'spread_width': spread_width,
                        'entry_date': date,
                        'entry_spot': entry_spot,
                        'short_prem': short_prem,
                        'hedge_prem': hedge_prem,
                        'net_credit': net_credit,
                        'sl_spot': l_alert,
                        'days_held': 0
                    }
                    pending_alert = None
            elif direction == 'SELL':
                if h > h_alert:
                    pending_alert = None
                elif l <= l_alert:
                    entry_spot = min(o, l_alert)
                    short_strike = round(entry_spot / 50) * 50
                    hedge_strike = short_strike + 300 # 0.20 Delta hedge (~300 pts OTM)
                    spread_width = hedge_strike - short_strike # 300 pts
                    short_prem = round(entry_spot * 0.015, 1) # ~360 pts
                    hedge_prem = round(entry_spot * 0.0038, 1) # ~90 pts (0.2 delta)
                    net_credit = short_prem - hedge_prem # ~270 pts

                    position = -1
                    active_trade = {
                        'type': 'BEAR_CALL_SPREAD',
                        'short_strike': short_strike,
                        'hedge_strike': hedge_strike,
                        'spread_width': spread_width,
                        'entry_date': date,
                        'entry_spot': entry_spot,
                        'short_prem': short_prem,
                        'hedge_prem': hedge_prem,
                        'net_credit': net_credit,
                        'sl_spot': h_alert,
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
                    pending_alert = {'direction': 'SELL', 'date': date, 'h_alert': h, 'l_alert': l}
                    touched_upper = False
                elif touched_lower and ha_l > bb_l:
                    pending_alert = {'direction': 'BUY', 'date': date, 'h_alert': h, 'l_alert': l}
                    touched_lower = False

    # Filter to period trades
    eval_start_str = eval_df.index[0].strftime('%Y-%m-%d')
    period_trades = [t for t in trades if t['entry_date'] >= eval_start_str or t['exit_date'] >= eval_start_str]

    print(f"📋 TRADE LOG ({len(period_trades)} Trades in Period):")
    print("="*110)
    trades_df = pd.DataFrame(period_trades)
    if not trades_df.empty:
        cols_to_print = ['trade_id', 'type', 'short_strike', 'hedge_strike', 'entry_date', 'exit_date', 'days_held', 'entry_spot', 'exit_spot', 'net_credit', 'pnl_rupees', 'return_pct', 'exit_reason']
        print(trades_df[cols_to_print].to_string(index=False))

        # Metrics
        total_pnl = sum(t['pnl_rupees'] for t in period_trades)
        wins = [t for t in period_trades if t['pnl_rupees'] > 0]
        losses = [t for t in period_trades if t['pnl_rupees'] <= 0]
        win_rate = (len(wins) / len(period_trades)) * 100
        gross_profit = sum(t['pnl_rupees'] for t in wins)
        gross_loss = abs(sum(t['pnl_rupees'] for t in losses))
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')
        avg_win = np.mean([t['pnl_rupees'] for t in wins]) if wins else 0
        avg_loss = np.mean([t['pnl_rupees'] for t in losses]) if losses else 0
        avg_holding = np.mean([t['days_held'] for t in period_trades])

        # Equity Curve and Drawdown
        equity = [0.0]
        for t in period_trades:
            equity.append(equity[-1] + t['pnl_rupees'])
        equity_series = pd.Series(equity)
        peak_series = equity_series.cummax()
        dd_series = peak_series - equity_series
        max_drawdown = dd_series.max()

        print("="*110)
        print("📈 PERFORMANCE SUMMARY (ATM Option Selling + 0.20 Delta Hedge | 1 Lot = 65 Qty):")
        print(f"• Total Realized Net P&L: ₹{total_pnl:,.2f}")
        print(f"• Total Trades: {len(period_trades)} (Wins: {len(wins)}, Losses: {len(losses)})")
        print(f"• Win Rate: {win_rate:.1f}%")
        print(f"• Profit Factor: {profit_factor:.2f}")
        print(f"• Gross Profit: ₹{gross_profit:,.2f}")
        print(f"• Gross Loss: -₹{gross_loss:,.2f}")
        print(f"• Average Win per Trade: ₹{avg_win:,.2f}")
        print(f"• Average Loss per Trade: -₹{avg_loss:,.2f}")
        print(f"• Win / Loss Ratio: {(avg_win / avg_loss):.2f}" if avg_loss > 0 else "• Win / Loss Ratio: N/A")
        print(f"• Max Drawdown: -₹{max_drawdown:,.2f}")
        print(f"• Average Holding Period: {avg_holding:.1f} trading days")
        print("="*110)

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
        print(f"{'TOTAL':<10} | {len(period_trades):<8} | {win_rate:>7.1f}%   | ₹{total_pnl:>15,.2f}")
        print("="*65)

if __name__ == '__main__':
    print("\n" + "#"*105)
    print("--- TEST 1: LAST 1 YEAR (2025-2026) ---")
    print("#"*105)
    run_spread_backtest(period_years=1)

    print("\n" + "#"*105)
    print("--- TEST 2: MULTI-YEAR (LAST 3 YEARS: 2023-2026) ---")
    print("#"*105)
    run_spread_backtest(period_years=3)
