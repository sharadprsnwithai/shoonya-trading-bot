#!/usr/bin/env python3
"""
Backtest Engine for 19-Period Daily WMA Hedged Option Selling Strategy on NIFTY 50
================================================================================

Strategy Rules:
- Trend Indicator: 19-period WMA on Daily Spot Close
- Evaluation Window: Daily at 09:30 AM IST
- Bullish (Spot > 19 WMA): Sell OTM Put (PE) Delta 0.20-0.25 (Premium <= 100 INR) + Buy 2% OTM Put Hedge
- Bearish (Spot < 19 WMA): Sell OTM Call (CE) Delta 0.20-0.25 (Premium <= 100 INR) + Buy 2% OTM Call Hedge
- Dynamic Expiry Routing:
    - Day <= 15th: Current Month Expiry (Last Thursday of current month)
    - Day > 15th: Next Month Expiry (Last Thursday of next month)
- Risk Management:
    - Hard SL: Short leg entry premium * (1 + SL%) (Default: 15%)
    - Re-Entry Rule: If SL hits and trend remains valid, re-enter at original entry premium
    - Profit Target: Hold to expiry (or configurable TP, e.g., 70%)
    - Expiry Closeout: 15:15 PM on expiry day
    - Trend Reversal Exit: Exit on opposite 19 WMA crossover at 09:30 AM
"""

import urllib.request
import json
import datetime
import math
import calendar
from dataclasses import dataclass
from typing import List, Dict, Optional, Tuple

LOT_SIZE = 65
RISK_FREE_RATE = 0.065 # 6.5% RBI repo rate
DEFAULT_IV = 0.145     # 14.5% Average NIFTY Implied Volatility

# ==============================================================================
# Black-Scholes Option Pricing & Greeks Engine
# ==============================================================================

def norm_cdf(x: float) -> float:
    return (1.0 + math.erf(x / math.sqrt(2.0))) / 2.0

def black_scholes_call(s: float, k: float, t: float, r: float, sigma: float) -> Tuple[float, float]:
    """Returns (Price, Delta) for Call Option"""
    if t <= 0.0001:
        price = max(0.0, s - k)
        delta = 1.0 if s > k else 0.0
        return price, delta
    d1 = (math.log(s / k) + (r + 0.5 * sigma * sigma) * t) / (sigma * math.sqrt(t))
    d2 = d1 - sigma * math.sqrt(t)
    price = s * norm_cdf(d1) - k * math.exp(-r * t) * norm_cdf(d2)
    delta = norm_cdf(d1)
    return max(0.05, price), delta

def black_scholes_put(s: float, k: float, t: float, r: float, sigma: float) -> Tuple[float, float]:
    """Returns (Price, Delta) for Put Option"""
    if t <= 0.0001:
        price = max(0.0, k - s)
        delta = -1.0 if s < k else 0.0
        return price, abs(delta)
    d1 = (math.log(s / k) + (r + 0.5 * sigma * sigma) * t) / (sigma * math.sqrt(t))
    d2 = d1 - sigma * math.sqrt(t)
    price = k * math.exp(-r * t) * norm_cdf(-d2) - s * norm_cdf(-d1)
    delta = abs(norm_cdf(d1) - 1.0) # Absolute delta
    return max(0.05, price), delta

def find_monthly_expiry(year: int, month: int) -> datetime.date:
    """Finds the last Thursday of the given month (NSE Monthly Expiry)"""
    num_days = calendar.monthrange(year, month)[1]
    last_day = datetime.date(year, month, num_days)
    # Thursday is weekday 3
    offset = (last_day.weekday() - 3) % 7
    expiry = last_day - datetime.timedelta(days=offset)
    return expiry

def get_target_expiry(trade_date: datetime.date) -> datetime.date:
    """Routes to Current Month if day <= 15th, else Next Month"""
    curr_expiry = find_monthly_expiry(trade_date.year, trade_date.month)
    if trade_date.day <= 15:
        # If trade date is before current month expiry
        if trade_date <= curr_expiry:
            return curr_expiry
    
    # Route to next month
    if trade_date.month == 12:
        next_month_year = trade_date.year + 1
        next_month = 1
    else:
        next_month_year = trade_date.year
        next_month = trade_date.month + 1
    return find_monthly_expiry(next_month_year, next_month)

