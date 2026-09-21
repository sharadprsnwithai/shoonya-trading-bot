#!/usr/bin/env python3
"""
Backtest for the "EMA-on-RSI" (RSI crosses its own EMA) strategy.

Video strategy being modelled (5-min candles, NIFTY 50):
  Indicators : RSI(n) on close  +  EMA(p) applied to the RSI *values* (the black line).
  Regime     : RSI ran into overbought (> OB)   -> only PUT side (fade overbought).
               RSI ran into oversold  (< OS)    -> only CALL side (fade oversold).
               RSI + EMA stuck mid-range         -> no trade (sideways filter).
  Signal     : RSI crosses BELOW its own EMA (after touching > OB)  -> BUY PUT.
               RSI crosses ABOVE its own EMA (after touching < OS)   -> BUY CALL.
  Entry      : On the bar AFTER the signal candle, price breaks the signal candle's
               high (call) / low (put).
  Stop loss  : signal candle low (call) / high (put).
  Target     : fixed R:R = RR (default 5.0) measured on the INDEX (spot).
  Holding    : if target not hit same day, carry position to next day(s), follow spot.
  Discipline : max 1 entry/day, no stale-signal carry-over, SL honoured (no averaging).

The video gives NO EMA period on RSI and NO hard OS threshold -> both are CLI knobs so we
can sweep. RSI-EMA period and the extremum thresholds are the two things the video hides.

P&L is modelled two ways for the same trades:
  SPOT  : raw index points of the move, and realised R multiples.
  OPTION: delta * spot_diff * qty  (ATM first-order approximation, same as legacy scripts).
          delta and lot weeks decay crudely via --daily-delta-decay (0 for long-dated).

Usage examples:
  python scripts/rsi_ema_of_rsi_backtest.py                    # defaults, 3 months
  python scripts/rsi_ema_of_rsi_backtest.py --range 6mo        # as much 5m as Yahoo gives
  python scripts/rsi_ema_of_rsi_backtest.py --ema 9 --rsi 14 --rr 5 --ob 70 --os 30
  python scripts/rsi_ema_of_rsi_backtest.py --sweep            # sweep EMA period 7..21
"""

import argparse
import datetime
import json
import sys
import urllib.request

import numpy as np

IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))


# --------------------------------------------------------------------------- #
# Data fetch (Yahoo Finance 5m OHLC, same approach as run_rsi_backtest.py)     #
# --------------------------------------------------------------------------- #
def fetch_nifty(range_str: str):
    url = (
        "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI"
        f"?range={range_str}&interval=5m"
    )
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode())["chart"]["result"][0]

    ts = data["timestamp"]
    q = data["indicators"]["quote"][0]
    candles = []
    for i in range(len(ts)):
        if q["open"][i] is None or q["close"][i] is None:
            continue
        dt = datetime.datetime.fromtimestamp(ts[i], IST)
        candles.append(
            {
                "dt": dt,
                "date": dt.date(),
                "time": dt.time(),
                "epoch": ts[i],
                "open": float(q["open"][i]),
                "high": float(q["high"][i]),
                "low": float(q["low"][i]),
                "close": float(q["close"][i]),
            }
        )
    return candles


# --------------------------------------------------------------------------- #
# Indicators                                                                  #
# --------------------------------------------------------------------------- #
def rsi_wilder(closes, period):
    """Wilder-smoothed RSI(period) computed over the whole series at once."""
    n = len(closes)
    rsi = np.full(n, np.nan)
    if n < period + 1:
        return rsi
    deltas = np.diff(closes)
    gains = np.where(deltas > 0, deltas, 0.0)
    losses = np.where(deltas < 0, -deltas, 0.0)
    avg_gain = float(gains[:period].sum() / period)
    avg_loss = float(losses[:period].sum() / period)
    for i in range(period, n):
        g = float(gains[i - 1])
        l = float(losses[i - 1])
        avg_gain = (avg_gain * (period - 1) + g) / period
        avg_loss = (avg_loss * (period - 1) + l) / period
        if avg_loss == 0:
            rsi[i] = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi[i] = 100.0 - (100.0 / (1.0 + rs))
    return rsi


def ema_series(values, period):
    """Classic EMA over an arbitrary series (here: RSI values). Skips NaN warm-up."""
    n = len(values)
    out = np.full(n, np.nan)
    if n < period:
        return out
    alpha = 2.0 / (period + 1.0)
    valid = [i for i in range(n) if not np.isnan(values[i])]
    if len(valid) < period:
        return out
    # seed with SMA of the first `period` VALID values
    seed_idx = valid[period - 1]
    seed = np.nanmean([values[i] for i in valid[:period]])
    out[seed_idx] = seed
    prev = seed
    for i in range(seed_idx + 1, n):
        if np.isnan(values[i]):
            continue
        prev = values[i] * alpha + prev * (1 - alpha)
        out[i] = prev
    return out


