import urllib.request
import json
import datetime
import math

# Fetch 1 month 5m data from Yahoo Finance for ^NSEI (NIFTY 50)
url = 'https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=1mo&interval=5m'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())['chart']['result'][0]
except Exception as e:
    print(f"Error fetching data: {e}")
    exit(1)

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
        gain = diff if diff > 0 else 0.0
        loss = -diff if diff < 0 else 0.0
        avg_gain = (avg_gain * (period - 1) + gain) / period
        avg_loss = (avg_loss * (period - 1) + loss) / period
        if avg_loss == 0:
            rsi[i] = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi[i] = 100.0 - (100.0 / (1.0 + rs))
    return rsi

def calc_adx(highs, lows, closes, period=14):
    n = len(closes)
    if n < period * 2:
        return [float('nan')] * n
    tr = []
    plus_dm = []
    minus_dm = []
    for i in range(n):
        if i == 0:
            tr.append(highs[i] - lows[i])
            plus_dm.append(0.0)
            minus_dm.append(0.0)
        else:
            hl = highs[i] - lows[i]
            hc = abs(highs[i] - closes[i - 1])
            lc = abs(lows[i] - closes[i - 1])
            tr.append(max(hl, hc, lc))
            
            up_move = highs[i] - highs[i - 1]
            down_move = lows[i - 1] - lows[i]
            if up_move > down_move and up_move > 0:
                plus_dm.append(up_move)
            else:
                plus_dm.append(0.0)
            if down_move > up_move and down_move > 0:
                minus_dm.append(down_move)
            else:
                minus_dm.append(0.0)
                
    smoothed_tr = [float('nan')] * n
    smoothed_pdm = [float('nan')] * n
    smoothed_mdm = [float('nan')] * n
    dx = [float('nan')] * n
    adx = [float('nan')] * n
    
    smoothed_tr[period] = sum(tr[1:period + 1])
    smoothed_pdm[period] = sum(plus_dm[1:period + 1])
    smoothed_mdm[period] = sum(minus_dm[1:period + 1])
    
    for i in range(period + 1, n):
        smoothed_tr[i] = smoothed_tr[i - 1] - (smoothed_tr[i - 1] / period) + tr[i]
        smoothed_pdm[i] = smoothed_pdm[i - 1] - (smoothed_pdm[i - 1] / period) + plus_dm[i]
        smoothed_mdm[i] = smoothed_mdm[i - 1] - (smoothed_mdm[i - 1] / period) + minus_dm[i]
        
        pdi = (smoothed_pdm[i] / smoothed_tr[i]) * 100.0 if smoothed_tr[i] != 0 else 0
        mdi = (smoothed_mdm[i] / smoothed_tr[i]) * 100.0 if smoothed_tr[i] != 0 else 0
        sum_di = pdi + mdi
        diff_di = abs(pdi - mdi)
        dx[i] = (diff_di / sum_di) * 100.0 if sum_di != 0 else 0
        
    adx_start = period * 2
    if adx_start < n:
        valid_dx = [d for d in dx[period:adx_start] if not math.isnan(d)]
        if len(valid_dx) > 0:
            adx[adx_start - 1] = sum(valid_dx) / len(valid_dx)
            for i in range(adx_start, n):
                if not math.isnan(dx[i]):
                    adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period
    return adx

