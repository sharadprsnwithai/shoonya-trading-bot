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

lot_size = 65
lots = 1
qty = lots * lot_size
delta = 0.50
base_premium = 150.0
sl_pct = 0.20
tp_pct = 0.50
max_sl_pts = 20.0
adx_threshold = 20.0

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
            opt_type = open_pos['type']
            spot_diff = (exit_spot - entry_spot) if opt_type == 'CE' else (entry_spot - exit_spot)
            pts = spot_diff * delta
            pnl = pts * qty
            trades.append({
                'id': len(trades) + 1, 'date': str(day), 'type': opt_type,
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
            
        # 1. Manage open position: Check Hard SL, Target Profit, or RSI Reversal
        if open_pos is not None:
            exit_spot = c['close']
            entry_spot = open_pos['entry_spot']
            opt_type = open_pos['type']
            spot_diff = (exit_spot - entry_spot) if opt_type == 'CE' else (entry_spot - exit_spot)
            pts = spot_diff * delta
            
            # Check Hard SL Hit or Target Profit Hit
            sl_limit_pts = -min(base_premium * sl_pct, max_sl_pts)
            tp_limit_pts = base_premium * tp_pct
            
            is_sl_hit = pts <= sl_limit_pts
            is_tp_hit = pts >= tp_limit_pts
            is_reversal = (opt_type == 'CE' and rsi5_curr < rsi15_curr) or (opt_type == 'PE' and rsi5_curr > rsi15_curr)
            
            if is_sl_hit or is_tp_hit or is_reversal:
                if is_sl_hit:
                    reason = f'HARD_SL_HIT ({pts:.1f} pts)'
                elif is_tp_hit:
                    reason = f'TARGET_PROFIT_HIT ({pts:.1f} pts)'
                else:
                    reason = f'RSI_REVERSAL_{"BEARISH" if opt_type=="CE" else "BULLISH"}'
                
                pnl = pts * qty
                trades.append({
                    'id': len(trades) + 1, 'date': str(day), 'type': opt_type,
                    'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                    'entry_spot': entry_spot, 'exit_time': c['time'].strftime('%H:%M'),
                    'exit_spot': exit_spot, 'spot_diff': spot_diff, 'pnl_pts': pts,
                    'pnl_amount': pnl, 'reason': reason
                })
                open_pos = None
            continue
            
        # 2. Check Entry
        if not trade_executed_today and t <= datetime.time(15, 0):
            bullish = (rsi5_prev <= rsi15_prev) and (rsi5_curr > rsi15_curr)
            bearish = (rsi5_prev >= rsi15_prev) and (rsi5_curr < rsi15_curr)
            
            # ADX filter check
            adx_ok = True
            if adx_threshold > 0:
                adx_series = calc_adx([b['high'] for b in bars_15m], [b['low'] for b in bars_15m], [b['close'] for b in bars_15m], 14)
                adx_val = adx_series[-1]
                if adx_val == adx_val and adx_val < adx_threshold:
                    adx_ok = False
            
            if bullish and adx_ok:
                open_pos = {'type': 'CE', 'entry_time': c['time'], 'entry_spot': c['close']}
                trade_executed_today = True
            elif bearish and adx_ok:
                open_pos = {'type': 'PE', 'entry_time': c['time'], 'entry_spot': c['close']}
                trade_executed_today = True

    if open_pos is not None:
        c = day_candles[-1]
        exit_spot = c['close']
        entry_spot = open_pos['entry_spot']
        opt_type = open_pos['type']
        spot_diff = (exit_spot - entry_spot) if opt_type == 'CE' else (entry_spot - exit_spot)
        pts = spot_diff * delta
        pnl = pts * qty
        trades.append({
            'id': len(trades) + 1, 'date': str(day), 'type': opt_type,
            'entry_time': open_pos['entry_time'].strftime('%H:%M'),
            'entry_spot': entry_spot, 'exit_time': c['time'].strftime('%H:%M'),
            'exit_spot': exit_spot, 'spot_diff': spot_diff, 'pnl_pts': pts,
            'pnl_amount': pnl, 'reason': 'EOD_CLOSE'
        })
        open_pos = None

print("\n" + "=" * 115)
print(f"{'#':<3} | {'Date':<10} | {'Type':<4} | {'Entry (IST)':<11} | {'Exit (IST)':<10} | {'Spot Entry':<10} | {'Spot Exit':<10} | {'Spot Pts':<10} | {'Opt Pts':<9} | {'Net P&L (Rs)':<12} | {'Exit Reason'}")
print("=" * 115)

gross_profit = 0
gross_loss = 0
total_pts = 0
wins = 0
losses = 0

cumulative_pnl = []
cum = 0

for tr in trades:
    pnl = tr['pnl_amount']
    pts = tr['pnl_pts']
    cum += pnl
    cumulative_pnl.append(cum)
    total_pts += pts
    if pnl > 0:
        wins += 1
        gross_profit += pnl
    else:
        losses += 1
        gross_loss += abs(pnl)
        
    print(f"{tr['id']:<3} | {tr['date']:<10} | {tr['type']:<4} | {tr['entry_time']:<11} | {tr['exit_time']:<10} | Rs {tr['entry_spot']:<9.2f} | Rs {tr['exit_spot']:<9.2f} | {tr['spot_diff']:+8.2f}   | {pts:+7.2f}   | Rs {pnl:+9.2f} | {tr['reason']}")

print("=" * 115)

net_pnl = gross_profit - gross_loss
win_rate = (wins / len(trades) * 100) if trades else 0.0
profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float('inf')

peak = 0
max_dd = 0
for val in cumulative_pnl:
    if val > peak:
        peak = val
    dd = peak - val
    if dd > max_dd:
        max_dd = dd

print("\n--- OPTIMIZED BACKTEST SUMMARY (SL + TP + ADX Filter) ---")
print(f"  Period Analyzed:             {sorted_days[0]} to {sorted_days[-1]} ({len(sorted_days)} trading days)")
print(f"  Rules:                       1 Trade/Day | 15m ADX >= 20 | Hard SL: 20 pts (20%) | Target: 75 pts (50%)")
print(f"  Total Trades:                {len(trades)}")
print(f"  Winning Trades:              {wins} ({win_rate:.1f}%)")
print(f"  Losing Trades:               {losses} ({100 - win_rate:.1f}%)")
print(f"  Total Option Points:         {total_pts:+.2f} pts")
print(f"  Gross Profit:                Rs. {gross_profit:,.2f}")
print(f"  Gross Loss:                  Rs. {gross_loss:,.2f}")
print(f"  Net Realized P&L:            Rs. {net_pnl:+,.2f}")
print(f"  Profit Factor:               {profit_factor:.2f}")
print(f"  Max Peak-to-Trough Drawdown: Rs. {max_dd:,.2f}")
