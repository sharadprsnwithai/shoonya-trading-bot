#!/usr/bin/env python3
"""
Comparison: Pure Intraday vs Multi-Day Positional Execution of 19 WMA Hedged Strategy
=====================================================================================
Evaluates the 19 WMA strategy under both paradigms on 5-minute NIFTY candle data.
"""

import urllib.request
import json
import datetime
import math
from typing import List, Dict, Optional, Tuple
from backtest_19wma_strategy import (
    DailyBar, HourlyBar, select_short_strike,
    black_scholes_call, black_scholes_put, get_target_expiry,
    LOT_SIZE, RISK_FREE_RATE, DEFAULT_IV
)

def run_pure_intraday_backtest(
    daily_bars: List[DailyBar],
    hourly_bars: List[HourlyBar],
    sl_percent: float = 15.0,
    allow_reentry: bool = True,
    max_reentries: int = 1
) -> Dict:
    """
    INTRADAY MODE:
    - Enters at 09:30 AM every single day based on 19 WMA bias
    - Tracks 15% SL and re-entry intraday
    - Mandatorily squares off every day at 15:15 PM
    """
    hourly_by_date: Dict[datetime.date, List[HourlyBar]] = {}
    for h in hourly_bars:
        hourly_by_date.setdefault(h.date, []).append(h)

    daily_map = {d.date: d for d in daily_bars}
    sorted_dates = sorted([d.date for d in daily_bars if not math.isnan(d.wma19)])

    trades = []
    daily_equity = []
    current_equity = 150000.0
    trade_id = 1

    for d_idx, cur_date in enumerate(sorted_dates):
        cur_daily = daily_map[cur_date]
        prev_daily = daily_map[sorted_dates[d_idx - 1]] if d_idx > 0 else cur_daily
        wma19 = prev_daily.wma19

        day_hbars = hourly_by_date.get(cur_date, [])
        if len(day_hbars) < 2:
            continue

        spot_0930 = day_hbars[0].open
        is_bullish = spot_0930 > wma19
        target_expiry = get_target_expiry(cur_date)
        t_days = max(1, (target_expiry - cur_date).days)
        t_years = t_days / 365.0

        # Select Short + Hedge strikes using monthly contract
        short_k, short_prem, short_delta = select_short_strike(spot_0930, is_bullish, t_years, max_premium=100.0, target_delta=0.22)
        if is_bullish:
            hedge_k = round((short_k * 0.98) / 50.0) * 50.0
            hedge_prem, _ = black_scholes_put(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
        else:
            hedge_k = round((short_k * 1.02) / 50.0) * 50.0
            hedge_prem, _ = black_scholes_call(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)

        sl_price = short_prem * (1.0 + (sl_percent / 100.0))
        
        pos_open = True
        reentries_count = 0
        cur_short_prem = short_prem
        cur_hedge_prem = hedge_prem
        cur_short_k = short_k
        cur_hedge_k = hedge_k
        cur_sl_price = sl_price
        entry_time = day_hbars[0].time

        for h_idx, hbar in enumerate(day_hbars):
            if not pos_open:
                break
            
            # Check intraday SL on high/low of hourly candle
            for sim_spot in [hbar.high, hbar.low]:
                if not pos_open:
                    break
                if is_bullish:
                    s_px, _ = black_scholes_put(sim_spot, cur_short_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
                    h_px, _ = black_scholes_put(sim_spot, cur_hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
                else:
                    s_px, _ = black_scholes_call(sim_spot, cur_short_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
                    h_px, _ = black_scholes_call(sim_spot, cur_hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)

                if s_px >= cur_sl_price:
                    # SL Hit
                    short_pnl = (cur_short_prem - s_px) * LOT_SIZE
                    hedge_pnl = (h_px - cur_hedge_prem) * LOT_SIZE
                    net_pnl = short_pnl + hedge_pnl - 50.0 # 50 INR brokerage
                    trades.append({
                        'id': trade_id,
                        'date': cur_date,
                        'type': 'INTRADAY',
                        'pnl': net_pnl,
                        'reason': f'HARD_SL_HIT_{sl_percent}%',
                        'is_win': net_pnl > 0
                    })
                    trade_id += 1
                    current_equity += net_pnl

                    # Re-entry check if trend valid
                    trend_valid = (sim_spot > wma19) if is_bullish else (sim_spot < wma19)
                    if allow_reentry and trend_valid and reentries_count < max_reentries and h_idx < len(day_hbars) - 2:
                        reentries_count += 1
                        re_k, re_prem, _ = select_short_strike(sim_spot, is_bullish, t_years, max_premium=100.0, target_delta=0.22)
                        if is_bullish:
                            re_h_k = round((re_k * 0.98) / 50.0) * 50.0
                            re_h_prem, _ = black_scholes_put(sim_spot, re_h_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
                        else:
                            re_h_k = round((re_k * 1.02) / 50.0) * 50.0
                            re_h_prem, _ = black_scholes_call(sim_spot, re_h_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
                        
                        cur_short_k = re_k
                        cur_hedge_k = re_h_k
                        cur_short_prem = re_prem
                        cur_hedge_prem = re_h_prem
                        cur_sl_price = re_prem * (1.0 + (sl_percent / 100.0))
                    else:
                        pos_open = False
                    break

        # Mandatory EOD Square-Off at 15:15 PM
        if pos_open:
            exit_spot = day_hbars[-1].close
            # Subtract 1 intraday day fraction for theta
            t_rem = max(0.0001, (t_days - 0.25) / 365.0)
            if is_bullish:
                s_exit, _ = black_scholes_put(exit_spot, cur_short_k, t_rem, RISK_FREE_RATE, DEFAULT_IV)
                h_exit, _ = black_scholes_put(exit_spot, cur_hedge_k, t_rem, RISK_FREE_RATE, DEFAULT_IV)
            else:
                s_exit, _ = black_scholes_call(exit_spot, cur_short_k, t_rem, RISK_FREE_RATE, DEFAULT_IV)
                h_exit, _ = black_scholes_call(exit_spot, cur_hedge_k, t_rem, RISK_FREE_RATE, DEFAULT_IV)

            short_pnl = (cur_short_prem - s_exit) * LOT_SIZE
            hedge_pnl = (h_exit - cur_hedge_prem) * LOT_SIZE
            net_pnl = short_pnl + hedge_pnl - 50.0
            trades.append({
                'id': trade_id,
                'date': cur_date,
                'type': 'INTRADAY',
                'pnl': net_pnl,
                'reason': 'EOD_1515_SQUAREOFF',
                'is_win': net_pnl > 0
            })
            trade_id += 1
            current_equity += net_pnl

        daily_equity.append((cur_date, current_equity))

    tot = len(trades)
    wins = [t for t in trades if t['is_win']]
    losses = [t for t in trades if not t['is_win']]
    gp = sum(t['pnl'] for t in wins)
    gl = abs(sum(t['pnl'] for t in losses))
    net = sum(t['pnl'] for t in trades)
    pf = (gp / gl) if gl > 0 else (999.0 if gp > 0 else 0.0)
    
    peak = 150000.0
    max_dd = 0.0
    max_dd_pct = 0.0
    for _, eq in daily_equity:
        if eq > peak:
            peak = eq
        dd = peak - eq
        dd_pct = (dd / peak) * 100.0
        if dd > max_dd:
            max_dd = dd
            max_dd_pct = dd_pct

    return {
        'trades': tot,
        'wins': len(wins),
        'losses': len(losses),
        'win_rate': (len(wins) / tot * 100.0) if tot > 0 else 0.0,
        'gross_profit': gp,
        'gross_loss': gl,
        'net_pnl': net,
        'pf': pf,
        'max_dd': max_dd,
        'max_dd_pct': max_dd_pct,
        'final_eq': current_equity
    }

if __name__ == '__main__':
    from backtest_19wma_strategy import fetch_data, run_backtest_simulation
    daily_bars, hourly_bars = fetch_data()

    print("=" * 95)
    print("  INTRADAY VS POSITIONAL EXECUTION COMPARISON (2 YEARS NIFTY 50 DATA)".center(90))
    print("=" * 95)
    print(f"{'EXECUTION MODE':<38} | {'TRADES':<6} | {'WIN %':<7} | {'NET P&L (INR)':<15} | {'PF':<5} | {'MAX DD'}")
    print("-" * 95)

    # 1. Pure Intraday (Square-off 15:15 every day) with 15% SL
    res_intra_15 = run_pure_intraday_backtest(daily_bars, hourly_bars, sl_percent=15.0, allow_reentry=True, max_reentries=1)
    print(f"{'1. Pure Intraday (15% SL, EOD 15:15)':<38} | {res_intra_15['trades']:<6} | {res_intra_15['win_rate']:>5.1f}% | {res_intra_15['net_pnl']:>+14,.2f} | {res_intra_15['pf']:>5.2f} | Rs. {res_intra_15['max_dd']:,.0f} ({res_intra_15['max_dd_pct']:.1f}%)")

    # 2. Pure Intraday (30% SL, EOD 15:15)
    res_intra_30 = run_pure_intraday_backtest(daily_bars, hourly_bars, sl_percent=30.0, allow_reentry=True, max_reentries=1)
    print(f"{'2. Pure Intraday (30% SL, EOD 15:15)':<38} | {res_intra_30['trades']:<6} | {res_intra_30['win_rate']:>5.1f}% | {res_intra_30['net_pnl']:>+14,.2f} | {res_intra_30['pf']:>5.2f} | Rs. {res_intra_30['max_dd']:,.0f} ({res_intra_30['max_dd_pct']:.1f}%)")

    # 3. Pure Intraday (50% SL, EOD 15:15)
    res_intra_50 = run_pure_intraday_backtest(daily_bars, hourly_bars, sl_percent=50.0, allow_reentry=True, max_reentries=1)
    print(f"{'3. Pure Intraday (50% SL, EOD 15:15)':<38} | {res_intra_50['trades']:<6} | {res_intra_50['win_rate']:>5.1f}% | {res_intra_50['net_pnl']:>+14,.2f} | {res_intra_50['pf']:>5.2f} | Rs. {res_intra_50['max_dd']:,.0f} ({res_intra_50['max_dd_pct']:.1f}%)")

    # 4. Pure Intraday (No SL, Hold to 15:15)
    res_intra_nosl = run_pure_intraday_backtest(daily_bars, hourly_bars, sl_percent=999.0, allow_reentry=False, max_reentries=0)
    print(f"{'4. Pure Intraday (No SL, EOD 15:15)':<38} | {res_intra_nosl['trades']:<6} | {res_intra_nosl['win_rate']:>5.1f}% | {res_intra_nosl['net_pnl']:>+14,.2f} | {res_intra_nosl['pf']:>5.2f} | Rs. {res_intra_nosl['max_dd']:,.0f} ({res_intra_nosl['max_dd_pct']:.1f}%)")

    print("-" * 95)

    # 5. Positional (Multi-day hold to Expiry / Trend Reversal)
    res_pos_15 = run_backtest_simulation(daily_bars, hourly_bars, sl_percent=15.0, allow_reentry=True, max_reentries_per_day=1)
    print(f"{'5. Positional (15% SL, Hold to Expiry)':<38} | {res_pos_15['total_trades']:<6} | {res_pos_15['win_rate']:>5.1f}% | {res_pos_15['net_pnl']:>+14,.2f} | {res_pos_15['profit_factor']:>5.2f} | Rs. {res_pos_15['max_drawdown']:,.0f} ({res_pos_15['max_drawdown_pct']:.1f}%)")

    # 6. Positional (100% SL / 2x Double, Hold to Expiry)
    res_pos_100 = run_backtest_simulation(daily_bars, hourly_bars, sl_percent=100.0, allow_reentry=False, max_reentries_per_day=0)
    print(f"{'6. Positional (100% SL / 2x, Hold Expiry)':<38} | {res_pos_100['total_trades']:<6} | {res_pos_100['win_rate']:>5.1f}% | {res_pos_100['net_pnl']:>+14,.2f} | {res_pos_100['profit_factor']:>5.2f} | Rs. {res_pos_100['max_drawdown']:,.0f} ({res_pos_100['max_drawdown_pct']:.1f}%)")

    # 7. Positional (Pure 19WMA Reversal Exit)
    res_pos_rev = run_backtest_simulation(daily_bars, hourly_bars, sl_percent=999.0, allow_reentry=False, max_reentries_per_day=0)
    print(f"{'7. Positional (Pure 19WMA Reversal Exit)':<38} | {res_pos_rev['total_trades']:<6} | {res_pos_rev['win_rate']:>5.1f}% | {res_pos_rev['net_pnl']:>+14,.2f} | {res_pos_rev['profit_factor']:>5.2f} | Rs. {res_pos_rev['max_drawdown']:,.0f} ({res_pos_rev['max_drawdown_pct']:.1f}%)")

    print("=" * 95)
