"""
Backtest of BOLLINGER_HA_1M (NIFTY weekly ATM option buying) on real Shoonya
1-minute NIFTY index data with Black-Scholes synthesized ATM option premiums.

Data: Shoonya TPSeries 1m for NIFTY (token 26000) and INDIAVIX (token 26017),
downloaded with --fetch into --data-dir (default data/backtest/, gitignored).
Requires .env with SHOONYA_USER_ID / SHOONYA_PASSWORD / SHOONYA_TOTP_SECRET /
SHOONYA_BASE_URL / SHOONYA_VENDOR_CODE / SHOONYA_CLIENT_ID / SHOONYA_SECRET_KEY.

Signal + exit rules mirror com.tradingbot.strategy.bollingerha.* .

WARNING: option premiums are Black-Scholes synthesized from the index path and
India VIX - real historical weekly-option minutes are not retrievable from
Shoonya or Kite (expired contracts leave the instrument master). Treat results
as a model, not fill-accurate replay. See
docs/superpowers/specs/2026-10-04-bollinger-ha-backtest-findings.md
"""
import json, math, argparse, time as _time, hashlib, hmac, base64, struct, urllib.parse
from pathlib import Path
from datetime import datetime, timedelta, date, time as dtime
from decimal import Decimal, ROUND_HALF_UP
from collections import defaultdict

Q2 = Decimal("0.01")
Q4 = Decimal("0.0001")
Q6 = Decimal("0.000001")
TICK = Decimal("0.05")

BB_PERIOD, BB_SD = 20, 2.0
BUFFER, MIN_SL, MAX_SL = Decimal("1.0"), Decimal("2.0"), Decimal("20.0")
MAX_TRADES = 2
ENTRY_START, ENTRY_CUTOFF, SQUARE_OFF = dtime(9, 15), dtime(10, 30), dtime(15, 0)
TREND_FILTER, TREND_EMA = True, 20
LOTS, LOT = 2, 65
QTY = LOTS * LOT
R = 0.065

ap = argparse.ArgumentParser()
ap.add_argument("--exit-model", choices=["tick", "candle"], default="tick")
ap.add_argument("--order-cost", type=float, default=25.0, help="all-in INR per order")
ap.add_argument("--slippage", type=float, default=0.05, help="INR per share per fill")
ap.add_argument("--rr", type=float, default=2.0, help="risk:reward for T1")
ap.add_argument("--ema-slope", type=int, default=0, help="EMA20 must move in trade direction over last N min")
ap.add_argument("--ema-gap", type=float, default=0.0, help="spot must be >= gap pts beyond EMA20")
ap.add_argument("--body-ratio", type=float, default=0.0, help="HA body >= ratio*HA range on signal candle")
ap.add_argument("--index-move", type=int, default=0, help="index must have moved in direction over last N min")
ap.add_argument("--min-entry", type=float, default=0.0, help="min premium entry (filters near-worthless)")
ap.add_argument("--max-risk", type=float, default=99.0, help="tighten max SL points")
ap.add_argument("--start", default=None, help="first session YYYY-MM-DD")
ap.add_argument("--end", default=None, help="last session YYYY-MM-DD")
ap.add_argument("--data-dir", default="data/backtest", help="folder for the 1m JSON files")
ap.add_argument("--fetch", action="store_true", help="download 1m index + VIX history from Shoonya")
a = ap.parse_args()
RR = Decimal(str(a.rr))

DATA_DIR = Path(a.data_dir)


def parse(t):
    return datetime.strptime(t, "%d-%m-%Y %H:%M:%S")