# --------------------------------------------------------------------------- #
# Single-strategy backtest                                                    #
# --------------------------------------------------------------------------- #
def run(candles, rsi_period, ema_period, overbought, oversold, lookback, rr,
        delta, lot_size, lots, max_hold_days, delta_decay, show):
    outs = ["5m", "15m", "30m", "1h", "4h", "1D", "1W"]
    tg = "??"
    for o in outs:
        try:
            _c = candles
        except Exception:
            pass
    tt = candles[-1]["dt"]
    tg = f"{tt:%Y-%m-%d}"

    closes = np.array([c["close"] for c in candles])
    rsi = rsi_wilder(closes, rsi_period)
    rsi_ema = ema_series(rsi, ema_period)

    # rolling max/min of RSI over a trailing window, used for the extremum filter
    rsi_roll_max = np.full(len(rsi), np.nan)
    rsi_roll_min = np.full(len(rsi), np.nan)
    for i in range(len(rsi)):
        lo = max(0, i - lookback + 1)
        seg = rsi[lo : i + 1]
        seg = seg[~np.isnan(seg)]
        if len(seg):
            rsi_roll_max[i] = seg.max()
            rsi_roll_min[i] = seg.min()

    # group candle indices by trading day for 1-trade/day + next-day carry
    days = {}
    order = []
    for i, c in enumerate(candles):
        d = c["date"]
        if d not in days:
            days[d] = []
            order.append(d)
        days[d].append(i)

    trades = []
    pos = None          # active position
    pending = None      # signal awaiting price-break entry
    last_entry_day = None

    for i, c in enumerate(candles):
        day = c["date"]
        # ------------ manage active position (SL / target first, then carry) ------
        if pos is not None:
            if pos["type"] == "CE":
                sl = pos["sl"]
                tgt = pos["target"]
                if c["low"] <= sl:
                    fill = min(sl, c["open"])
                    trades.append(_close_trade(pos, "SL", day, c["time"], fill))
                    pos = None
                elif c["high"] >= tgt:
                    fill = max(tgt, c["open"])
                    trades.append(_close_trade(pos, "TARGET", day, c["time"], fill))
                    pos = None
                else:
                    pos["hold_bars"] += 1
            else:  # PE
                sl = pos["sl"]
                tgt = pos["target"]
                if c["high"] >= sl:
                    fill = max(sl, c["open"])
                    trades.append(_close_trade(pos, "SL", day, c["time"], fill))
                    pos = None
                elif c["low"] <= tgt:
                    fill = min(tgt, c["open"])
                    trades.append(_close_trade(pos, "TARGET", day, c["time"], fill))
                    pos = None
                else:
                    pos["hold_bars"] += 1

            # hard cap on how many trading days we babysit an open trade
            if pos is not None and pos["hold_days"] >= max_hold_days:
                trades.append(_close_trade(pos, "HOLD_TIMEOUT", day, c["time"], c["close"]))
                pos = None
            if pos is not None:
                continue  # a position blocks new signals

        # ------------ pending signal -> entry on price break -----------------------
        if pending is not None and pending["day"] != day:
            pending = None  # never carry a stale signal overnight
        if pending is not None:
            sig_high = pending["candle_high"]
            sig_low = pending["candle_low"]
            if pending["type"] == "CE" and c["high"] >= sig_high:
                pos = _open_trade("CE", pending, sig_high, sig_low, day, c["time"], delta, lot_size, lots, rr, delta_decay)
                last_entry_day = day
                pending = None
            elif pending["type"] == "PE" and c["low"] <= sig_low:
                pos = _open_trade("PE", pending, sig_high, sig_low, day, c["time"], delta, lot_size, lots, rr, delta_decay)
                last_entry_day = day
                pending = None
            if pos is not None:
                continue

        # ------------ new signal evaluation (1 trade per day) ---------------------
        if last_entry_day == day or pos is not None:
            continue
        if i < 2 or np.isnan(rsi[i]) or np.isnan(rsi_ema[i]) or np.isnan(rsi[i - 1]) or np.isnan(rsi_ema[i - 1]):
            continue

        rsi_prev, rsi_curr = rsi[i - 1], rsi[i]
        ema_prev, ema_curr = rsi_ema[i - 1], rsi_ema[i]
        crossed_below = rsi_prev > ema_prev and rsi_curr <= ema_curr   # RSI down through its EMA
        crossed_above = rsi_prev < ema_prev and rsi_curr >= ema_curr   # RSI up through its EMA

        # extremum filter: RSI had recently been in the required zone
        in_overbought = not np.isnan(rsi_roll_max[i]) and rsi_roll_max[i] >= overbought
        in_oversold = not np.isnan(rsi_roll_min[i]) and rsi_roll_min[i] <= oversold

        if crossed_below and in_overbought:  # fade overbought -> PUT
            pending = {"type": "PE", "candle_high": c["high"], "candle_low": c["low"],
                       "day": day, "sig_time": c["time"], "rsi_at_signal": rsi_curr}
        elif crossed_above and in_oversold:  # fade oversold -> CALL
            pending = {"type": "CE", "candle_high": c["high"], "candle_low": c["low"],
                       "day": day, "sig_time": c["time"], "rsi_at_signal": rsi_curr}

    # force-exit any open trade at data end
    if pos is not None:
        trades.append(_close_trade(pos, "DATA_END", candles[-1]["date"],
                                   candles[-1]["time"], candles[-1]["close"]))

    return trades, tg


