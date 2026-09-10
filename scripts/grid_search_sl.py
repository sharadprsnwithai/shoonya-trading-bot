import urllib.request
import json
import datetime

# Fetch 1 month 5m data from Yahoo Finance for ^NSEI (NIFTY 50)
url = 'https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=1mo&interval=5m'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode())['chart']['result'][0]

timestamps = data['timestamp']
quotes = data['indicators']['quote'][0]
opens = quotes['open']
highs = quotes['high']
lows = quotes['low']
closes = quotes['close']
volumes = quotes['volume']

candles = []
for i in range(len(timestamps)):
    if opens[i] is None or closes[i] is None:
        continue
    t = datetime.datetime.fromtimestamp(timestamps[i], datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
    candles.append({
        'dt': t,
        'time': t.time(),
        'date': t.date(),
        'open': float(opens[i]),
        'high': float(highs[i]),
        'low': float(lows[i]),
        'close': float(closes[i]),
        'volume': int(volumes[i]) if volumes[i] is not None else 0
    })

def calc_rsi(prices, period=14):
    n = len(prices)
    if n < period + 1:
        return [float('nan')] * n
    rsi = [float('nan')] * n
    gains = []
    losses = []
    for i in range(1, period + 1):
        diff = prices[i] - prices[i - 1]
        if diff >= 0:
            gains.append(diff)
            losses.append(0.0)
        else:
            gains.append(0.0)
            losses.append(-diff)
            
    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period
    if avg_loss == 0:
        rsi[period] = 100.0
    else:
        rs = avg_gain / avg_loss
        rsi[period] = 100.0 - (100.0 / (1.0 + rs))
        
    for i in range(period + 1, n):
        diff = prices[i] - prices[i - 1]
        g = diff if diff > 0 else 0.0
        l = -diff if diff < 0 else 0.0
        avg_gain = (avg_gain * (period - 1) + g) / period
        avg_loss = (avg_loss * (period - 1) + l) / period
        if avg_loss == 0:
            rsi[i] = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi[i] = 100.0 - (100.0 / (1.0 + rs))
    return rsi

def calc_adx(highs, lows, closes, period=14):
    n = len(closes)
    if n < 2 * period + 1:
        return [float('nan')] * n
    tr = [0.0] * n
    plus_dm = [0.0] * n
    minus_dm = [0.0] * n
    
    for i in range(1, n):
        h = highs[i]
        l = lows[i]
        prev_c = closes[i - 1]
        prev_h = highs[i - 1]
        prev_l = lows[i - 1]
        tr[i] = max(h - l, abs(h - prev_c), abs(l - prev_c))
        up = h - prev_h
        down = prev_l - l
        plus_dm[i] = up if (up > down and up > 0) else 0.0
        minus_dm[i] = down if (down > up and down > 0) else 0.0
        
    smooth_tr = sum(tr[1:period+1])
    smooth_plus_dm = sum(plus_dm[1:period+1])
    smooth_minus_dm = sum(minus_dm[1:period+1])
    
    dx = [0.0] * n
    for i in range(period, n):
        if i > period:
            smooth_tr = smooth_tr - (smooth_tr / period) + tr[i]
            smooth_plus_dm = smooth_plus_dm - (smooth_plus_dm / period) + plus_dm[i]
            smooth_minus_dm = smooth_minus_dm - (smooth_minus_dm / period) + minus_dm[i]
        plus_di = (smooth_plus_dm / smooth_tr * 100.0) if smooth_tr > 0 else 0.0
        minus_di = (smooth_minus_dm / smooth_tr * 100.0) if smooth_tr > 0 else 0.0
        di_sum = plus_di + minus_di
        dx[i] = (abs(plus_di - minus_di) / di_sum * 100.0) if di_sum > 0 else 0.0
        
    adx = [float('nan')] * n
    adx_start = 2 * period
    if n > adx_start:
        adx[adx_start] = sum(dx[period+1:adx_start+1]) / period
        for i in range(adx_start + 1, n):
            adx[i] = (adx[i-1] * (period - 1) + dx[i]) / period
    return adx

def resample_15m(c_list):
    grouped = {}
    for c in c_list:
        epoch_mins = int(c['dt'].timestamp()) // 60
        slot_key = (epoch_mins // 15) * 15
        if slot_key not in grouped:
            grouped[slot_key] = []
        grouped[slot_key].append(c)
    res = []
    for k in sorted(grouped.keys()):
        bars = grouped[k]
        res.append({
            'dt': bars[-1]['dt'],
            'open': bars[0]['open'],
            'high': max(b['high'] for b in bars),
            'low': min(b['low'] for b in bars),
            'close': bars[-1]['close'],
            'volume': sum(b['volume'] for b in bars)
        })
    return res

days = {}
for c in candles:
    d = c['date']
    if d not in days:
        days[d] = []
    days[d].append(c)
sorted_days = sorted(days.keys())

def run_simulation(sl_pct=0.02, tp_pct=0.50, adx_threshold=20.0):
    lot_size = 65
    lots = 1
    qty = lots * lot_size
    delta = 0.50
    base_premium = 150.0
    theta_per_hour = 1.0
    
    trades = []
    for day_idx, day in enumerate(sorted_days):
        day_candles = days[day]
        start_hist_idx = max(0, day_idx - 5)
        hist_candles = []
        for past_idx in range(start_hist_idx, day_idx):
            hist_candles.extend(days[sorted_days[past_idx]])
        
        trade_executed_today = False
        open_pos = None
        current_day_candles = []
        
        for c in day_candles:
            current_day_candles.append(c)
            all_bars = hist_candles + current_day_candles
            t = c['time']
            
            # EOD square off at 15:05
            if t >= datetime.time(15, 5) and open_pos is not None:
                exit_spot = c['close']
                entry_spot = open_pos['entry_spot']
                pos_type = open_pos['type']
                is_bullish = ("PE" in pos_type) # SELL PE is bullish
                spot_diff = (exit_spot - entry_spot) if is_bullish else (entry_spot - exit_spot)
                
                entry_dt = open_pos['entry_dt']
                exit_dt = c['dt']
                hold_hours = (exit_dt - entry_dt).total_seconds() / 3600.0
                theta_pts = hold_hours * theta_per_hour
                
                pts = (spot_diff * delta) + theta_pts
                pnl = pts * qty
                trades.append({
                    'id': len(trades) + 1, 'date': str(day), 'type': pos_type,
                    'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                    'entry_spot': entry_spot, 'exit_time': c['time'].strftime('%H:%M'),
                    'exit_spot': exit_spot, 'spot_diff': spot_diff, 'pnl_pts': pts,
                    'pnl_amount': pnl, 'reason': 'EOD_SQUARE_OFF (15:05)'
                })
                open_pos = None
                continue

            if t < datetime.time(9, 45):
                continue
                
            bars_15m = resample_15m(all_bars)
            if len(bars_15m) < 19 or len(all_bars) < 25:
                continue
                
            rsi5_series = calc_rsi([b['close'] for b in all_bars], 14)
            rsi15_series = calc_rsi([b['close'] for b in bars_15m], 14)
            
            rsi5_curr = rsi5_series[-1]
            rsi5_prev = rsi5_series[-2]
            rsi15_curr = rsi15_series[-1]
            rsi15_prev = rsi15_series[-2]
            
            if (rsi5_curr != rsi5_curr or rsi5_prev != rsi5_prev or 
                rsi15_curr != rsi15_curr or rsi15_prev != rsi15_prev):
                continue
                
            # 1. Manage open position
            if open_pos is not None:
                # Check intrabar extreme (high/low) to see if SL was touched
                pos_type = open_pos['type']
                is_bullish = ("PE" in pos_type) # SELL PE is bullish, hurt if spot drops (bar low)
                
                worst_spot = c['low'] if is_bullish else c['high']
                worst_diff = (worst_spot - open_pos['entry_spot']) if is_bullish else (open_pos['entry_spot'] - worst_spot)
                
                entry_dt = open_pos['entry_dt']
                exit_dt = c['dt']
                hold_hours = (exit_dt - entry_dt).total_seconds() / 3600.0
                theta_pts = hold_hours * theta_per_hour
                
                worst_pts = (worst_diff * delta) + theta_pts
                close_spot = c['close']
                close_diff = (close_spot - open_pos['entry_spot']) if is_bullish else (open_pos['entry_spot'] - close_spot)
                close_pts = (close_diff * delta) + theta_pts
                
                sl_limit_pts = -(base_premium * sl_pct)
                tp_limit_pts = base_premium * tp_pct
                
                is_sl_hit = worst_pts <= sl_limit_pts
                is_tp_hit = close_pts >= tp_limit_pts
                is_reversal = (rsi5_curr < rsi15_curr) if is_bullish else (rsi5_curr > rsi15_curr)
                
                if is_sl_hit or is_tp_hit or is_reversal:
                    if is_sl_hit:
                        reason = f'HARD_SL_HIT ({sl_limit_pts:.1f} pts)'
                        pts = sl_limit_pts
                        exit_spot = worst_spot
                        spot_diff = worst_diff
                    elif is_tp_hit:
                        reason = f'TARGET_PROFIT_HIT ({tp_limit_pts:.1f} pts)'
                        pts = tp_limit_pts
                        exit_spot = close_spot
                        spot_diff = close_diff
                    else:
                        reason = f'RSI_REVERSAL_{"BEARISH" if is_bullish else "BULLISH"}'
                        pts = close_pts
                        exit_spot = close_spot
                        spot_diff = close_diff
                    
                    pnl = pts * qty
                    trades.append({
                        'id': len(trades) + 1, 'date': str(day), 'type': pos_type,
                        'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                        'entry_spot': open_pos['entry_spot'], 'exit_time': c['time'].strftime('%H:%M'),
                        'exit_spot': exit_spot, 'spot_diff': spot_diff, 'pnl_pts': pts,
                        'pnl_amount': pnl, 'reason': reason
                    })
                    open_pos = None
                continue
                
            # 2. Check Entry
            if not trade_executed_today and t <= datetime.time(15, 0):
                bullish = (rsi5_prev <= rsi15_prev) and (rsi5_curr > rsi15_curr)
                bearish = (rsi5_prev >= rsi15_prev) and (rsi5_curr < rsi15_curr)
                
                adx_ok = True
                if adx_threshold > 0:
                    adx_series = calc_adx([b['high'] for b in bars_15m], [b['low'] for b in bars_15m], [b['close'] for b in bars_15m], 14)
                    adx_val = adx_series[-1]
                    if adx_val == adx_val and adx_val < adx_threshold:
                        adx_ok = False
                
                if bullish and adx_ok:
                    open_pos = {'type': "SELL_PE", 'entry_time': c['time'], 'entry_dt': c['dt'], 'entry_spot': c['close']}
                    trade_executed_today = True
                elif bearish and adx_ok:
                    open_pos = {'type': "SELL_CE", 'entry_time': c['time'], 'entry_dt': c['dt'], 'entry_spot': c['close']}
                    trade_executed_today = True

        if open_pos is not None:
            c = day_candles[-1]
            exit_spot = c['close']
            entry_spot = open_pos['entry_spot']
            pos_type = open_pos['type']
            is_bullish = ("PE" in pos_type)
            spot_diff = (exit_spot - entry_spot) if is_bullish else (entry_spot - exit_spot)
            hold_hours = (c['dt'] - open_pos['entry_dt']).total_seconds() / 3600.0
            theta_pts = hold_hours * theta_per_hour
            pts = (spot_diff * delta) + theta_pts
            pnl = pts * qty
            trades.append({
                'id': len(trades) + 1, 'date': str(day), 'type': pos_type,
                'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                'entry_spot': entry_spot, 'exit_time': c['time'].strftime('%H:%M'),
                'exit_spot': exit_spot, 'spot_diff': spot_diff, 'pnl_pts': pts,
                'pnl_amount': pnl, 'reason': 'EOD_CLOSE'
            })
            open_pos = None

    wins = sum(1 for t in trades if t['pnl_amount'] > 0)
    losses = sum(1 for t in trades if t['pnl_amount'] <= 0)
    gp = sum(t['pnl_amount'] for t in trades if t['pnl_amount'] > 0)
    gl = sum(abs(t['pnl_amount']) for t in trades if t['pnl_amount'] <= 0)
    net_pnl = gp - gl
    wr = (wins / len(trades) * 100) if trades else 0.0
    pf = (gp / gl) if gl > 0 else 99.0
    
    cum = 0
    peak = 0
    max_dd = 0
    for t in trades:
        cum += t['pnl_amount']
        if cum > peak: peak = cum
        dd = peak - cum
        if dd > max_dd: max_dd = dd
        
    return {
        'sl_pct': sl_pct, 'trades_count': len(trades), 'wins': wins, 'losses': losses, 'win_rate': wr,
        'gross_profit': gp, 'gross_loss': gl, 'net_pnl': net_pnl, 'profit_factor': pf,
        'max_dd': max_dd, 'trades': trades
    }

sl_levels = [0.01, 0.02, 0.03, 0.04, 0.05, 0.07, 0.10, 0.15, 0.20]
results = [run_simulation(sl) for sl in sl_levels]

print("\n" + "=" * 95)
print("             GRID SEARCH: STOP-LOSS % FOR NIFTY OPTION SELLING (LAST 1 MONTH)")
print("=" * 95)
print(f"{'SL %':<8} | {'Trades':<7} | {'Win Rate':<10} | {'Gross Profit':<14} | {'Gross Loss':<12} | {'Net Realized PnL':<18} | {'Profit Factor':<14} | {'Max DD'}")
print("-" * 95)
for r in results:
    sl_str = f"{r['sl_pct']*100:.1f}%"
    wr_str = f"{r['win_rate']:.1f}% ({r['wins']}W/{r['losses']}L)"
    print(f"{sl_str:<8} | {r['trades_count']:<7} | {wr_str:<10} | Rs {r['gross_profit']:<11,.2f} | Rs {r['gross_loss']:<9,.2f} | Rs {r['net_pnl']:<15,.2f} | {r['profit_factor']:<14.2f} | Rs {r['max_dd']:,.2f}")
print("=" * 95)

# Detailed trade log for 2% SL
res2 = run_simulation(0.02)
print("\n=== TRADE-BY-TRADE LOG FOR 2.0% STOP-LOSS ===")
print(f"{'#':<3} | {'Date':<10} | {'Action':<10} | {'Spot Entry':<10} | {'Spot Exit':<10} | {'P&L (Rs)':<10} | {'Exit Reason'}")
print("-" * 95)
for t in res2['trades']:
    print(f"{t['id']:<3} | {t['date']:<10} | {t['type']:<10} | Rs {t['entry_spot']:<7.2f} | Rs {t['exit_spot']:<7.2f} | Rs {t['pnl_amount']:+7.2f} | {t['reason']}")
