"""
Silver ORB-BIAS + VWAP-CROSS futures strategy (v3 of the silver ORB series).

Spec:
  - ORB = high/low of the 18:00-18:30 IST candles (interval configurable: 5m exact
    spec, 1h coarse lens for the longer history yfinance provides).
  - BIAS: after 18:30, the first candle CLOSE beyond the ORB sets the bias
    (above ORB high => LONG, below ORB low => SHORT). Bias persists and flips only
    on a close beyond the opposite boundary.
  - ENTRY: wait for a VWAP cross in the same direction — a candle closing across
    VWAP from the other side (prev close <= prev VWAP and close > VWAP for LONG;
    mirror for SHORT). The bias candle itself may also be the cross candle.
  - One trade per day, no entries after 21:00 IST, square-off at 23:00 IST.
  - Futures P&L: SL -7% / target +25% of MARGIN deployed (analogue of the options
    spec), default 10% margin => 10x => SL 0.70% / TGT 2.50% of price.

VWAP: session-anchored at 18:00 IST by default (evening-session VWAP, includes the
ORB candles); a calendar-day (00:00 IST) anchor variant is also reported. Typical
price (H+L+C)/3 weighted by volume (zero-volume bars treated as 1).

DATA LIMIT: yfinance serves at most 60 days of 5m/15m bars (server-side cap);
only 1h+ reaches 3 months. So: RUN A = exact 5m spec over the full 60 days,
RUN B = 3-month lens at 1h granularity (hourly ORB bar + hourly VWAP cross).
"""

import sys
import io
from dataclasses import dataclass
from datetime import time

import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

# ----------------------------- parameters -----------------------------
TICKER = "SI=F"

ORB_START = time(18, 0)  # IST
ORB_END = time(18, 30)  # IST, exclusive
ENTRY_CUTOFF = time(21, 0)  # IST, no fresh entries after this
SQUAREOFF = time(23, 0)  # IST, flat by this time

RISK_PCT = 0.07  # 7% of margin at risk
TARGET_PCT = 0.25  # 25% of margin target
MARGIN_PCT = 0.10  # assumed MCX futures margin (=> 10x leverage)
MARGIN_SWEEP = (0.05, 0.10, 0.20, 1.00)  # incl. 100% = literal price-space 7/25

MIN_ORB_RANGE_PCT = 0.05  # skip dead/near-flat opening ranges
RISK_PER_TRADE_CAPITAL = 0.01  # equity curve: 1% of capital risked per trade

ANCHOR_SESSION = time(18, 0)  # session VWAP anchor
ANCHOR_DAY = time(0, 0)  # calendar-day VWAP anchor (comparison variant)

IST = "Asia/Kolkata"
RUNS = {"A": {"interval": "5m", "days": 59, "label": "exact 5m spec / 60 days"},
        "B": {"interval": "1h", "days": 89, "label": "coarse 1h lens / 3 months"}}

# Optional local OHLCV CSV (e.g. scripts/shoonya_mcx_pull.py output) instead of
# yfinance: python scripts/silver_orb_vwap_futures_backtest.py data/mcx_silver_5m_95d.csv
LOCAL_CSV = (sys.argv[1] if len(sys.argv) > 1
             and sys.argv[1].lower().endswith(".csv") else None)

_DF_CACHE: dict = {}


def _load_local_csv(interval: str) -> pd.DataFrame:
    """Load a local OHLCV CSV (datetime with tz offset) shaped like yfinance."""
    df = pd.read_csv(LOCAL_CSV)
    ts = pd.to_datetime(df["datetime"], utc=True)
    df = df.set_index(ts).sort_index()
    df.index = df.index.tz_convert(IST)
    df = df[["open", "high", "low", "close", "volume"]].copy()
    df.columns = ["Open", "High", "Low", "Close", "Volume"]
    df = df.dropna(subset=["Close"])
    if interval != "5m":
        agg = {"Open": "first", "High": "max", "Low": "min",
               "Close": "last", "Volume": "sum"}
        df = (df.resample(interval, label="left", closed="left")
                .agg(agg).dropna(subset=["Close"]))
    return df