def _open_trade(typ, pending, sig_high, sig_low, day, tm, delta, lot_size, lots, rr, delta_decay):
    if sig_high - sig_low <= 0.0:
        return None
    sl_dist = 0.0
    if typ == "CE":
        entry = sig_high
        sl = sig_low
        target = entry + rr * (entry - sl)
        sl_dist = entry - sl
    else:
        entry = sig_low
        sl = sig_high
        target = entry - rr * (sl - entry)
        sl_dist = sl - entry
    return {
        "type": typ,
        "entry_day": day,
        "entry_time": tm,
        "entry": entry,
        "sl": sl,
        "target": target,
        "sl_dist": sl_dist,
        "sig_time": pending.get("sig_time"),
        "hold_bars": 1,
        "hold_days": 1,
        "delta": delta,
        "delta_decay": delta_decay,
        "lot_size": lot_size,
        "lots": lots,
    }


def _close_trade(pos, reason, day, tm, fill):
    opt_type = pos["type"]
    spot_diff = (fill - pos["entry"]) if opt_type == "CE" else (pos["entry"] - fill)
    # option P&L (ATM delta, first-order) with crude decay for multi-day holds
    effective_delta = pos["delta"] * (pos["delta_decay"] ** max(0, pos["hold_days"] - 1))
    opt_pnl = spot_diff * effective_delta * pos["lots"] * pos["lot_size"]
    r_multiple = spot_diff / pos["sl_dist"] if pos["sl_dist"] > 0 else 0.0
    return {
        "type": opt_type,
        "entry_day": pos["entry_day"],
        "entry_time": pos["entry_time"],
        "exit_day": str(day),
        "exit_time": str(tm),
        "entry": pos["entry"],
        "exit": fill,
        "spot_diff": spot_diff,
        "r_multiple": r_multiple,
        "opt_pnl": opt_pnl,
        "reason": reason,
        "sl": pos["sl"],
        "target": pos["target"],
        "hold_days": pos["hold_days"],
    }


# --------------------------------------------------------------------------- #
# Reporting                                                                   #
# --------------------------------------------------------------------------- #
def summarize(trades, candles, rsi_period, ema_period, overbought, oversold,
              lookback, rr, delta, lot_size, lots, label=""):
    d0 = str(candles[0]["date"])
    d1 = str(candles[-1]["date"])
    n_days = len({c["date"] for c in candles})
    print("\n" + "=" * 118)
    print(f"  {label or 'EMA-on-RSI backtest'}   |  RSI({rsi_period}) - EMA({ema_period}) on RSI  |  "
          f"Period {d0} -> {d1} ({n_days} days)  |  RR {rr}:1  OB {overbought} OS {oversold}")
    print("=" * 118)

    if not trades:
        print("  No trades generated with these settings.")
        return

    hdr = (f"{'#':<3}{'Date':<11}{'Type':<4}{'SigT':<6}{'EntHr':<6}{'ExHr':<6}"
           f"{'SpotIn':>9}{'SpotEx':>9}{'Diff':>9}{'R':>6}{'Hd':>3}{'OptP&L':>12}  Reason")
    print(hdr)
    print("-" * 118)

    gross_profit = gross_loss = 0.0
    wins = losses = 0
    spot_moves = []
    cum = 0.0
    peak = 0.0
    max_dd = 0.0
    for t in trades:
        pnl = t["opt_pnl"]
        cum += pnl
        peak = max(peak, cum)
        max_dd = max(max_dd, peak - cum)
        spot_moves.append(t["spot_diff"])
        if pnl > 0:
            wins += 1
            gross_profit += pnl
        else:
            losses += 1
            gross_loss += abs(pnl)
        print(f"{t['type']:<3}{str(t['entry_day']):<11}{'':<4}{t['entry_time']:<6}{t['exit_time']:<6}"
              f"{t['entry']:>9.1f}{''} {t['exit']:>9.1f}{t['spot_diff']:>9.1f}{t['r_multiple']:>6.2f}"
              f"{t['hold_days']:>3}{pnl:>12,.0f}  {t['reason']}")
    print("-" * 118)

    net = gross_profit - gross_loss
    win_rate = 100.0 * wins / len(trades) if trades else 0.0
    pf = gross_profit / gross_loss if gross_loss > 0 else float("inf")
    n = len(trades)
    total_r = sum(t["r_multiple"] for t in trades)
    avg_r = total_r / n if n else 0.0

    print(f"\n  {label or 'Summary'}")
    print(f"    Trades............: {len(trades)}   (wins {wins}, losses {losses})")
    print(f"    Win rate..........: {win_rate:.1f}%")
    print(f"    Profit factor.....: {pf:.2f}")
    print(f"    Gross profit/loss.: {gross_profit:,.0f} / {gross_loss:,.0f}")
    print(f"    Net option P&L....: {net:+,.0f}  (qty = {lots}x{lot_size}, ~delta {delta})")
    print(f"    Avg R/normalized..: total R {total_r:+.2f}  avg {avg_r:+.2f} R/trade")
    print(f"    Max drawdown......: {max_dd:,.0f}")
    print(f"    Avg hold..........: {np.mean([t['hold_days'] for t in trades]):.2f} days")
    return {"trades": len(trades), "winrate": win_rate, "pf": pf, "net": net,
            "avgR": avg_r, "maxdd": max_dd}