def _repo_env():
    env = {}
    for line in open(Path(__file__).resolve().parents[1] / ".env", encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def fetch_shoonya_1m():
    import requests

    env = _repo_env()
    base = env["SHOONYA_BASE_URL"].rstrip("/")
    uid, pwd = env["SHOONYA_USER_ID"], env["SHOONYA_PASSWORD"]
    secret, cid, skey = env.get("SHOONYA_TOTP_SECRET", ""), env.get("SHOONYA_CLIENT_ID", ""), env.get("SHOONYA_SECRET_KEY", "")
    vc, ip = env.get("SHOONYA_VENDOR_CODE", ""), env.get("SHOONYA_PUBLIC_IP", "127.0.0.1")
    hdrs = {"Content-Type": "application/x-www-form-urlencoded", "X-Forwarded-For": ip}
    sha = lambda s: hashlib.sha256(s.encode()).hexdigest()

    def post(route, jd, extra=""):
        r = requests.post(
            f"{base}/NorenWClientAPI/{route}",
            data="jData=" + json.dumps(jd).replace("&", "%26") + extra,
            headers=hdrs,
            timeout=60,
        )
        return r.json()

    offs = [83, 50, 97, 114, 110, 46, 27, 93]
    appkey = sha(uid + "|" + "".join(chr(o + p) for p, o in enumerate(offs)))
    k = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    counter = int(_time.time()) // 30
    h = hmac.new(k, struct.pack(">Q", counter), hashlib.sha1).digest()
    off = h[19] & 0xF
    totp = str((struct.unpack(">I", h[off : off + 4])[0] & 0x7FFFFFFF) % 10**6).zfill(6)

    qa = post(
        "QuickAuth",
        {
            "apkversion": "W2_20250926", "uid": uid, "pwd": sha(pwd), "factor2": totp,
            "appkey": appkey, "imei": "12345678-1234-1234-1234-123456789abc",
            "addldivinf": "Mozilla/5.0", "source": "API", "vc": vc, "app_key": cid,
        },
    )
    if qa.get("stat") != "Ok":
        raise SystemExit("QuickAuth failed: " + str(qa.get("emsg") or qa))
    gt = post("GenAcsTok", {"client_id": cid, "code": qa["code"], "checksum": sha(cid + skey + qa["code"])})
    tok = gt.get("susertoken") or gt.get("access_token")
    if not tok:
        raise SystemExit("GenAcsTok failed: " + str(gt))

    def series(token):
        end = int(_time.time())
        seen = {}
        for st in (end - 270 * 86400, end - 140 * 86400):
            rows = post(
                "TPSeries",
                {"uid": uid, "exch": "NSE", "token": token, "st": str(st), "et": str(end), "intrv": "1"},
                "&jKey=" + urllib.parse.quote(tok, safe=""),
            )
            if not isinstance(rows, list):
                raise SystemExit(f"TPSeries {token} failed: {str(rows)[:200]}")
            for r0 in rows:
                if r0.get("stat") == "Ok":
                    seen[r0["time"]] = r0
        out = [
            {"t": t, "o": float(r["into"]), "h": float(r["inth"]), "l": float(r["intl"]),
             "c": float(r["intc"]), "v": int(float(r["intv"] or 0))}
            for t, r in seen.items()
        ]
        out.sort(key=lambda x: datetime.strptime(x["t"], "%d-%m-%Y %H:%M:%S"))
        return out

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    for token, name in (("26000", "nifty_1m_full"), ("26017", "vix_1m_full")):
        rows = series(token)
        json.dump(rows, open(DATA_DIR / f"{name}.json", "w"))
        print(f"fetched {name}: {len(rows)} candles  {rows[0]['t']} -> {rows[-1]['t']}")


def load(name):
    for suffix in ("_full", ""):
        p = DATA_DIR / f"{name}{suffix}.json"
        if p.exists():
            return json.load(open(p))
    raise SystemExit(f"missing {name} - run with --fetch (needs .env Shoonya credentials)")


if a.fetch or not (DATA_DIR / "nifty_1m_full.json").exists():
    fetch_shoonya_1m()


idx = sorted((parse(r["t"]), r) for r in load("nifty_1m"))
vix_raw = sorted((parse(r["t"]), r["c"]) for r in load("vix_1m"))
if a.start:
    idx = [x for x in idx if x[0].date() >= date.fromisoformat(a.start)]
if a.end:
    idx = [x for x in idx if x[0].date() <= date.fromisoformat(a.end)]
if not idx:
    raise SystemExit("empty window")

vix_by_day = defaultdict(list)
for t, v in vix_raw:
    vix_by_day[t.date()].append((t, v))


def vix_at(ts):
    rows = vix_by_day.get(ts.date())
    if rows:
        prev = None
        for t, v in rows:
            if t > ts:
                return prev if prev is not None else v
            prev = v
        return prev
    for dd in sorted(vix_by_day, reverse=True):
        if dd < ts.date():
            return vix_by_day[dd][-1][1]
    return 12.0


def norm(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bs(S, K, tau, sig, cp):
    intrinsic = max(S - K, 0.0) if cp == "CE" else max(K - S, 0.0)
    if tau <= 1e-9 or sig <= 1e-9:
        return Decimal(str(intrinsic)).quantize(TICK, ROUND_HALF_UP)
    st = sig * math.sqrt(tau)
    d1 = (math.log(S / K) + (R + 0.5 * sig * sig) * tau) / st
    d2 = d1 - st
    if cp == "CE":
        px = S * norm(d1) - K * math.exp(-R * tau) * norm(d2)
    else:
        px = K * math.exp(-R * tau) * norm(-d2) - S * norm(-d1)
    px = max(px, intrinsic)
    return Decimal(str(round(px * 20) / 20)).quantize(TICK, ROUND_HALF_UP)


# NSE 2026 holidays (mirrors NseTradingCalendarUtil)
HOLIDAYS = {
    date(2026, 1, 26), date(2026, 2, 16), date(2026, 3, 3), date(2026, 3, 20),
    date(2026, 3, 31), date(2026, 4, 3), date(2026, 4, 14), date(2026, 5, 1),
    date(2026, 8, 15), date(2026, 9, 14), date(2026, 10, 2), date(2026, 10, 20),
    date(2026, 11, 8), date(2026, 11, 24), date(2026, 12, 25),
}


def is_td(d):
    return d.weekday() < 5 and d not in HOLIDAYS


def next_expiry(d):
    # Weekly expiry = Tuesday; when Tuesday is a holiday it rolls to the prior trading day
    # (confirmed against broker master: 19-Oct-26 and 23-Nov-26 expiries sit on Mondays).
    x = d
    while x.weekday() != 1:
        x += timedelta(days=1)
    while not is_td(x):
        x -= timedelta(days=1)
    return x


class HAState:
    """Incremental Heikin-Ashi: O(1) per candle instead of rebuilding the series."""

    def __init__(self):
        self.prev_o = self.prev_c = None
        self.n = 0
        self.candles = []

    def push(self, candle):
        o, h, l, c = candle
        hc = ((o + h + l + c) / Decimal(4)).quantize(Q2, ROUND_HALF_UP)
        ho = (((o + c) / Decimal(2)) if self.n == 0 else ((self.prev_o + self.prev_c) / Decimal(2))).quantize(
            Q2, ROUND_HALF_UP
        )
        self.candles.append(
            {"o": ho, "h": max(h, ho, hc), "l": min(l, ho, hc), "c": hc, "green": hc > ho}
        )
        self.prev_o, self.prev_c = ho, hc
        self.n += 1


def bb(ha_list):
    if len(ha_list) < BB_PERIOD:
        return None
    win = [x["c"] for x in ha_list[-BB_PERIOD:]]
    sma = (sum(win) / Decimal(BB_PERIOD)).quantize(Q4, ROUND_HALF_UP)
    var = (sum((c - sma) ** 2 for c in win) / Decimal(BB_PERIOD)).quantize(Q6, ROUND_HALF_UP)
    std = Decimal(str(math.sqrt(float(var)) * BB_SD))
    return (sma + std).quantize(Q2, ROUND_HALF_UP), (sma - std).quantize(Q2, ROUND_HALF_UP)


def ema(vals, period):
    if len(vals) < period:
        return None
    cur = (sum(Decimal(str(v)) for v in vals[:period]) / Decimal(period)).quantize(
        Q4, ROUND_HALF_UP
    )
    alpha = Decimal(str(2.0 / (period + 1)))
    oma = Decimal(1) - alpha
    for v in vals[period:]:
        cur = (Decimal(str(v)) * alpha + cur * oma).quantize(Q4, ROUND_HALF_UP)
    return float(cur.quantize(Q2, ROUND_HALF_UP))


def aligned_partial(total, lot):
    if lot <= 1 or total < 2 * lot:
        return 0
    al = (total // 2 // lot) * lot
    return 0 if (al < lot or al >= total) else al


class Setup:
    def __init__(self, cp):
        self.cp, self.touched, self.age = cp, False, 0

    def touch(self):
        self.touched, self.age = True, 0

    def inc_age(self):
        if self.touched:
            self.age += 1
            if self.age > 3:
                self.reset()

    def reset(self):
        self.touched, self.age = False, 0


class Pos:
    pass


# ---------------- premium series synthesis ----------------
by_day = defaultdict(list)
for t, r in idx:
    by_day[t.date()].append((t, r))

days = sorted(by_day)
prev_close_index = None
premium = {}

for n, d in enumerate(days):
    rows = by_day[d]
    spot0 = rows[0][1]["c"] if prev_close_index is None else prev_close_index
    K = float(int(round(spot0 / 50.0)) * 50)
    t_exp = datetime.combine(next_expiry(d), dtime(15, 30))
    for t, r in rows:
        tau = max((t_exp - t).total_seconds(), 0.0) / (365.0 * 24 * 3600)
        sig = vix_at(t) / 100.0
        O, H, L, C = float(r["o"]), float(r["h"]), float(r["l"]), float(r["c"])
        for cp in ("CE", "PE"):
            po, pc = bs(O, K, tau, sig, cp), bs(C, K, tau, sig, cp)
            if cp == "CE":
                ph, pl = bs(H, K, tau, sig, cp), bs(L, K, tau, sig, cp)
            else:
                ph, pl = bs(L, K, tau, sig, cp), bs(H, K, tau, sig, cp)
            premium[(d, t, cp)] = (po, ph, pl, pc)
    prev_close_index = rows[-1][1]["c"]

print(f"days={len(days)} ({days[0]} .. {days[-1]})  index_minutes={len(idx)}")
atms = {}
for n, d in enumerate(days):
    spot0 = by_day[d][0][1]["c"] if n == 0 else by_day[days[n - 1]][-1][1]["c"]
    atms[str(d)] = int(round(spot0 / 50.0) * 50)
print("ATM strikes:", atms)

# ---------------- engine replay ----------------
trades, stats = [], defaultdict(int)
pos = None
hist = {"CE": [], "PE": []}
ha_state = {"CE": HAState(), "PE": HAState()}
setups = {"CE": Setup("CE"), "PE": Setup("PE")}
spot_hist, latest_spot = [], None
cur_date, count, locked, day_pnl = None, 0, False, 0.0
daily_pnl, peak, max_dd = defaultdict(float), 0.0, 0.0


def last_close(cp):
    h = hist[cp]
    return h[-1][3] if h else None


def close_pos(price, reason, ts, kind):
    global pos, locked, day_pnl
    p = pos
    px = Decimal(str(price))
    pnl = float((px - p.entry) * p.remaining)
    day_pnl += pnl
    p.exits.append((ts, reason, float(px), p.remaining, pnl))
    if kind == "stop":
        if count < MAX_TRADES:
            setups["CE" if p.cp == "PE" else "PE"].reset()
        else:
            locked = True
    stats["exits_" + kind] += 1
    pos = None


def manage(candle, ts):
    global pos, day_pnl
    p = pos
    o, h, l, c = candle
    if o <= p.stop:  # gap beyond stop -> worse of open/close (Java behaviour)
        close_pos(float(min(o, c)), "GAP_STOP", ts, "stop")
        return
    if not p.target_hit and h >= p.target:
        eq = aligned_partial(p.qty, LOT)
        if eq <= 0:
            p.target_hit, p.cost_sl, p.stop = True, True, p.entry
        else:
            pnl = float((p.target - p.entry) * eq)
            p.exits.append((ts, "T1_PARTIAL", float(p.target), eq, pnl))
            stats["partial"] += 1
            day_pnl += pnl
            p.qty -= eq
            p.remaining -= eq
            p.target_hit, p.cost_sl, p.stop = True, True, p.entry
    if pos is not None and l <= p.stop:
        if a.exit_model == "tick":  # protective stop fills at stop
            obs = p.stop
        else:  # candle backstop books the close beyond the stop
            obs = p.stop if c >= p.stop else c
        close_pos(float(obs), "COST_SL" if p.cost_sl else "INITIAL_SL", ts, "stop")


def evaluate(cp, ts):
    global pos, count
    h = hist[cp]
    if len(h) < BB_PERIOD:
        return
    ha_list = ha_state[cp].candles
    bands = bb(ha_list)
    if bands is None:
        return
    lower = bands[1]
    last = ha_list[-1]
    setup = setups[cp]
    if last["l"] <= lower:
        stats["touches"] += 1
        setup.touch()
        if not last["green"]:
            return
    else:
        setup.inc_age()
    if not (setup.touched and last["green"]):
        return
    stats["green_after_touch"] += 1
    if TREND_FILTER and latest_spot is not None and len(spot_hist) >= TREND_EMA:
        e = ema(spot_hist, TREND_EMA)
        if e is not None:
            if cp == "CE" and latest_spot < e:
                stats["trend_reject"] += 1
                return
            if cp == "PE" and latest_spot > e:
                stats["trend_reject"] += 1
                return
    entry = (last["h"] + BUFFER).quantize(Q2, ROUND_HALF_UP)
    stop = (last["l"] - BUFFER).quantize(Q2, ROUND_HALF_UP)
    risk = entry - stop
    if risk < MIN_SL or risk > MAX_SL:
        stats["risk_reject"] += 1
        setup.reset()
        return
    if a.min_entry and float(entry) < a.min_entry:
        stats["min_entry_reject"] += 1
        setup.reset()
        return
    if risk > Decimal(str(a.max_risk)):
        stats["max_risk_reject"] += 1
        setup.reset()
        return
    if a.body_ratio:
        rng = last["h"] - last["l"]
        body = abs(last["c"] - last["o"])
        if rng <= 0 or float(body) < a.body_ratio * float(rng):
            stats["body_reject"] += 1
            return
    if a.index_move and len(spot_hist) > a.index_move:
        d = latest_spot - spot_hist[-1 - a.index_move]
        if (cp == "CE" and d <= 0) or (cp == "PE" and d >= 0):
            stats["idx_move_reject"] += 1
            return
    if a.ema_slope and len(spot_hist) >= TREND_EMA + a.ema_slope:
        e0 = ema(spot_hist, TREND_EMA)
        e1 = ema(spot_hist[: -a.ema_slope], TREND_EMA)
        if e0 is not None and e1 is not None:
            if (cp == "CE" and e0 <= e1) or (cp == "PE" and e0 >= e1):
                stats["ema_slope_reject"] += 1
                return
    if a.ema_gap:
        e = ema(spot_hist, TREND_EMA)
        if e is None or (cp == "CE" and latest_spot - e < a.ema_gap) or (
            cp == "PE" and e - latest_spot < a.ema_gap
        ):
            stats["ema_gap_reject"] += 1
            return
    target = (entry + risk * RR).quantize(Q2, ROUND_HALF_UP)
    p = Pos()
    p.cp, p.entry, p.stop, p.target = cp, entry, stop, target
    p.stop0, p.risk = stop, risk
    p.qty = p.remaining = QTY
    p.target_hit = p.cost_sl = False
    p.opened, p.exits = ts, []
    pos = p
    count += 1
    setup.reset()
    trades.append(p)
    stats["entries"] += 1


for t, r in idx:
    done = t + timedelta(minutes=1)
    day = t.date()

    if cur_date is None:
        cur_date = day
    elif done.date() != cur_date:
        if pos:
            close_pos(float(last_close(pos.cp)), "ROLLOVER", t, "square")
        daily_pnl[cur_date] = day_pnl
        cur_date, count, locked, day_pnl = day, 0, False, 0.0
        hist = {"CE": [], "PE": []}
        ha_state = {"CE": HAState(), "PE": HAState()}
        setups = {"CE": Setup("CE"), "PE": Setup("PE")}
        spot_hist, latest_spot = [], None

    if pos and done.time() >= SQUARE_OFF:  # per-minute auto square-off poller
        lc = last_close(pos.cp)
        if lc is not None:
            close_pos(float(lc), "AUTO_SQUARE_OFF", t, "square")

    latest_spot = float(r["c"])
    spot_hist.append(latest_spot)

    o, h, l, c = float(r["o"]), float(r["h"]), float(r["l"]), float(r["c"])
    for cp in ("CE", "PE"):
        pc_ohlc = premium[(day, t, cp)]
        hist[cp].append(pc_ohlc)
        ha_state[cp].push(pc_ohlc)

    if pos:
        ev = pos.cp
        manage(hist[ev][-1], t)
        if pos is None:
            # the opposite contract's event for this bar still runs entry evaluation
            other = "PE" if ev == "CE" else "CE"
            if not locked and count < MAX_TRADES and ENTRY_START <= done.time() <= ENTRY_CUTOFF:
                evaluate(other, t)
        daily_pnl[cur_date] = day_pnl
        continue

    if not locked and count < MAX_TRADES and ENTRY_START <= done.time() <= ENTRY_CUTOFF:
        for cp in ("CE", "PE"):
            evaluate(cp, t)
    daily_pnl[cur_date] = day_pnl

if pos:
    close_pos(float(last_close(pos.cp)), "EOD_UNWIND", idx[-1][0], "square")
daily_pnl[cur_date] = day_pnl

# ---------------- report ----------------
gross = 0.0
rows = []
for p in trades:
    pnl = sum(x[4] for x in p.exits)
    gross += pnl
    fills = [(p.entry, QTY)] + [(Decimal(str(x[2])), x[3]) for x in p.exits]
    cost = len(fills) * a.order_cost + sum(a.slippage * q for _, q in fills)
    rows.append((p.opened, p.cp, float(p.entry), float(p.stop), float(p.target), pnl, cost, p.exits))

print(f"\nexit-model={a.exit_model}  RR=1:{a.rr:g}  trades={len(trades)}")
print("stats:", dict(stats))
neg = [p for p in trades if p.stop0 <= 0]
print(f"trades with initial stop <= 0 (unorderable): {len(neg)}")
for p in neg:
    print(f"    {p.opened:%d-%m %H:%M} {p.cp} entry={p.entry} stop={p.stop0} risk={p.risk}")
print("premium sanity (day 1):")
d0 = days[0]
for hh, mm in ((9, 20), (12, 30), (14, 55)):
    for t, r in by_day[d0]:
        if (t.hour, t.minute) == (hh, mm):
            ce, pe = premium[(d0, t, "CE")][3], premium[(d0, t, "PE")][3]
            print(f"    {t:%H:%M} spot={float(r['c']):.0f} CE={float(ce)} PE={float(pe)}")
            break
print("per-day index move%:")
for n, d in enumerate(days):
    rows_d = by_day[d]
    o, c = rows_d[0][1]["o"], rows_d[-1][1]["c"]
    print(f"    {d} O={o:7.0f} C={c:7.0f} {100*(float(c)/float(o)-1):+5.2f}%  "
          f"range={float(min(r['l'] for _, r in rows_d)):.0f}..{float(max(r['h'] for _, r in rows_d)):.0f}")
wins = [r[5] for r in rows if r[5] > 0]
losses = [r[5] for r in rows if r[5] <= 0]
costs = sum(r[6] for r in rows)
net = gross - costs
print(f"gross PnL = {gross:+.1f} INR   costs = {costs:.1f}   net = {net:+.1f}")
print(f"wins={len(wins)} losses={len(losses)}  win%={100*len(wins)/max(len(rows),1):.1f}")
if wins:
    print(f"avg win={sum(wins)/len(wins):+.1f}")
if losses:
    print(f"avg loss={sum(losses)/len(losses):+.1f}")
run = 0.0
for r in rows:
    run += r[5]
    peak = max(peak, run)
    max_dd = max(max_dd, peak - run)
print(f"max drawdown = {max_dd:.1f} INR")
sq = sum(e[4] for p in trades for e in p.exits if e[1] == "AUTO_SQUARE_OFF")
print(f"net excl. square-off runners = {net - sq:+.1f}")
agg = defaultdict(float)
cnt = defaultdict(int)
for p in trades:
    for e in p.exits:
        agg[e[1]] += e[4]
        cnt[e[1]] += 1
print("PnL by exit reason:")
for k in sorted(agg, key=lambda k: -agg[k]):
    print(f"    {k:18s} n={cnt[k]:2d}  {agg[k]:+9.1f}")

print("\nTRADES:")
for r in rows:
    print(
        f"  {r[0]:%d-%m %H:%M} {r[1]}  E={r[2]:.2f} SL={r[3]:.2f} T={r[4]:.2f}  "
        f"PnL={r[5]:+8.1f}  exits={[(x[1], x[2]) for x in r[7]]}"
    )

print("\nDAILY:")
tot = 0.0
for d in sorted(daily_pnl):
    v = daily_pnl[d]
    tot += v
    print(f"  {d}: {v:+9.1f}  (cum {tot:+9.1f})")

i0, i1 = idx[0][1]["c"], idx[-1][1]["c"]
print(f"\nNIFTY buy&hold over window: {i0:.0f} -> {i1:.0f} = {100*(i1/i0-1):+.2f}%")
