#!/usr/bin/env python3
"""
Advanced Institutional Optimization for 19-Period WMA Hedged Strategy
=====================================================================

Explores:
1. Daily Close vs Intraday Exit (eliminating noise stops)
2. Structural Stop Loss (e.g. 1x Entry, 2x Entry, Max Spread Risk vs 15% Intraday)
3. Delta Selection (0.15 vs 0.22 vs 0.30)
4. Weekly vs Monthly Expiry Routing
5. Momentum / Slope confirmation on 19 WMA
"""

import datetime
import math
from typing import List, Dict, Optional, Tuple
from backtest_19wma_strategy import (
    DailyBar, HourlyBar, Position, fetch_data, select_short_strike,
    black_scholes_call, black_scholes_put, get_target_expiry,
    LOT_SIZE, RISK_FREE_RATE, DEFAULT_IV
)

def run_institutional_backtest(
    daily_bars: List[DailyBar],
    hourly_bars: List[HourlyBar],
    exit_on_daily_close_only: bool = True,
    sl_multiplier: float = 2.0, # 2.0 means exit if option doubles (100% loss)
    target_delta: float = 0.22,
    max_premium: float = 120.0
) -> Dict:
    hourly_by_date: Dict[datetime.date, List[HourlyBar]] = {}
    for h in hourly_bars:
        hourly_by_date.setdefault(h.date, []).append(h)

    daily_map = {d.date: d for d in daily_bars}
    sorted_dates = sorted([d.date for d in daily_bars if not math.isnan(d.wma19)])

    active_pos: Optional[Position] = None
    closed_trades: List[Position] = []
    trade_id_counter = 1
    
    daily_equity = []
    current_equity = 150000.0

    for d_idx, cur_date in enumerate(sorted_dates):
        cur_daily = daily_map[cur_date]
        prev_daily = daily_map[sorted_dates[d_idx - 1]] if d_idx > 0 else cur_daily
        
        # 19 WMA from yesterday's daily close
        wma19 = prev_daily.wma19
        
        day_hbars = hourly_by_date.get(cur_date, [])
        if not day_hbars:
            continue
        
        spot_0930 = day_hbars[0].open
        is_bullish = spot_0930 > wma19
        target_expiry = get_target_expiry(cur_date)
        t_days = max(1, (target_expiry - cur_date).days)
        t_years = t_days / 365.0

        # Check Trend Reversal on 09:30 AM
        if active_pos and not active_pos.is_closed:
            if active_pos.is_bullish != is_bullish:
                exit_spot = spot_0930
                rem_t = max(0.0001, (active_pos.expiry - cur_date).days / 365.0)
                if active_pos.is_bullish:
                    s_exit, _ = black_scholes_put(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    h_exit, _ = black_scholes_put(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                else:
                    s_exit, _ = black_scholes_call(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    h_exit, _ = black_scholes_call(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                
                short_pnl = (active_pos.short_entry_price - s_exit) * LOT_SIZE
                hedge_pnl = (h_exit - active_pos.hedge_entry_price) * LOT_SIZE
                net_pnl = short_pnl + hedge_pnl - 50.0
                
                active_pos.is_closed = True
                active_pos.exit_date = cur_date
                active_pos.exit_spot = exit_spot
                active_pos.short_exit_price = s_exit
                active_pos.hedge_exit_price = h_exit
                active_pos.net_pnl = net_pnl
                active_pos.exit_reason = "19WMA_TREND_REVERSAL"
                closed_trades.append(active_pos)
                current_equity += net_pnl
                active_pos = None

        # Check Expiry Day Square Off at 15:15
        if active_pos and not active_pos.is_closed and cur_date >= active_pos.expiry:
            exit_spot = day_hbars[-1].close
            rem_t = 0.0001
            if active_pos.is_bullish:
                s_exit, _ = black_scholes_put(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                h_exit, _ = black_scholes_put(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
            else:
                s_exit, _ = black_scholes_call(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                h_exit, _ = black_scholes_call(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
            
            short_pnl = (active_pos.short_entry_price - s_exit) * LOT_SIZE
            hedge_pnl = (h_exit - active_pos.hedge_entry_price) * LOT_SIZE
            net_pnl = short_pnl + hedge_pnl - 50.0
            
            active_pos.is_closed = True
            active_pos.exit_date = cur_date
            active_pos.exit_spot = exit_spot
            active_pos.short_exit_price = s_exit
            active_pos.hedge_exit_price = h_exit
            active_pos.net_pnl = net_pnl
            active_pos.exit_reason = "EXPIRY_SQUARE_OFF"
            closed_trades.append(active_pos)
            current_equity += net_pnl
            active_pos = None

        # Open Position
        if active_pos is None:
            short_k, short_prem, short_delta = select_short_strike(spot_0930, is_bullish, t_years, max_premium=max_premium, target_delta=target_delta)
            
            if is_bullish:
                hedge_k = round((short_k * 0.98) / 50.0) * 50.0
                hedge_prem, _ = black_scholes_put(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            else:
                hedge_k = round((short_k * 1.02) / 50.0) * 50.0
                hedge_prem, _ = black_scholes_call(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            
            sl_price = short_prem * sl_multiplier
            
            active_pos = Position(
                trade_id=trade_id_counter,
                entry_date=cur_date,
                entry_time=datetime.time(9, 30),
                entry_spot=spot_0930,
                is_bullish=is_bullish,
                expiry=target_expiry,
                short_strike=short_k,
                short_entry_price=short_prem,
                short_delta=short_delta,
                hedge_strike=hedge_k,
                hedge_entry_price=hedge_prem,
                stop_loss_price=sl_price,
                target_profit_price=None
            )
            trade_id_counter += 1

        # Check structural stop loss
        if not exit_on_daily_close_only and active_pos and not active_pos.is_closed:
            rem_t = max(0.001, (active_pos.expiry - cur_date).days / 365.0)
            for hbar in day_hbars:
                if active_pos is None or active_pos.is_closed:
                    break
                for sim_spot in [hbar.high, hbar.low]:
                    if active_pos.is_bullish:
                        s_px, _ = black_scholes_put(sim_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                        h_px, _ = black_scholes_put(sim_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    else:
                        s_px, _ = black_scholes_call(sim_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                        h_px, _ = black_scholes_call(sim_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    
                    if s_px >= active_pos.stop_loss_price:
                        short_pnl = (active_pos.short_entry_price - s_px) * LOT_SIZE
                        hedge_pnl = (h_px - active_pos.hedge_entry_price) * LOT_SIZE
                        net_pnl = short_pnl + hedge_pnl - 50.0
                        
                        active_pos.is_closed = True
                        active_pos.exit_date = cur_date
                        active_pos.exit_spot = sim_spot
                        active_pos.short_exit_price = s_px
                        active_pos.hedge_exit_price = h_px
                        active_pos.net_pnl = net_pnl
                        active_pos.exit_reason = f"STRUCTURAL_SL_HIT ({sl_multiplier:.1f}x)"
                        closed_trades.append(active_pos)
                        current_equity += net_pnl
                        active_pos = None
                        break

        daily_equity.append((cur_date, current_equity))

    # Close open position
    if active_pos and not active_pos.is_closed:
        last_d = daily_bars[-1]
        exit_spot = last_d.close
        rem_t = max(0.0001, (active_pos.expiry - last_d.date).days / 365.0)
        if active_pos.is_bullish:
            s_exit, _ = black_scholes_put(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
            h_exit, _ = black_scholes_put(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
        else:
            s_exit, _ = black_scholes_call(exit_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
            h_exit, _ = black_scholes_call(exit_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
        
        short_pnl = (active_pos.short_entry_price - s_exit) * LOT_SIZE
        hedge_pnl = (h_exit - active_pos.hedge_entry_price) * LOT_SIZE
        net_pnl = short_pnl + hedge_pnl - 50.0
        
        active_pos.is_closed = True
        active_pos.exit_date = last_d.date
        active_pos.exit_spot = exit_spot
        active_pos.short_exit_price = s_exit
        active_pos.hedge_exit_price = h_exit
        active_pos.net_pnl = net_pnl
        active_pos.exit_reason = "END_OF_BACKTEST"
        closed_trades.append(active_pos)
        current_equity += net_pnl

    total_trades = len(closed_trades)
    winning_trades = [t for t in closed_trades if t.net_pnl > 0]
    losing_trades = [t for t in closed_trades if t.net_pnl <= 0]
    
    win_rate = (len(winning_trades) / total_trades * 100.0) if total_trades > 0 else 0.0
    gross_profit = sum(t.net_pnl for t in winning_trades)
    gross_loss = abs(sum(t.net_pnl for t in losing_trades))
    net_pnl = sum(t.net_pnl for t in closed_trades)
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else (999.0 if gross_profit > 0 else 0.0)
    
    peak_equity = 150000.0
    max_dd = 0.0
    max_dd_pct = 0.0
    for _, eq in daily_equity:
        if eq > peak_equity:
            peak_equity = eq
        dd = peak_equity - eq
        dd_pct = (dd / peak_equity) * 100.0
        if dd > max_dd:
            max_dd = dd
            max_dd_pct = dd_pct

    return {
        'total_trades': total_trades,
        'wins': len(winning_trades),
        'losses': len(losing_trades),
        'win_rate': win_rate,
        'gross_profit': gross_profit,
        'gross_loss': gross_loss,
        'net_pnl': net_pnl,
        'profit_factor': profit_factor,
        'max_drawdown': max_dd,
        'max_drawdown_pct': max_dd_pct,
        'final_equity': current_equity,
        'return_pct': ((current_equity - 150000.0) / 150000.0) * 100.0,
        'trades': closed_trades,
        'equity_curve': daily_equity
    }

if __name__ == '__main__':
    daily_bars, hourly_bars = fetch_data()

    print("=" * 95)
    print("  INSTITUTIONAL STRUCTURAL OPTIMIZATION MATRIX (2 YEARS NIFTY 50 DATA)".center(90))
    print("=" * 95)
    print(f"{'STRATEGY VARIATION':<45} | {'TRADES':<6} | {'WIN %':<7} | {'NET P&L (INR)':<15} | {'PF':<5} | {'MAX DD'}")
    print("-" * 95)

    experiments = [
        ("1. Exact User Spec (15% SL Intraday)", False, 1.15, 0.22, 100.0),
        ("2. 50% SL Intraday (1.5x Entry)", False, 1.50, 0.22, 100.0),
        ("3. 100% SL Intraday (2.0x Entry Double)", False, 2.00, 0.22, 100.0),
        ("4. 150% SL Intraday (2.5x Entry)", False, 2.50, 0.22, 100.0),
        ("5. Pure Trend Reversal Exit (Hedge Protected)", True, 999.0, 0.22, 100.0),
        ("6. Pure Trend Reversal Exit (Delta 0.25)", True, 999.0, 0.25, 120.0),
        ("7. Pure Trend Reversal Exit (Delta 0.18 Conservative)", True, 999.0, 0.18, 80.0),
    ]

    for label, daily_only, sl_mult, delta, max_p in experiments:
        r = run_institutional_backtest(
            daily_bars, hourly_bars,
            exit_on_daily_close_only=daily_only,
            sl_multiplier=sl_mult,
            target_delta=delta,
            max_premium=max_p
        )
        print(f"{label:<45} | {r['total_trades']:<6} | {r['win_rate']:>5.1f}% | {r['net_pnl']:>+14,.2f} | {r['profit_factor']:>5.2f} | Rs. {r['max_drawdown']:,.0f} ({r['max_drawdown_pct']:.1f}%)")

    print("=" * 95)