# --------------------------------------------------------------------------- #
# CLI                                                                         #
# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--range", default="60d", help="Yahoo range (5m capped ~60d)")
    ap.add_argument("--rsi", type=int, default=14)
    ap.add_argument("--ema", type=int, default=9, help="EMA period applied to RSI values")
    ap.add_argument("--ob", type=float, default=70.0, help="overbought RSI")
    ap.add_argument("--os", type=float, default=30.0, help="oversold RSI")
    ap.add_argument("--lookback", type=int, default=10, help="bars back RSI must touch extreme")
    ap.add_argument("--rr", type=float, default=5.0, help="target R:R on spot")
    ap.add_argument("--delta", type=float, default=0.50)
    ap.add_argument("--delta-decay", type=float, default=1.0,
                    help="per-day delta multiplier for option holds (1.0 = none)")
    ap.add_argument("--lot-size", type=int, default=65)
    ap.add_argument("--lots", type=int, default=1)
    ap.add_argument("--max-hold-days", type=int, default=5)
    ap.add_argument("--sweep", action="store_true", help="sweep EMA(7..21) & report each")
    ap.add_argument("--ema-list", default="7,9,12,14,21", help="EMA periods for sweep")
    args = ap.parse_args()

    print(f"Fetching NIFTY 5m data (range={args.range}) ...")
    candles = fetch_nifty(args.range)
    if len(candles) < 200:
        print("Too little data fetched; aborting.")
        sys.exit(1)
    print(f"Loaded {len(candles)} valid 5m candles "
          f"({candles[0]['date']} -> {candles[-1]['date']})")

    if args.sweep:
        best = None
        for ep in [int(x) for x in args.ema_list.split(",")]:
            trades, _ = run(candles, args.rsi, ep, args.ob, args.os, args.lookback,
                            args.rr, args.delta, args.lot_size, args.lots,
                            args.max_hold_days, args.delta_decay, show=False)
            if not trades:
                continue
            n = len(trades)
            wins = sum(1 for t in trades if t["opt_pnl"] > 0)
            net = sum(t["opt_pnl"] for t in trades)
            totalR = sum(t["r_multiple"] for t in trades)
            avgR = totalR / n
            line = (f"EMA({ep:>2}) on RSI : {n:>3} trades | win {100*wins/n:5.1f}% | "
                    f"net {net:>+9,.0f} | avg {avgR:+.2f}R | totalR {totalR:+.2f}")
            print(line)
            score = net
            if best is None or score > best[0]:
                best = (score, ep, n, wins, avgR, totalR)
        print("-" * 70)
        if best:
            print(f"BEST by net P&L: EMA({best[1]}) -> {best[2]} trades, "
                  f"win {(100*best[3]/best[2]):.1f}%, avg {best[4]:+.2f}R, "
                  f"total {best[5]:+.2f}R")
    else:
        trades, _ = run(candles, args.rsi, args.ema, args.ob, args.os, args.lookback,
                        args.rr, args.delta, args.lot_size, args.lots,
                        args.max_hold_days, args.delta_decay, show=True)
        summarize(trades, candles, args.rsi, args.ema, args.ob, args.os,
                  args.lookback, args.rr, args.delta, args.lot_size, args.lots)


if __name__ == "__main__":
    main()