def select_short_strike(spot: float, is_bullish: bool, t_years: float, max_premium: float = 100.0, target_delta: float = 0.22) -> Tuple[float, float, float]:
    """
    Selects short strike with delta ~ 0.20-0.25 and premium <= max_premium.
    Returns: (strike, premium, delta)
    """
    best_strike = None
    best_premium = 0.0
    best_delta = 0.0
    min_delta_diff = 999.0

    step = 50.0
    if is_bullish:
        # Looking for OTM PE strike below spot
        start_k = math.floor(spot / step) * step
        for i in range(1, 40):
            k = start_k - (i * step)
            prem, delta = black_scholes_put(spot, k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            if prem <= max_premium and 0.12 <= delta <= 0.30:
                diff = abs(delta - target_delta)
                if diff < min_delta_diff:
                    min_delta_diff = diff
                    best_strike = k
                    best_premium = prem
                    best_delta = delta
    else:
        # Looking for OTM CE strike above spot
        start_k = math.ceil(spot / step) * step
        for i in range(1, 40):
            k = start_k + (i * step)
            prem, delta = black_scholes_call(spot, k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            if prem <= max_premium and 0.12 <= delta <= 0.30:
                diff = abs(delta - target_delta)
                if diff < min_delta_diff:
                    min_delta_diff = diff
                    best_strike = k
                    best_premium = prem
                    best_delta = delta

    # Fallback if no strike matches exactly
    if best_strike is None:
        if is_bullish:
            best_strike = round((spot * 0.97) / step) * step
            best_premium, best_delta = black_scholes_put(spot, best_strike, t_years, RISK_FREE_RATE, DEFAULT_IV)
        else:
            best_strike = round((spot * 1.03) / step) * step
            best_premium, best_delta = black_scholes_call(spot, best_strike, t_years, RISK_FREE_RATE, DEFAULT_IV)

    return best_strike, best_premium, best_delta

# ==============================================================================
# Historical Data Downloader & 19 WMA Engine
# ==============================================================================

@dataclass
class DailyBar:
    date: datetime.date
    open: float
    high: float
    low: float
    close: float
    wma19: float = 0.0

@dataclass
class HourlyBar:
    dt: datetime.datetime
    date: datetime.date
    time: datetime.time
    open: float
    high: float
    low: float
    close: float

def fetch_data() -> Tuple[List[DailyBar], List[HourlyBar]]:
    """Fetches 2 years daily and hourly bars from Yahoo Finance"""
    headers = {'User-Agent': 'Mozilla/5.0'}
    
    # 1. Fetch Daily Data
    url_daily = 'https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=2y&interval=1d'
    req_d = urllib.request.Request(url_daily, headers=headers)
    with urllib.request.urlopen(req_d) as resp:
        d_json = json.loads(resp.read().decode())['chart']['result'][0]
    
    d_ts = d_json['timestamp']
    d_q = d_json['indicators']['quote'][0]
    
    daily_bars: List[DailyBar] = []
    for i in range(len(d_ts)):
        if d_q['open'][i] is None or d_q['close'][i] is None:
            continue
        t = datetime.datetime.fromtimestamp(d_ts[i], datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
        daily_bars.append(DailyBar(
            date=t.date(),
            open=float(d_q['open'][i]),
            high=float(d_q['high'][i]),
            low=float(d_q['low'][i]),
            close=float(d_q['close'][i])
        ))
    
    # Compute 19-period WMA on Daily Closes
    # WMA_19 = sum(i * Close_(t-19+i)) / sum(1..19) = sum / 190
    weights = list(range(1, 20))
    sum_w = sum(weights) # 190
    for idx in range(len(daily_bars)):
        if idx < 18:
            daily_bars[idx].wma19 = float('nan')
        else:
            w_sum = sum(daily_bars[idx - 18 + k].close * weights[k] for k in range(19))
            daily_bars[idx].wma19 = w_sum / sum_w

    # 2. Fetch Hourly Data for intraday path execution
    url_hourly = 'https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=2y&interval=1h'
    req_h = urllib.request.Request(url_hourly, headers=headers)
    with urllib.request.urlopen(req_h) as resp:
        h_json = json.loads(resp.read().decode())['chart']['result'][0]
        
    h_ts = h_json['timestamp']
    h_q = h_json['indicators']['quote'][0]
    
    hourly_bars: List[HourlyBar] = []
    for i in range(len(h_ts)):
        if h_q['open'][i] is None or h_q['close'][i] is None:
            continue
        t = datetime.datetime.fromtimestamp(h_ts[i], datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
        hourly_bars.append(HourlyBar(
            dt=t,
            date=t.date(),
            time=t.time(),
            open=float(h_q['open'][i]),
            high=float(h_q['high'][i]),
            low=float(h_q['low'][i]),
            close=float(h_q['close'][i])
        ))
        
    return daily_bars, hourly_bars

# ==============================================================================
# Strategy Position & Simulation
# ==============================================================================

@dataclass
class Position:
    trade_id: int
    entry_date: datetime.date
    entry_time: datetime.time
    entry_spot: float
    is_bullish: bool # True = Sold PE + Bought PE Hedge; False = Sold CE + Bought CE Hedge
    expiry: datetime.date
    
    short_strike: float
    short_entry_price: float
    short_delta: float
    
    hedge_strike: float
    hedge_entry_price: float
    
    stop_loss_price: float
    target_profit_price: Optional[float]
    
    re_entry_count: int = 0
    is_closed: bool = False
    exit_date: Optional[datetime.date] = None
    exit_spot: Optional[float] = None
    short_exit_price: float = 0.0
    hedge_exit_price: float = 0.0
    net_pnl: float = 0.0
    exit_reason: str = ""

def run_backtest_simulation(
    daily_bars: List[DailyBar],
    hourly_bars: List[HourlyBar],
    sl_percent: float = 15.0,
    tp_percent: Optional[float] = None,
    allow_reentry: bool = True,
    max_reentries_per_day: int = 1
) -> Dict:
    """
    Executes backtest over historical daily and hourly bars.
    """
    # Group hourly bars by date
    hourly_by_date: Dict[datetime.date, List[HourlyBar]] = {}
    for h in hourly_bars:
        hourly_by_date.setdefault(h.date, []).append(h)

    # Map daily bar index
    daily_map = {d.date: d for d in daily_bars}
    sorted_dates = sorted([d.date for d in daily_bars if not math.isnan(d.wma19)])

    active_pos: Optional[Position] = None
    closed_trades: List[Position] = []
    trade_id_counter = 1
    
    daily_equity = []
    current_equity = 150000.0 # Starting capital 1.5 Lakhs (1 lot hedged spread requires ~35k)

    for d_idx, cur_date in enumerate(sorted_dates):
        cur_daily = daily_map[cur_date]
        prev_daily = daily_map[sorted_dates[d_idx - 1]] if d_idx > 0 else cur_daily
        
        # 19 WMA based on previous closed day
        wma19 = prev_daily.wma19
        
        day_hbars = hourly_by_date.get(cur_date, [])
        if not day_hbars:
            continue
        
        # Spot price at 09:30 AM (approx using open of day or first bar)
        spot_0930 = day_hbars[0].open
        is_bullish = spot_0930 > wma19
        
        target_expiry = get_target_expiry(cur_date)
        t_days = max(1, (target_expiry - cur_date).days)
        t_years = t_days / 365.0

        # Check if active position is on opposite side of 19 WMA -> Close & Reverse
        if active_pos and not active_pos.is_closed:
            if active_pos.is_bullish != is_bullish:
                # Trend changed at 09:30 AM evaluation
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
                net_pnl = short_pnl + hedge_pnl - 50.0 # 50 INR brokerage/slippage per round trip
                
                active_pos.is_closed = True
                active_pos.exit_date = cur_date
                active_pos.exit_spot = exit_spot
                active_pos.short_exit_price = s_exit
                active_pos.hedge_exit_price = h_exit
                active_pos.net_pnl = net_pnl
                active_pos.exit_reason = "TREND_REVERSAL_EXIT"
                closed_trades.append(active_pos)
                current_equity += net_pnl
                active_pos = None

        # Check if active position reached expiry today -> Closeout at 15:15
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
            active_pos.exit_reason = "EXPIRY_SQUARE_OFF (15:15)"
            closed_trades.append(active_pos)
            current_equity += net_pnl
            active_pos = None

        # Open Position if none active
        reentries_today = 0
        if active_pos is None:
            short_k, short_prem, short_delta = select_short_strike(spot_0930, is_bullish, t_years, max_premium=100.0, target_delta=0.22)
            
            # Hedge strike = 2.0% OTM from short strike
            if is_bullish:
                hedge_k = round((short_k * 0.98) / 50.0) * 50.0
                hedge_prem, _ = black_scholes_put(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            else:
                hedge_k = round((short_k * 1.02) / 50.0) * 50.0
                hedge_prem, _ = black_scholes_call(spot_0930, hedge_k, t_years, RISK_FREE_RATE, DEFAULT_IV)
            
            sl_price = short_prem * (1.0 + (sl_percent / 100.0))
            tp_price = short_prem * (1.0 - (tp_percent / 100.0)) if tp_percent else None
            
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
                target_profit_price=tp_price
            )
            trade_id_counter += 1

        # Intraday Bar-by-Bar Path Simulation (SL, TP, Re-entry checks)
        for hbar in day_hbars:
            if active_pos is None:
                break
            
            # Estimate remaining time in years
            rem_days = max(0.001, (active_pos.expiry - cur_date).days)
            rem_t = rem_days / 365.0
            
            # Check price range of current hourly candle
            for sim_spot in [hbar.open, hbar.high, hbar.low, hbar.close]:
                if active_pos is None:
                    break
                
                if active_pos.is_bullish:
                    s_px, _ = black_scholes_put(sim_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    h_px, _ = black_scholes_put(sim_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                else:
                    s_px, _ = black_scholes_call(sim_spot, active_pos.short_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                    h_px, _ = black_scholes_call(sim_spot, active_pos.hedge_strike, rem_t, RISK_FREE_RATE, DEFAULT_IV)

                # 1. Check Hard SL Hit
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
                    active_pos.exit_reason = f"HARD_SL_HIT ({sl_percent}%)"
                    closed_trades.append(active_pos)
                    current_equity += net_pnl
                    
                    # 2. Re-entry Logic: If trend unchanged & within re-entry limits
                    current_trend_valid = (sim_spot > wma19) if active_pos.is_bullish else (sim_spot < wma19)
                    if allow_reentry and current_trend_valid and reentries_today < max_reentries_per_day:
                        # Re-enter at original entry strike/premium when price pulls back
                        reentries_today += 1
                        re_short_k, re_short_prem, re_delta = select_short_strike(sim_spot, active_pos.is_bullish, rem_t, max_premium=100.0, target_delta=0.22)
                        
                        if active_pos.is_bullish:
                            re_hedge_k = round((re_short_k * 0.98) / 50.0) * 50.0
                            re_hedge_prem, _ = black_scholes_put(sim_spot, re_hedge_k, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                        else:
                            re_hedge_k = round((re_short_k * 1.02) / 50.0) * 50.0
                            re_hedge_prem, _ = black_scholes_call(sim_spot, re_hedge_k, rem_t, RISK_FREE_RATE, DEFAULT_IV)
                        
                        active_pos = Position(
                            trade_id=trade_id_counter,
                            entry_date=cur_date,
                            entry_time=hbar.time,
                            entry_spot=sim_spot,
                            is_bullish=active_pos.is_bullish,
                            expiry=active_pos.expiry,
                            short_strike=re_short_k,
                            short_entry_price=re_short_prem,
                            short_delta=re_delta,
                            hedge_strike=re_hedge_k,
                            hedge_entry_price=re_hedge_prem,
                            stop_loss_price=re_short_prem * (1.0 + (sl_percent / 100.0)),
                            target_profit_price=re_short_prem * (1.0 - (tp_percent / 100.0)) if tp_percent else None,
                            re_entry_count=reentries_today
                        )
                        trade_id_counter += 1
                    else:
                        active_pos = None
                    break

                # 3. Check Target Profit Hit (if enabled)
                if active_pos and active_pos.target_profit_price and s_px <= active_pos.target_profit_price:
                    short_pnl = (active_pos.short_entry_price - s_px) * LOT_SIZE
                    hedge_pnl = (h_px - active_pos.hedge_entry_price) * LOT_SIZE
                    net_pnl = short_pnl + hedge_pnl - 50.0
                    
                    active_pos.is_closed = True
                    active_pos.exit_date = cur_date
                    active_pos.exit_spot = sim_spot
                    active_pos.short_exit_price = s_px
                    active_pos.hedge_exit_price = h_px
                    active_pos.net_pnl = net_pnl
                    active_pos.exit_reason = f"TARGET_PROFIT_HIT ({tp_percent}%)"
                    closed_trades.append(active_pos)
                    current_equity += net_pnl
                    active_pos = None
                    break

        daily_equity.append((cur_date, current_equity))

    # Close open position at end of backtest
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

    # Compute Statistics
    total_trades = len(closed_trades)
    winning_trades = [t for t in closed_trades if t.net_pnl > 0]
    losing_trades = [t for t in closed_trades if t.net_pnl <= 0]
    
    win_rate = (len(winning_trades) / total_trades * 100.0) if total_trades > 0 else 0.0
    gross_profit = sum(t.net_pnl for t in winning_trades)
    gross_loss = abs(sum(t.net_pnl for t in losing_trades))
    net_pnl = sum(t.net_pnl for t in closed_trades)
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else (999.0 if gross_profit > 0 else 0.0)
    
    # Calculate Max Drawdown
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

def print_result_table(title: str, res: Dict):
    print(f"\n{'=' * 88}")
    print(f"  {title.center(84)}")
    print(f"{'=' * 88}")
    print(f"  Total Trades Executed       : {res['total_trades']}")
    print(f"  Winning Trades / Losing     : {res['wins']} / {res['losses']}")
    print(f"  Win Rate (%)                : {res['win_rate']:.2f}%")
    print(f"  Gross Profit (INR)          : +Rs. {res['gross_profit']:,.2f}")
    print(f"  Gross Loss (INR)            : -Rs. {res['gross_loss']:,.2f}")
    print(f"  Net Realized P&L (INR)      : +Rs. {res['net_pnl']:,.2f}" if res['net_pnl'] >= 0 else f"  Net Realized P&L (INR)      : -Rs. {abs(res['net_pnl']):,.2f}")
    print(f"  Profit Factor               : {res['profit_factor']:.2f}")
    print(f"  Max Drawdown (INR / %)      : Rs. {res['max_drawdown']:,.2f} ({res['max_drawdown_pct']:.2f}%)")
    print(f"  Total Return on Capital     : {res['return_pct']:+.2f}% (Started with Rs. 1,50,000)")
    print(f"{'=' * 88}\n")

if __name__ == '__main__':
    print("Fetching historical NIFTY 50 daily and intraday candle data...")
    daily_bars, hourly_bars = fetch_data()
    print(f"Loaded {len(daily_bars)} daily bars and {len(hourly_bars)} intraday hourly bars across 2 years.\n")

    # 1. Exact Strategy Spec (15% SL, Unlimited Re-entry, Hold to Expiry)
    res_exact = run_backtest_simulation(
        daily_bars, hourly_bars,
        sl_percent=15.0,
        tp_percent=None,
        allow_reentry=True,
        max_reentries_per_day=5
    )
    print_result_table("CONFIGURATION 1: EXACT USER SPEC (15% SL, RE-ENTRY, HOLD TO EXPIRY)", res_exact)

    # 2. Calibrated SL Sensitivity
    print(f"{'=' * 88}")
    print(f"  STOP-LOSS & PROFIT-TARGET SENSITIVITY GRID SEARCH (2-YEAR NIFTY DATA)".center(84))
    print(f"{'=' * 88}")
    print(f"{'CONFIG / SL / TP':<40} | {'TRADES':<7} | {'WIN %':<8} | {'NET P&L (INR)':<15} | {'PF':<6} | {'MAX DD'}")
    print("-" * 88)

    sl_configs = [
        ("15% SL (Hold to Expiry)", 15.0, None, True, 1),
        ("20% SL (Hold to Expiry)", 20.0, None, True, 1),
        ("25% SL (Hold to Expiry)", 25.0, None, True, 1),
        ("30% SL (Hold to Expiry)", 30.0, None, True, 1),
        ("35% SL (Hold to Expiry)", 35.0, None, True, 1),
        ("50% SL (Hold to Expiry)", 50.0, None, True, 1),
        ("30% SL + 50% Profit Target", 30.0, 50.0, True, 1),
        ("30% SL + 70% Profit Target", 30.0, 70.0, True, 1),
        ("35% SL + 70% Profit Target", 35.0, 70.0, True, 1),
        ("No SL (Hold to Expiry/Reversal)", 999.0, None, False, 0),
    ]

    for label, sl, tp, reentry, max_re in sl_configs:
        r = run_backtest_simulation(
            daily_bars, hourly_bars,
            sl_percent=sl,
            tp_percent=tp,
            allow_reentry=reentry,
            max_reentries_per_day=max_re
        )
        print(f"{label:<40} | {r['total_trades']:<7} | {r['win_rate']:>5.1f}% | {r['net_pnl']:>+14,.2f} | {r['profit_factor']:>5.2f} | Rs. {r['max_drawdown']:,.0f} ({r['max_drawdown_pct']:.1f}%)")

    print(f"{'=' * 88}\n")

    # Sample Trade Logs from Exact Configuration
    print("RECENT TRADE EXECUTIONS (LAST 10 TRADES):")
    print(f"{'ID':<4} | {'ENTRY DT':<10} | {'BIAS':<7} | {'SHORT K':<9} | {'PREM':<6} | {'HEDGE K':<9} | {'EXIT DT':<10} | {'EXIT REASON':<28} | {'NET P&L'}")
    print("-" * 115)
    for t in res_exact['trades'][-10:]:
        bias = "PUT (BULL)" if t.is_bullish else "CALL (BEAR)"
        print(f"{t.trade_id:<4} | {str(t.entry_date):<10} | {bias:<7} | {t.short_strike:<9.0f} | Rs.{t.short_entry_price:<4.1f} | {t.hedge_strike:<9.0f} | {str(t.exit_date):<10} | {t.exit_reason:<28} | Rs. {t.net_pnl:>+8.2f}")
    print("-" * 115)
