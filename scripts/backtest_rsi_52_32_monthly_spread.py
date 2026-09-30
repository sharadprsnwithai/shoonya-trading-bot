import sys
import io
import math
import calendar
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

# Set UTF-8 encoding for stdout
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def norm_cdf(x):
    """Standard normal cumulative distribution function using math.erf."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))

# ---------------------------------------------------------
# Black-Scholes Option Pricing
# ---------------------------------------------------------
def bs_call_price(S, K, T, r, sigma):
    if T <= 0.0001:
        return max(0.0, S - K)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return S * norm_cdf(d1) - K * math.exp(-r * T) * norm_cdf(d2)

def bs_put_price(S, K, T, r, sigma):
    if T <= 0.0001:
        return max(0.0, K - S)
    d1 = (math.log(S / K) + (r + 0.5 * sigma ** 2) * T) / (sigma * math.sqrt(T))
    d2 = d1 - sigma * math.sqrt(T)
    return K * math.exp(-r * T) * norm_cdf(-d2) - S * norm_cdf(-d1)

# ---------------------------------------------------------
# Expiry & Rollover Calendar Utilities
# ---------------------------------------------------------
def get_monthly_expiry(year, month):
    """Last Thursday of the month."""
    cal = calendar.monthcalendar(year, month)
    # Thursdays are index 3
    thursdays = [week[calendar.THURSDAY] for week in cal if week[calendar.THURSDAY] != 0]
    last_thursday = thursdays[-1]
    return datetime.date(year, month, last_thursday)

def get_rollover_date(year, month):
    """Wednesday of the week preceding expiry week (2nd to last Wednesday)."""
    cal = calendar.monthcalendar(year, month)
    wednesdays = [week[calendar.WEDNESDAY] for week in cal if week[calendar.WEDNESDAY] != 0]
    if len(wednesdays) >= 2:
        rollover_day = wednesdays[-2]
    else:
        rollover_day = wednesdays[-1]
    return datetime.date(year, month, rollover_day)

def get_next_month(year, month):
    if month == 12:
        return year + 1, 1
    return year, month + 1

# ---------------------------------------------------------
# RSI Calculation
# ---------------------------------------------------------
def calculate_rsi(series, period=14):
    delta = series.diff()
    gain = (delta.where(delta > 0, 0)).copy()
    loss = (-delta.where(delta < 0, 0)).copy()
    
    # Wilder's smoothing
    avg_gain = gain.rolling(window=period, min_periods=period).mean()
    avg_loss = loss.rolling(window=period, min_periods=period).mean()
    
    for i in range(period, len(series)):
        avg_gain.iloc[i] = (avg_gain.iloc[i-1] * (period - 1) + gain.iloc[i]) / period
        avg_loss.iloc[i] = (avg_loss.iloc[i-1] * (period - 1) + loss.iloc[i]) / period
        
    rs = avg_gain / avg_loss.replace(0, np.nan)
    rsi = 100 - (100 / (1 + rs))
    return rsi.fillna(50)

# ---------------------------------------------------------
# Strike Selection Engine
# ---------------------------------------------------------
def select_spread_strikes(spot, spread_type, expiry_date, current_date, iv=0.14, r=0.065):
    """
    Finds (short_strike, hedge_strike) meeting:
      - Short leg is OTM
      - Strike multiples of 100 preferred
      - Width <= 2.5% of Spot (approx 400 - 600 pts)
      - Net Credit in [90, 140] points
      - Objective: Farthest OTM Short Strike
    """
    days_to_exp = max(0.5, (expiry_date - current_date).days)
    T = days_to_exp / 365.0
    
    max_width = spot * 0.025 # 2.5%
    base_strike = round(spot / 100.0) * 100
    
    candidates = []
    
    if spread_type == 'BEAR_CALL_SPREAD':
        # Short Call Strike >= base_strike + 100 (OTM)
        for short_k in range(int(base_strike) + 100, int(base_strike) + 1500, 100):
            short_prem = bs_call_price(spot, short_k, T, r, iv)
            if short_prem < 90: # Can't get 90 credit if short leg itself is < 90
                break
            for hedge_k in range(short_k + 100, short_k + int(max_width) + 100, 100):
                width = hedge_k - short_k
                if width > max_width:
                    break
                hedge_prem = bs_call_price(spot, hedge_k, T, r, iv)
                net_credit = short_prem - hedge_prem
                if 90.0 <= net_credit <= 140.0:
                    distance = short_k - spot
                    candidates.append({
                        'short_strike': short_k,
                        'hedge_strike': hedge_k,
                        'short_prem': short_prem,
                        'hedge_prem': hedge_prem,
                        'net_credit': net_credit,
                        'width': width,
                        'distance': distance
                    })
                    
    elif spread_type == 'BULL_PUT_SPREAD':
        # Short Put Strike <= base_strike - 100 (OTM)
        for short_k in range(int(base_strike) - 100, int(base_strike) - 1500, -100):
            short_prem = bs_put_price(spot, short_k, T, r, iv)
            if short_prem < 90:
                break
            for hedge_k in range(short_k - 100, short_k - int(max_width) - 100, -100):
                width = short_k - hedge_k
                if width > max_width:
                    break
                hedge_prem = bs_put_price(spot, hedge_k, T, r, iv)
                net_credit = short_prem - hedge_prem
                if 90.0 <= net_credit <= 140.0:
                    distance = spot - short_k
                    candidates.append({
                        'short_strike': short_k,
                        'hedge_strike': hedge_k,
                        'short_prem': short_prem,
                        'hedge_prem': hedge_prem,
                        'net_credit': net_credit,
                        'width': width,
                        'distance': distance
                    })

    if not candidates:
        # Fallback: relax credit slightly [75, 150]
        if spread_type == 'BEAR_CALL_SPREAD':
            for short_k in range(int(base_strike) + 100, int(base_strike) + 1500, 100):
                short_prem = bs_call_price(spot, short_k, T, r, iv)
                for hedge_k in range(short_k + 100, short_k + int(max_width) + 100, 100):
                    if (hedge_k - short_k) > max_width: break
                    hedge_prem = bs_call_price(spot, hedge_k, T, r, iv)
                    net_credit = short_prem - hedge_prem
                    if 75.0 <= net_credit <= 150.0:
                        candidates.append({
                            'short_strike': short_k,
                            'hedge_strike': hedge_k,
                            'short_prem': short_prem,
                            'hedge_prem': hedge_prem,
                            'net_credit': net_credit,
                            'width': hedge_k - short_k,
                            'distance': short_k - spot
                        })
        else:
            for short_k in range(int(base_strike) - 100, int(base_strike) - 1500, -100):
                short_prem = bs_put_price(spot, short_k, T, r, iv)
                for hedge_k in range(short_k - 100, short_k - int(max_width) - 100, -100):
                    if (short_k - hedge_k) > max_width: break
                    hedge_prem = bs_put_price(spot, hedge_k, T, r, iv)
                    net_credit = short_prem - hedge_prem
                    if 75.0 <= net_credit <= 150.0:
                        candidates.append({
                            'short_strike': short_k,
                            'hedge_strike': hedge_k,
                            'short_prem': short_prem,
                            'hedge_prem': hedge_prem,
                            'net_credit': net_credit,
                            'width': short_k - hedge_k,
                            'distance': spot - short_k
                        })

    if candidates:
        # Sort by maximum distance from spot (farthest OTM)
        candidates.sort(key=lambda x: x['distance'], reverse=True)
        return candidates[0]
    
    # Extreme fallback: 400 pt standard OTM spread
    if spread_type == 'BEAR_CALL_SPREAD':
        sk = int(base_strike) + 200
        hk = sk + 400
        sp = bs_call_price(spot, sk, T, r, iv)
        hp = bs_call_price(spot, hk, T, r, iv)
        return {'short_strike': sk, 'hedge_strike': hk, 'short_prem': sp, 'hedge_prem': hp, 'net_credit': sp - hp, 'width': 400, 'distance': sk - spot}
    else:
        sk = int(base_strike) - 200
        hk = sk - 400
        sp = bs_put_price(spot, sk, T, r, iv)
        hp = bs_put_price(spot, hk, T, r, iv)
        return {'short_strike': sk, 'hedge_strike': hk, 'short_prem': sp, 'hedge_prem': hp, 'net_credit': sp - hp, 'width': 400, 'distance': spot - sk}

# ---------------------------------------------------------
# Main Backtester
# ---------------------------------------------------------
def run_backtest(lot_size=25, num_lots=2, iv=0.135):
    print("=" * 110)
    print("📈 IBBM RSI (52/32) MONTHLY CREDIT SPREAD STRATEGY - HISTORICAL BACKTEST")
    print("=" * 110)
    print(f"⚙️ Configuration: Timeframe = 1-Hour | RSI Period = 14 | Upper = 52 | Lower = 32")
    print(f"📦 Trade Structure: OTM Monthly Credit Spread (Credit 90-140 pts, Max Width 2-2.5%)")
    print(f"🔄 Roll-over Rule: 2nd-to-last Wednesday 15:15 IST before Monthly Expiry")
    print(f"💰 Position Sizing: {num_lots} lots x {lot_size} qty = {num_lots * lot_size} Total Units")
    print("=" * 110)

    # Download 1h data for Nifty
    df = yf.download('^NSEI', period='730d', interval='1h', progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna().copy()
    
    # Compute RSI
    df['RSI'] = calculate_rsi(df['Close'], period=14)
    df = df.dropna().copy()
    
    print(f"📊 Historical Data: {len(df)} 1-Hour candles from {df.index[0]} to {df.index[-1]}\n")

    r = 0.065
    position = None # None, 'BEAR_CALL_SPREAD', 'BULL_PUT_SPREAD'
    active_spread = None
    trades = []

    for i in range(14, len(df)):
        ts = df.index[i]
        curr_date = ts.date()
        spot = df['Close'].iloc[i]
        rsi = df['RSI'].iloc[i]
        hour = ts.hour
        minute = ts.minute

        # 1. Determine Expiry selection for new trade
        if curr_date.day <= 15:
            exp_year, exp_month = curr_date.year, curr_date.month
        else:
            exp_year, exp_month = get_next_month(curr_date.year, curr_date.month)
        
        target_expiry = get_monthly_expiry(exp_year, exp_month)
        rollover_date = get_rollover_date(exp_year, exp_month)

        # 2. Manage Active Position
        if position is not None and active_spread is not None:
            active_expiry = active_spread['expiry']
            active_rollover = active_spread['rollover_date']
            days_to_exp = max(0.001, (active_expiry - curr_date).days + (15.5 - hour) / 24.0)
            T_now = days_to_exp / 365.0
            
            # Current mark-to-market pricing
            if active_spread['type'] == 'BEAR_CALL_SPREAD':
                current_short_p = bs_call_price(spot, active_spread['short_strike'], T_now, r, iv)
                current_hedge_p = bs_call_price(spot, active_spread['hedge_strike'], T_now, r, iv)
            else:
                current_short_p = bs_put_price(spot, active_spread['short_strike'], T_now, r, iv)
                current_hedge_p = bs_put_price(spot, active_spread['hedge_strike'], T_now, r, iv)
            
            current_spread_val = current_short_p - current_hedge_p
            
            # Check Exit Conditions:
            # Condition A: RSI Signal Reversal
            reversal = False
            if active_spread['type'] == 'BEAR_CALL_SPREAD' and rsi > 52.0:
                reversal = True
                exit_reason = "RSI_FLIP_BULLISH (>52)"
            elif active_spread['type'] == 'BULL_PUT_SPREAD' and rsi < 32.0:
                reversal = True
                exit_reason = "RSI_FLIP_BEARISH (<32)"

            # Condition B: Roll-over on 2nd-to-last Wednesday (15:15 IST)
            rollover_trigger = False
            if curr_date >= active_rollover and (hour >= 15 or curr_date > active_rollover):
                rollover_trigger = True
                exit_reason = "EXPIRY_ROLLOVER (2nd-last Wed)"

            # Condition C: Reached Expiry Day End
            expired = False
            if curr_date >= active_expiry and hour >= 15:
                expired = True
                exit_reason = "EXPIRY_SETTLEMENT"

            if reversal or rollover_trigger or expired:
                # Close Trade
                entry_credit = active_spread['net_credit']
                exit_spread_val = max(0.0, current_spread_val)
                pnl_pts = entry_credit - exit_spread_val
                pnl_rs = pnl_pts * (num_lots * lot_size)
                
                trade_record = {
                    'trade_id': len(trades) + 1,
                    'type': active_spread['type'],
                    'entry_time': active_spread['entry_time'],
                    'exit_time': ts,
                    'entry_spot': active_spread['entry_spot'],
                    'exit_spot': spot,
                    'short_strike': active_spread['short_strike'],
                    'hedge_strike': active_spread['hedge_strike'],
                    'expiry': active_spread['expiry'],
                    'entry_credit': entry_credit,
                    'exit_spread_val': exit_spread_val,
                    'pnl_pts': pnl_pts,
                    'pnl_rs': pnl_rs,
                    'exit_reason': exit_reason,
                    'days_held': (curr_date - active_spread['entry_time'].date()).days
                }
                trades.append(trade_record)
                
                # If it was a reversal, immediately enter new opposite position
                if reversal:
                    new_type = 'BULL_PUT_SPREAD' if active_spread['type'] == 'BEAR_CALL_SPREAD' else 'BEAR_CALL_SPREAD'
                    spread_info = select_spread_strikes(spot, new_type, target_expiry, curr_date, iv, r)
                    position = new_type
                    active_spread = {
                        'type': new_type,
                        'entry_time': ts,
                        'entry_spot': spot,
                        'short_strike': spread_info['short_strike'],
                        'hedge_strike': spread_info['hedge_strike'],
                        'expiry': target_expiry,
                        'rollover_date': rollover_date,
                        'net_credit': spread_info['net_credit'],
                        'short_prem': spread_info['short_prem'],
                        'hedge_prem': spread_info['hedge_prem']
                    }
                    continue
                elif rollover_trigger:
                    # Roll over to next month with same direction
                    next_y, next_m = get_next_month(active_expiry.year, active_expiry.month)
                    next_expiry = get_monthly_expiry(next_y, next_m)
                    next_rollover = get_rollover_date(next_y, next_m)
                    spread_info = select_spread_strikes(spot, active_spread['type'], next_expiry, curr_date, iv, r)
                    active_spread = {
                        'type': active_spread['type'],
                        'entry_time': ts,
                        'entry_spot': spot,
                        'short_strike': spread_info['short_strike'],
                        'hedge_strike': spread_info['hedge_strike'],
                        'expiry': next_expiry,
                        'rollover_date': next_rollover,
                        'net_credit': spread_info['net_credit'],
                        'short_prem': spread_info['short_prem'],
                        'hedge_prem': spread_info['hedge_prem']
                    }
                    continue
                else:
                    position = None
                    active_spread = None

        # 3. If Flat, Check Entry Signal
        if position is None:
            if rsi < 32.0:
                spread_type = 'BEAR_CALL_SPREAD'
            elif rsi > 52.0:
                spread_type = 'BULL_PUT_SPREAD'
            else:
                spread_type = None

            if spread_type is not None:
                spread_info = select_spread_strikes(spot, spread_type, target_expiry, curr_date, iv, r)
                position = spread_type
                active_spread = {
                    'type': spread_type,
                    'entry_time': ts,
                    'entry_spot': spot,
                    'short_strike': spread_info['short_strike'],
                    'hedge_strike': spread_info['hedge_strike'],
                    'expiry': target_expiry,
                    'rollover_date': rollover_date,
                    'net_credit': spread_info['net_credit'],
                    'short_prem': spread_info['short_prem'],
                    'hedge_prem': spread_info['hedge_prem']
                }

    # ---------------------------------------------------------
    # Performance Reporting
    # ---------------------------------------------------------
    if not trades:
        print("❌ No trades generated during test period.")
        return

    tdf = pd.DataFrame(trades)
    wins = tdf[tdf['pnl_pts'] > 0]
    losses = tdf[tdf['pnl_pts'] <= 0]
    win_rate = len(wins) / len(tdf) * 100.0
    total_pts = tdf['pnl_pts'].sum()
    total_pnl_rs = tdf['pnl_rs'].sum()
    gross_profit = wins['pnl_rs'].sum()
    gross_loss = abs(losses['pnl_rs'].sum())
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')
    
    # Drawdown calculation
    tdf['cum_pnl'] = tdf['pnl_rs'].cumsum()
    tdf['peak'] = tdf['cum_pnl'].cummax()
    tdf['drawdown'] = tdf['cum_pnl'] - tdf['peak']
    max_dd_rs = tdf['drawdown'].min()
    max_dd_pts = (tdf['pnl_pts'].cumsum() - tdf['pnl_pts'].cumsum().cummax()).min()

    print(f"📋 TRADE SUMMARY ({len(tdf)} Total Closed Trades):")
    print("-" * 110)
    print(f"{'#':<3} {'Type':<17} {'Entry Time':<17} {'Exit Time':<17} {'Short/Hedge':<15} {'Credit':<8} {'ExitVal':<8} {'Pts':<8} {'PnL (₹)':<10} {'Reason'}")
    print("-" * 110)
    for _, t in tdf.iterrows():
        print(f"{t['trade_id']:<3} {t['type']:<17} {str(t['entry_time'])[:16]:<17} {str(t['exit_time'])[:16]:<17} {t['short_strike']}/{t['hedge_strike']:<11} {t['entry_credit']:<8.1f} {t['exit_spread_val']:<8.1f} {t['pnl_pts']:<+8.1f} ₹{t['pnl_rs']:<+9.0f} {t['exit_reason']}")

    print("\n" + "=" * 110)
    print("🏆 KEY PERFORMANCE METRICS")
    print("=" * 110)
    print(f"• Total Trades:              {len(tdf)} trades (~{len(tdf)/36:.1f} trades/month)")
    print(f"• Winning Trades:            {len(wins)} ({win_rate:.1f}%)")
    print(f"• Losing Trades:             {len(losses)} ({100-win_rate:.1f}%)")
    print(f"• Profit Factor:             {profit_factor:.2f}")
    print(f"• Total Points Captured:     {total_pts:+.2f} points")
    print(f"• Total Net P&L (₹):         ₹{total_pnl_rs:,.2f} ({num_lots} lots / {num_lots*lot_size} qty)")
    print(f"• Average P&L per Trade:     ₹{tdf['pnl_rs'].mean():,.2f} ({tdf['pnl_pts'].mean():+.1f} pts)")
    print(f"• Average Trade Duration:    {tdf['days_held'].mean():.1f} days")
    print(f"• Maximum Drawdown:          ₹{max_dd_rs:,.2f} ({max_dd_pts:.1f} pts)")
    print("=" * 110)

    # Monthly Breakdown
    tdf['YearMonth'] = tdf['exit_time'].apply(lambda x: x.strftime('%Y-%m'))
    monthly = tdf.groupby('YearMonth').agg(
        trades=('pnl_pts', 'count'),
        pts=('pnl_pts', 'sum'),
        pnl_rs=('pnl_rs', 'sum'),
        win_rate=('pnl_pts', lambda x: (x > 0).mean() * 100)
    )
    print("\n📅 MONTHLY BREAKDOWN:")
    print("-" * 75)
    print(f"{'Month':<10} {'Trades':<8} {'Win Rate':<12} {'Points':<12} {'PnL (₹)':<15}")
    print("-" * 75)
    for ym, row in monthly.iterrows():
        print(f"{ym:<10} {int(row['trades']):<8} {row['win_rate']:<11.1f}% {row['pts']:<+11.1f} ₹{row['pnl_rs']:<+14,.2f}")
    print("-" * 75)

if __name__ == '__main__':
    # Nifty current lot size = 25 (post lot size change from 50/65/75/25)
    # We test with 2 lots = 50 quantity as requested by user
    run_backtest(lot_size=25, num_lots=2, iv=0.135)