def resample_5m_to_15m(bars):
    resampled = []
    bucket_map = {}
    for b in bars:
        bucket_min = (b['dt'].minute // 15) * 15
        bucket_dt = b['dt'].replace(minute=bucket_min, second=0, microsecond=0)
        if bucket_dt not in bucket_map:
            bucket_map[bucket_dt] = []
        bucket_map[bucket_dt].append(b)
        
    for bdt in sorted(bucket_map.keys()):
        cluster = bucket_map[bdt]
        resampled.append({
            'dt': bdt,
            'open': cluster[0]['open'],
            'high': max(c['high'] for c in cluster),
            'low': min(c['low'] for c in cluster),
            'close': cluster[-1]['close'],
            'volume': sum(c['volume'] for c in cluster)
        })
    return resampled

def run_simulation(candles, hedge_enabled=False, stop_loss_pct=2.0, tp_pct=50.0, adx_threshold=20.0):
    lot_size = 65
    atm_entry_premium = 150.0
    atm_delta = 0.50
    atm_theta_per_hour = 1.0 # Seller gains theta
    
    # 2% OTM Hedge properties (e.g. 23500 PE when spot is 24000)
    hedge_entry_premium = 12.0 # 2% OTM weekly option premium
    hedge_delta = 0.10         # 2% OTM delta
    hedge_theta_per_hour = -0.20 # Buyer loses minor theta
    
    net_credit = atm_entry_premium - (hedge_entry_premium if hedge_enabled else 0.0)
    
    candles_by_date = {}
    for c in candles:
        d = c['date']
        if d not in candles_by_date:
            candles_by_date[d] = []
        candles_by_date[d].append(c)
        
    sorted_dates = sorted(candles_by_date.keys())
    trades = []
    trade_id = 1
    
    for day_idx, d in enumerate(sorted_dates):
        day_bars = sorted(candles_by_date[d], key=lambda x: x['dt'])
        start_hist = max(0, day_idx - 5)
        warm_history = []
        for p in range(start_hist, day_idx):
            warm_history.extend(candles_by_date[sorted_dates[p]])
            
        trade_today = False
        open_pos = None
        current_day_bars = []
        
        for bar in day_bars:
            current_day_bars.append(bar)
            t = bar['time']
            
            # EOD square-off at 15:05
            if t > datetime.time(15, 0) and open_pos is not None:
                exit_spot = bar['close']
                hold_hours = (bar['dt'] - open_pos['entry_time']).total_seconds() / 3600.0
                spot_diff = (exit_spot - open_pos['entry_spot']) if open_pos['is_bullish'] else (open_pos['entry_spot'] - exit_spot)
                
                atm_pts = (spot_diff * atm_delta) + (hold_hours * atm_theta_per_hour)
                if hedge_enabled:
                    # Hedge is long OTM option. If spot moves against ATM position (spot_diff < 0), hedge gains.
                    hedge_pts = (-spot_diff * hedge_delta) + (hold_hours * hedge_theta_per_hour)
                    net_pts = round(atm_pts + hedge_pts, 2)
                else:
                    net_pts = round(atm_pts, 2)
                    
                pnl = round(net_pts * lot_size, 2)
                trades.append({
                    'id': trade_id,
                    'date': str(d),
                    'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                    'exit_time': bar['dt'].strftime('%H:%M'),
                    'type': 'BULL_PUT_SPREAD (2% OTM)' if (open_pos['is_bullish'] and hedge_enabled) else ('BEAR_CALL_SPREAD (2% OTM)' if hedge_enabled else ('SELL_PE' if open_pos['is_bullish'] else 'SELL_CE')),
                    'entry_spot': open_pos['entry_spot'],
                    'exit_spot': exit_spot,
                    'net_points': net_pts,
                    'pnl': pnl,
                    'reason': 'EOD_SQUARE_OFF (15:05)',
                    'is_win': pnl > 0
                })
                trade_id += 1
                open_pos = None
                continue
                
            if t < datetime.time(9, 45):
                continue
                
            all_bars = warm_history + current_day_bars
            if len(all_bars) < 30:
                continue
                
            res_15m = resample_5m_to_15m(all_bars)
            if len(res_15m) < 20:
                continue
                
            closes_5m = [b['close'] for b in all_bars]
            highs_15m = [b['high'] for b in res_15m]
            lows_15m = [b['low'] for b in res_15m]
            closes_15m = [b['close'] for b in res_15m]
            
            rsi_5m = calc_rsi(closes_5m, 14)
            rsi_15m = calc_rsi(closes_15m, 14)
            adx_15m = calc_adx(highs_15m, lows_15m, closes_15m, 14)
            
            if len(rsi_5m) < 2 or len(rsi_15m) < 2:
                continue
                
            r5_curr = rsi_5m[-1]
            r5_prev = rsi_5m[-2]
            r15_curr = rsi_15m[-1]
            r15_prev = rsi_15m[-2]
            adx_curr = adx_15m[-1] if len(adx_15m) > 0 else float('nan')
            
            if math.isnan(r5_curr) or math.isnan(r5_prev) or math.isnan(r15_curr) or math.isnan(r15_prev):
                continue
                
            # Manage Open Position
            if open_pos is not None:
                exit_spot = bar['close']
                hold_hours = (bar['dt'] - open_pos['entry_time']).total_seconds() / 3600.0
                spot_diff = (exit_spot - open_pos['entry_spot']) if open_pos['is_bullish'] else (open_pos['entry_spot'] - exit_spot)
                
                atm_pts = (spot_diff * atm_delta) + (hold_hours * atm_theta_per_hour)
                if hedge_enabled:
                    hedge_pts = (-spot_diff * hedge_delta) + (hold_hours * hedge_theta_per_hour)
                    net_pts = round(atm_pts + hedge_pts, 2)
                else:
                    net_pts = round(atm_pts, 2)
                    
                sl_threshold_pts = -(net_credit * (stop_loss_pct / 100.0))
                tp_threshold_pts = net_credit * (tp_pct / 100.0)
                
                is_sl_hit = stop_loss_pct > 0 and net_pts <= sl_threshold_pts
                is_tp_hit = tp_pct > 0 and net_pts >= tp_threshold_pts
                is_reversal = (open_pos['is_bullish'] and r5_curr < r15_curr) or (not open_pos['is_bullish'] and r5_curr > r15_curr)
                
                if is_sl_hit or is_tp_hit or is_reversal:
                    if is_sl_hit:
                        reason = f"HARD_SL_HIT ({net_pts} pts)"
                    elif is_tp_hit:
                        reason = f"TARGET_PROFIT_HIT ({net_pts} pts)"
                    else:
                        reason = "RSI_REVERSAL_BEARISH" if open_pos['is_bullish'] else "RSI_REVERSAL_BULLISH"
                        
                    pnl = round(net_pts * lot_size, 2)
                    trades.append({
                        'id': trade_id,
                        'date': str(d),
                        'entry_time': open_pos['entry_time'].strftime('%H:%M'),
                        'exit_time': bar['dt'].strftime('%H:%M'),
                        'type': 'BULL_PUT_SPREAD (2% OTM)' if (open_pos['is_bullish'] and hedge_enabled) else ('BEAR_CALL_SPREAD (2% OTM)' if hedge_enabled else ('SELL_PE' if open_pos['is_bullish'] else 'SELL_CE')),
                        'entry_spot': open_pos['entry_spot'],
                        'exit_spot': exit_spot,
                        'net_points': net_pts,
                        'pnl': pnl,
                        'reason': reason,
                        'is_win': pnl > 0
                    })
                    trade_id += 1
                    open_pos = None
                continue
                
            # Entry Evaluation
            if not trade_today and t < datetime.time(15, 0):
                bullish_cross = (r5_prev <= r15_prev) and (r5_curr > r15_curr)
                bearish_cross = (r5_prev >= r15_prev) and (r5_curr < r15_curr)
                
                if not bullish_cross and not bearish_cross:
                    continue
                    
                if not math.isnan(adx_curr) and adx_curr < adx_threshold:
                    continue
                    
                open_pos = {
                    'entry_time': bar['dt'],
                    'entry_spot': bar['close'],
                    'is_bullish': bullish_cross
                }
                trade_today = True

    return trades

if __name__ == '__main__':
    unhedged_trades = run_simulation(candles, hedge_enabled=False, stop_loss_pct=2.0, tp_pct=50.0, adx_threshold=20.0)
    hedged_trades = run_simulation(candles, hedge_enabled=True, stop_loss_pct=2.0, tp_pct=50.0, adx_threshold=20.0)
    
    print("=" * 75)
    print("      NIFTY 5m vs 15m RSI CROSSOVER: UNHEDGED vs 2% OTM HEDGED SPREAD")
    print("=" * 75)
    
    for label, tr in [("UNHEDGED OPTION SELLING (Naked ATM)", unhedged_trades),
                      ("2% OTM HEDGED CREDIT SPREAD (ATM Sell + 2% OTM Hedge Buy)", hedged_trades)]:
        tot = len(tr)
        wins = [t for t in tr if t['is_win']]
        losses = [t for t in tr if not t['is_win']]
        wr = (len(wins) / tot * 100) if tot > 0 else 0
        gp = sum(t['pnl'] for t in wins)
        gl = abs(sum(t['pnl'] for t in losses))
        net = gp - gl
        pf = (gp / gl) if gl > 0 else 99.0
        
        print(f"\nMODE: {label}")
        print(f"   * Total Trades: {tot} ({len(wins)} Wins / {len(losses)} Losses)")
        print(f"   * Win Rate: {wr:.1f}%")
        print(f"   * Gross Profit: +Rs. {gp:,.2f}")
        print(f"   * Gross Loss:   -Rs. {gl:,.2f}")
        print(f"   * Net Realized P&L: +Rs. {net:,.2f}")
        print(f"   * Profit Factor: {pf:.2f}")
    
    print("\n" + "=" * 75)
    print("HEDGED SPREAD TRADE LOG (Sample):")
    print(f"{'ID':<3} | {'Date':<10} | {'Type':<26} | {'Entry':<5} | {'Exit':<5} | {'Net Pts':<8} | {'P&L (Rs.)':<10} | {'Reason'}")
    print("-" * 75)
    for t in hedged_trades:
        sign = "+" if t['pnl'] > 0 else ""
        print(f"{t['id']:<3} | {t['date']:<10} | {t['type']:<26} | {t['entry_time']:<5} | {t['exit_time']:<5} | {t['net_points']:<8.2f} | {sign + str(t['pnl']):<10} | {t['reason']}")
    print("=" * 75)