# ----------------------------- data -----------------------------
def download(interval: str, days: int) -> pd.DataFrame:
    key = ("local", interval) if LOCAL_CSV else (interval, days)
    if key in _DF_CACHE:
        return _DF_CACHE[key]
    if LOCAL_CSV:
        df = _load_local_csv(interval)
        _DF_CACHE[key] = df
        return df
    start = (pd.Timestamp.today().normalize() - pd.Timedelta(days=days)).strftime("%Y-%m-%d")
    df = yf.download(TICKER, start=start, interval=interval, progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna(subset=["Close"]).copy()
    if df.index.tz is None:
        df.index = df.index.tz_localize("UTC")
    df.index = df.index.tz_convert(IST)
    df = df.sort_index()
    _DF_CACHE[key] = df
    return df


@dataclass
class Trade:
    date: object
    entry_ts: pd.Timestamp
    side: str  # LONG / SHORT
    entry_price: float
    margin_pct: float
    bias_ts: pd.Timestamp = None  # when the ORB bias was set
    exit_ts: pd.Timestamp = None
    exit_price: float = 0.0
    reason: str = ""
    points: float = 0.0
    price_ret_pct: float = 0.0
    margin_ret_pct: float = 0.0
    r_multiple: float = 0.0

    def settle(self, exit_ts, exit_price, reason):
        self.exit_ts = exit_ts
        self.exit_price = exit_price
        self.reason = reason
        sign = 1.0 if self.side == "LONG" else -1.0
        self.points = sign * (exit_price - self.entry_price)
        self.price_ret_pct = self.points / self.entry_price * 100.0
        self.margin_ret_pct = self.price_ret_pct / self.margin_pct
        self.r_multiple = self.margin_ret_pct / (RISK_PCT * 100.0)


def backtest(df: pd.DataFrame, interval_min: int, anchor: time, margin: float):
    """Returns (trades, day_stats) where day_stats counts how valid-ORB days ended:
    'TRADED' | 'NO_BIAS' (never closed beyond ORB) | 'NO_CROSS' (bias set, no
    aligned VWAP cross before cutoff)."""
    dates = pd.unique(df.index.date)
    trades = []
    day_stats = {"TRADED": 0, "NO_BIAS": 0, "NO_CROSS": 0}
    min_orb_bars = 4 if interval_min <= 5 else 1

    for d in dates:
        day = df[df.index.date == d]

        # ORB = bars whose [open, open+interval) intersects [18:00, 18:30).
        # Interval-aware: 5m yields the six window bars; Yahoo's 1h bars align to
        # :30 IST, so the single 17:30-18:30 bar is the lens' representation of
        # the window (it contains 18:00-18:30 as its second half).
        bar_td = pd.Timedelta(minutes=interval_min)
        w_start = day.index[0].normalize() + pd.Timedelta(
            hours=ORB_START.hour, minutes=ORB_START.minute
        )
        w_end = day.index[0].normalize() + pd.Timedelta(
            hours=ORB_END.hour, minutes=ORB_END.minute
        )
        orb = day[(day.index < w_end) & (day.index + bar_td > w_start)]
        if len(orb) < min_orb_bars:
            continue
        orb_high, orb_low = float(orb["High"].max()), float(orb["Low"].min())
        if (orb_high - orb_low) / orb_low * 100.0 < MIN_ORB_RANGE_PCT:
            continue

        # VWAP over the anchored slice (18:00 session or whole calendar day)
        anchored = day[day.index.time >= anchor]
        if anchored.empty:
            continue
        vol = anchored["Volume"].where(anchored["Volume"] > 0, 1.0)
        typical = (anchored["High"] + anchored["Low"] + anchored["Close"]) / 3.0
        vwap = (typical * vol).cumsum() / vol.cumsum()
        closes = anchored["Close"]
        # cross flags relative to the previous bar of the anchored slice
        up = pd.Series(False, index=anchored.index)
        dn = pd.Series(False, index=anchored.index)
        up.iloc[1:] = (
            ((closes.shift(1) <= vwap.shift(1)) & (closes > vwap)).iloc[1:]
        )
        dn.iloc[1:] = (
            ((closes.shift(1) >= vwap.shift(1)) & (closes < vwap)).iloc[1:]
        )
        pos = {ts: i for i, ts in enumerate(anchored.index)}

        session = day[(day.index.time >= ORB_END) & (day.index.time <= SQUAREOFF)]
        if session.empty:
            continue

        trade: Trade = None
        bias = None  # None / "LONG" / "SHORT"
        bias_ts = None

        for ts, bar in session.iterrows():
            c, lo, hi = float(bar["Close"]), float(bar["Low"]), float(bar["High"])
            now = ts.time()

            if trade is None:
                if now >= ENTRY_CUTOFF:
                    break  # entries closed for the day
                # 1) bias from ORB boundary breaks (flips allowed)
                if c > orb_high:
                    if bias != "LONG":
                        bias, bias_ts = "LONG", ts
                elif c < orb_low:
                    if bias != "SHORT":
                        bias, bias_ts = "SHORT", ts
                # 2) entry on a VWAP cross in the bias direction (same bar allowed)
                i = pos[ts]
                if bias == "LONG" and bool(up.iloc[i]):
                    trade = Trade(d, ts, "LONG", c, margin, bias_ts=bias_ts)
                elif bias == "SHORT" and bool(dn.iloc[i]):
                    trade = Trade(d, ts, "SHORT", c, margin, bias_ts=bias_ts)
                continue  # entry at this bar's close; exits start next bar

            # ---- manage open position ----
            sl_dist = RISK_PCT * margin * trade.entry_price
            tgt_dist = TARGET_PCT * margin * trade.entry_price
            if trade.side == "LONG":
                sl, tgt = trade.entry_price - sl_dist, trade.entry_price + tgt_dist
                hit_sl = lo <= sl  # conservative: SL checked before target
                hit_tgt = hi >= tgt
            else:
                sl, tgt = trade.entry_price + sl_dist, trade.entry_price - tgt_dist
                hit_sl = hi >= sl
                hit_tgt = lo <= tgt

            if hit_sl:
                trade.settle(ts, sl, "SL")
            elif hit_tgt:
                trade.settle(ts, tgt, "TARGET")
            elif now >= SQUAREOFF:
                trade.settle(ts, c, "SQUAREOFF")
            else:
                continue
            trades.append(trade)
            trade = None
            break  # one trade per day

        if trade is not None:  # day ran out before square-off time
            last_ts, last_close = session.index[-1], float(session["Close"].iloc[-1])
            trade.settle(last_ts, last_close, "SQUAREOFF")
            trades.append(trade)
            day_stats["TRADED"] += 1
            continue
        if any(t.date == d for t in trades):
            day_stats["TRADED"] += 1
        elif bias is None:
            day_stats["NO_BIAS"] += 1
        else:
            day_stats["NO_CROSS"] += 1

    return trades, day_stats


def summarize(trades, day_stats, verbose=True):
    out = print if verbose else (lambda *a, **k: None)
    out(
        f"\nDay outcomes: traded {day_stats['TRADED']} | no ORB bias "
        f"{day_stats['NO_BIAS']} | bias but no aligned VWAP cross {day_stats['NO_CROSS']}"
    )
    if not trades:
        out("\nNo trades generated.")
        return None

    tdf = pd.DataFrame([vars(t) for t in trades])
    wr = 100.0 * (tdf["margin_ret_pct"] > 0).mean()
    out(
        f"\nTrades: {len(tdf)} over {tdf['date'].nunique()} days | win rate {wr:.1f}%"
    )
    out(
        f"Net: {tdf['margin_ret_pct'].sum():+.2f}% of margin | "
        f"sum R: {tdf['r_multiple'].sum():+.2f}R | avg R: {tdf['r_multiple'].mean():+.2f}R | "
        f"total {tdf['points'].sum():+.2f} price points"
    )
    out("\nBy side:")
    for side, g in tdf.groupby("side"):
        out(
            f"  {side:<6} n={len(g):<3} wins={int((g['margin_ret_pct'] > 0).sum()):<3} "
            f"net {g['margin_ret_pct'].sum():+7.2f}% margin | {g['r_multiple'].sum():+6.2f}R"
        )
    out("\nBy exit reason:")
    for reason, g in tdf.groupby("reason"):
        out(
            f"  {reason:<10} n={len(g):<3} avg {g['margin_ret_pct'].mean():+7.2f}% margin | "
            f"{g['r_multiple'].sum():+6.2f}R"
        )

    tdf = tdf.reset_index(drop=True)
    tdf["equity_r"] = tdf["r_multiple"].cumsum()
    tdf["equity_pct"] = tdf["equity_r"] * RISK_PER_TRADE_CAPITAL * 100.0
    peak = tdf["equity_pct"].cummax()
    max_dd = (tdf["equity_pct"] - peak).min()
    gross_win = tdf.loc[tdf["margin_ret_pct"] > 0, "margin_ret_pct"].sum()
    gross_loss = -tdf.loc[tdf["margin_ret_pct"] < 0, "margin_ret_pct"].sum()
    pf = gross_win / max(1e-9, gross_loss)
    out(
        f"\nEquity (1% risk/trade): final {tdf['equity_pct'].iloc[-1]:+.2f}% | "
        f"max drawdown {max_dd:.2f}% | profit factor {pf:.2f}"
    )

    if verbose:
        out("\nTrade log:")
        out(
            f"{'date':<11} {'bias IST':<7} {'entry IST':<7} {'side':<6} {'S entry':>9} "
            f"{'S exit':>9} {'exit':<10} {'price%':>7} {'R':>6} {'equity%':>8}"
        )
        for _, r in tdf.iterrows():
            out(
                f"{str(r['date']):<11} {r['bias_ts'].strftime('%H:%M'):<7} "
                f"{r['entry_ts'].strftime('%H:%M'):<7} {r['side']:<6} "
                f"{r['entry_price']:>9.2f} {r['exit_price']:>9.2f} {r['reason']:<10} "
                f"{r['price_ret_pct']:>+7.2f} {r['r_multiple']:>+6.2f} {r['equity_pct']:>+8.2f}"
            )

    return {
        "n": len(tdf),
        "wr": wr,
        "net_r": float(tdf["r_multiple"].sum()),
        "pf": pf,
        "exits": tdf["reason"].value_counts().to_dict(),
    }


def run(run_id: str, margin: float = MARGIN_PCT, anchor: time = ANCHOR_SESSION,
        verbose: bool = True):
    cfg = RUNS[run_id]
    out = print if verbose else (lambda *a, **k: None)
    interval_min = 5 if cfg["interval"] == "5m" else 60

    out("=" * 100)
    out(f"RUN {run_id}: {cfg['label']} | {cfg['interval']} bars | anchor "
        f"{anchor.strftime('%H:%M')} IST")
    out(
        f"ORB {ORB_START:%H:%M}-{ORB_END:%H:%M} IST | bias = close beyond ORB | "
        f"entry = {cfg['interval']} VWAP cross same direction "
        f"(cutoff {ENTRY_CUTOFF:%H:%M}) | "
        f"SL -{RISK_PCT:.0%}/TGT +{TARGET_PCT:.0%} of margin @ {margin:.0%} "
        f"({1/margin:.1f}x) => SL {RISK_PCT*margin:.2%}/TGT {TARGET_PCT*margin:.2%} of price | "
        f"square-off {SQUAREOFF:%H:%M}"
    )

    df = download(cfg["interval"], cfg["days"])
    if len(df) < 100:
        out(f"Insufficient data: {len(df)} bars")
        return None
    out(
        f"Data: {len(df)} bars | {df.index.min():%Y-%m-%d %H:%M} to "
        f"{df.index.max():%Y-%m-%d %H:%M} IST"
        + (f" | source: {LOCAL_CSV}" if LOCAL_CSV else "")
    )

    trades, day_stats = backtest(df, interval_min, anchor, margin)
    return summarize(trades, day_stats, verbose=verbose)


def sweep(run_id: str, anchor: time = ANCHOR_SESSION):
    cfg = RUNS[run_id]
    print(
        f"\nRun {run_id} sensitivity — margin ({cfg['label']}, "
        f"anchor {anchor:%H:%M}):"
    )
    print(
        f"  {'margin':>7} {'lev':>5} {'SL px':>7} {'TGT px':>7} {'trades':>6} "
        f"{'win%':>6} {'net R':>8} {'PF':>6}  exits"
    )
    for m in MARGIN_SWEEP:
        s = run(run_id, margin=m, anchor=anchor, verbose=False)
        if s:
            ex = s["exits"]
            ex_str = " ".join(f"{k}:{ex.get(k, 0)}" for k in ("SL", "TARGET", "SQUAREOFF"))
            print(
                f"  {m:>6.0%} {1/m:>4.1f}x {RISK_PCT*m:>6.2%} {TARGET_PCT*m:>6.2%} "
                f"{s['n']:>6} {s['wr']:>6.1f} {s['net_r']:>8.2f} {s['pf']:>6.2f}  {ex_str}"
            )


if __name__ == "__main__":
    print("\U0001F48E SILVER ORB-BIAS + VWAP-CROSS FUTURES STRATEGY")
    print("Bias from 18:00-18:30 IST ORB; entry on 5m VWAP cross in the same direction")

    full = run("A")  # exact spec, 60 days, default margin/anchor
    sweep("A")
    print("\n" + "-" * 100)
    anchor_alt = run("A", anchor=ANCHOR_DAY)  # calendar-day VWAP anchor, full report
    sweep("A", anchor=ANCHOR_DAY)

    lens = run("B")  # 3-month coarse lens
    sweep("B")
    anchor_alt_b = run("B", anchor=ANCHOR_DAY, verbose=False)
    if anchor_alt_b:
        print(
            f"\nRun B, calendar-day VWAP anchor @ {MARGIN_PCT:.0%} margin: "
            f"{anchor_alt_b['n']} trades | WR {anchor_alt_b['wr']:.1f}% | "
            f"net {anchor_alt_b['net_r']:+.2f}R | PF {anchor_alt_b['pf']:.2f}"
        )
