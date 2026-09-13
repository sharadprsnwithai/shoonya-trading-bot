import sys
import io

# Set UTF-8 encoding for Windows stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

import yfinance as yf
import pandas as pd
import numpy as np
import datetime

def run_3year_10lots_backtest(initial_capital=1000000.0, num_lots=10, lot_size=65, symbol='^NSEI'):
    total_qty = num_lots * lot_size # 650 qty
    print("="*115)
    print("💼 3-YEAR POSITIONAL BACKTEST: ATM OPTION SELLING + 0.20 DELTA HEDGE")
    print(f"💰 Initial Capital: ₹{initial_capital:,.2f} (10 Lakhs)")
    print(f"📦 Position Size: {num_lots} Lots ({total_qty} Qty) | Underlying: NIFTY 50 Spot ({symbol})")
    print(f"🎯 Margin Required per Spread: ~₹45,000 / lot | Total Margin for 10 Lots: ~₹4,50,000 (45% Capital Utilization)")
    print("="*115)

    # Fetch 4 years to ensure warmup
    df = yf.download(symbol, period='4y', interval='1d', progress=False)
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

    # 3 Years evaluation window (~750 trading sessions)
    start_eval_idx = max(20, len(df) - 750)
    eval_df = df.iloc[start_eval_idx:].copy()
    start_date = eval_df.index[0].strftime('%Y-%m-%d')
    end_date = eval_df.index[-1].strftime('%Y-%m-%d')
    print(f"📅 Evaluation Window: {start_date} to {end_date} ({len(eval_df)} trading days)\n")

    # State Machine Variables
    position = 0 # 0: Flat, 1: Bull Put Spread, -1: Bear Call Spread
    active_trade = None
    pending_alert = None
    touched_upper = False
    touched_lower = False

    trades = []
    capital = initial_capital
    peak_capital = initial_capital
    running_equity = [initial_capital]

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
                if l <= sl_spot:
                    exit_spot = min(o, sl_spot) if o < sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                elif h >= bb_u:
                    exit_spot = max(o, bb_u) if o > bb_u else bb_u
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True
            elif trade_type == 'BEAR_CALL_SPREAD':
                if h >= sl_spot:
                    exit_spot = max(o, sl_spot) if o > sl_spot else sl_spot
                    exit_reason = "STOP_LOSS"
                    exited = True
                elif l <= bb_l:
                    exit_spot = min(o, bb_l) if o < bb_l else bb_l
                    exit_reason = "TARGET_OPPOSITE_BAND"
                    exited = True

            if exited:
                spot_move = (exit_spot - entry_spot) if trade_type == 'BULL_PUT_SPREAD' else (entry_spot - exit_spot)
                
                # Sold Leg
                sold_leg_change = - (spot_move * 0.50) - (4.0 * days_held)
                sold_exit_prem = max(0.0, short_prem + sold_leg_change)
                sold_pnl = short_prem - sold_exit_prem

                # Hedge Leg (0.2 Delta)
                hedge_leg_change = - (spot_move * 0.20) - (1.0 * days_held)
                hedge_exit_prem = max(0.0, hedge_prem + hedge_leg_change)
                hedge_pnl = hedge_exit_prem - hedge_prem

                net_pnl_pts = sold_pnl + hedge_pnl
                max_profit = net_credit
                max_loss = - (spread_width - net_credit)
                net_pnl_pts = max(max_loss, min(max_profit, net_pnl_pts))
                
                trade_pnl_rupees = net_pnl_pts * total_qty
                capital += trade_pnl_rupees
                peak_capital = max(peak_capital, capital)

                completed_trade = {
                    'trade_id': len(trades) + 1,
                    'type': 'BULL_PUT' if trade_type == 'BULL_PUT_SPREAD' else 'BEAR_CALL',
                    'short_strike': active_trade['short_strike'],
                    'hedge_strike': active_trade['hedge_strike'],
                    'entry_date': active_trade['entry_date'],
                    'exit_date': date,
                    'days_held': days_held,
                    'entry_spot': round(entry_spot, 1),
                    'exit_spot': round(exit_spot, 1),
                    'net_credit_pts': round(net_credit, 1),
                    'pnl_pts': round(net_pnl_pts, 1),
                    'pnl_rupees': round(trade_pnl_rupees, 2),
                    'capital_after': round(capital, 2),
                    'exit_reason': exit_reason
                }
                trades.append(completed_trade)
                position = 0
                active_trade = None

        # 2. Manage Pending Alert
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
                    hedge_strike = short_strike - 300
                    spread_width = 300
                    short_prem = round(entry_spot * 0.015, 1)
                    hedge_prem = round(entry_spot * 0.0038, 1)
                    net_credit = short_prem - hedge_prem

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
                    hedge_strike = short_strike + 300
                    spread_width = 300
                    short_prem = round(entry_spot * 0.015, 1)
                    hedge_prem = round(entry_spot * 0.0038, 1)
                    net_credit = short_prem - hedge_prem

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

        # 3. Detect Band Touches
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

    # Filter to 3-Year period
    eval_start_str = eval_df.index[0].strftime('%Y-%m-%d')
    three_yr_trades = [t for t in trades if t['entry_date'] >= eval_start_str or t['exit_date'] >= eval_start_str]

    # Re-calculate running equity exclusively for the 3-year window
    running_cap = initial_capital
    for t in three_yr_trades:
        running_cap += t['pnl_rupees']
        t['running_cap'] = running_cap

    print(f"📋 COMPLETE 3-YEAR TRADE-BY-TRADE LOG ({len(three_yr_trades)} Trades):")
    print("="*120)
    trades_df = pd.DataFrame(three_yr_trades)
    if not trades_df.empty:
        cols_to_print = ['trade_id', 'type', 'short_strike', 'hedge_strike', 'entry_date', 'exit_date', 'days_held', 'entry_spot', 'exit_spot', 'pnl_rupees', 'running_cap', 'exit_reason']
        print(trades_df[cols_to_print].to_string(index=False))

        # Metrics
        total_pnl = sum(t['pnl_rupees'] for t in three_yr_trades)
        final_capital = initial_capital + total_pnl
        total_roi = (total_pnl / initial_capital) * 100
        years = 3.0
        cagr = (((final_capital / initial_capital) ** (1.0 / years)) - 1.0) * 100

        wins = [t for t in three_yr_trades if t['pnl_rupees'] > 0]
        losses = [t for t in three_yr_trades if t['pnl_rupees'] <= 0]
        win_rate = (len(wins) / len(three_yr_trades)) * 100
        gross_profit = sum(t['pnl_rupees'] for t in wins)
        gross_loss = abs(sum(t['pnl_rupees'] for t in losses))
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')
        avg_win = np.mean([t['pnl_rupees'] for t in wins]) if wins else 0
        avg_loss = np.mean([t['pnl_rupees'] for t in losses]) if losses else 0
        avg_holding = np.mean([t['days_held'] for t in three_yr_trades])
        best_trade = max([t['pnl_rupees'] for t in three_yr_trades])
        worst_trade = min([t['pnl_rupees'] for t in three_yr_trades])

        # Equity Curve and Drawdown
        equity_curve = [initial_capital]
        for t in three_yr_trades:
            equity_curve.append(equity_curve[-1] + t['pnl_rupees'])
        eq_series = pd.Series(equity_curve)
        peak_series = eq_series.cummax()
        dd_rupees = peak_series - eq_series
        max_dd_rupees = dd_rupees.max()
        dd_pct = (dd_rupees / peak_series) * 100
        max_dd_pct = dd_pct.max()

        calmar_ratio = (cagr / max_dd_pct) if max_dd_pct > 0 else float('inf')

        print("="*120)
        print("🏆 3-YEAR PERFORMANCE SUMMARY (10 LAKH CAPITAL | 10 LOTS):")
        print(f"• Initial Capital:        ₹{initial_capital:,.2f}")
        print(f"• Final Account Value:    ₹{final_capital:,.2f}")
        print(f"• Net Profit:             +₹{total_pnl:,.2f} (+{total_roi:.2f}% Total Return)")
        print(f"• CAGR (Annualized):      +{cagr:.2f}% per annum")
        print(f"• Total Trades:           {len(three_yr_trades)} (Wins: {len(wins)}, Losses: {len(losses)})")
        print(f"• Win Rate:               {win_rate:.1f}%")
        print(f"• Profit Factor:          {profit_factor:.2f}")
        print(f"• Gross Profit:           ₹{gross_profit:,.2f}")
        print(f"• Gross Loss:             -₹{gross_loss:,.2f}")
        print(f"• Average Winning Trade:  +₹{avg_win:,.2f}")
        print(f"• Average Losing Trade:   -₹{avg_loss:,.2f}")
        print(f"• Win/Loss Payoff Ratio:  {(avg_win / avg_loss):.2f} : 1")
        print(f"• Best Trade:             +₹{best_trade:,.2f}")
        print(f"• Worst Trade:            -₹{abs(worst_trade):,.2f}")
        print(f"• Max Drawdown (₹):       -₹{max_dd_rupees:,.2f}")
        print(f"• Max Drawdown (%):       -{max_dd_pct:.2f}% (Peak to Trough)")
        print(f"• Calmar Ratio:           {calmar_ratio:.2f}")
        print(f"• Avg Trade Duration:     {avg_holding:.1f} trading days")
        print("="*120)

        # Yearly Breakdown
        trades_df['year'] = pd.to_datetime(trades_df['exit_date']).dt.strftime('%Y')
        yearly_summary = trades_df.groupby('year').agg(
            trades=('pnl_rupees', 'count'),
            win_count=('pnl_rupees', lambda x: (x > 0).sum()),
            yearly_pnl=('pnl_rupees', 'sum')
        ).reset_index()
        yearly_summary['win_rate'] = (yearly_summary['win_count'] / yearly_summary['trades']) * 100
        yearly_summary['return_on_10L'] = (yearly_summary['yearly_pnl'] / initial_capital) * 100

        print("\n📅 YEARLY P&L BREAKDOWN:")
        print("-" * 75)
        print(f"{'Year':<8} | {'Trades':<8} | {'Win Rate':<10} | {'Yearly Net P&L (₹)':<20} | {'Return on 10L':<15}")
        print("-" * 75)
        for _, row in yearly_summary.iterrows():
            print(f"{row['year']:<8} | {int(row['trades']):<8} | {row['win_rate']:>7.1f}%   | ₹{row['yearly_pnl']:>17,.2f} | {row['return_on_10L']:>12.2f}%")
        print("-" * 75)
        print(f"{'TOTAL':<8} | {len(three_yr_trades):<8} | {win_rate:>7.1f}%   | ₹{total_pnl:>17,.2f} | {total_roi:>12.2f}%")
        print("="*75)

if __name__ == '__main__':
    run_3year_10lots_backtest()
