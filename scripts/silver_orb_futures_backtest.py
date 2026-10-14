"""
Silver ORB — FUTURES edition: same signal as silver_orb_backtest.py, futures P&L.

Spec (identical signal logic to the options backtest):
  - ORB = high/low of the 18:00-18:30 IST 5m candles.
  - After 18:30 IST the first 5m close above ORB high goes LONG futures; close below
    ORB low goes SHORT futures. One trade per day, entries until 21:00 IST,
    square-off at 23:00 IST.

Risk/target interpretation: 7% risk / 25% reward measured on the MARGIN deployed —
the direct analogue of "% of premium paid" in the options version — with an assumed
broker margin of MARGIN_PCT (10% default => 10x leverage):
    sl_dist  = RISK_PCT  x margin x entry_price   (0.70% of price @ 10% margin)
    tgt_dist = TARGET_PCT x margin x entry_price  (2.50% of price @ 10% margin)
The sweep also covers other margin assumptions, including 100% which corresponds to
the literal price-space reading of 7%/25% (no leverage: sl -7% / tgt +25% of price).

Unlike the options version, futures need NO option-premium modelling (no delta/IV
proxy) — outcomes are real price moves. Underlying data: CME silver futures (SI=F)
as a proxy for MCX silver; % moves are comparable, absolute levels are not.
Costs (spread/slippage/swap) are not modelled.
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
# argv[1]: either a local OHLCV CSV path (e.g. data/mcx_silver_5m_95d.csv from
# scripts/shoonya_mcx_pull.py) or a yfinance period like "1mo" / "60d".
LOCAL_CSV = (sys.argv[1] if len(sys.argv) > 1
             and sys.argv[1].lower().endswith(".csv") else None)
PERIOD = "mcx" if LOCAL_CSV else (sys.argv[1] if len(sys.argv) > 1 else "1mo")
INTERVAL = "5m"

ORB_START = time(18, 0)  # IST
ORB_END = time(18, 30)  # IST, exclusive — 5 candles of range
ENTRY_CUTOFF = time(21, 0)  # IST, no fresh entries after this
SQUAREOFF = time(23, 0)  # IST, flat by this time

RISK_PCT = 0.07  # 7% of margin at risk
TARGET_PCT = 0.25  # 25% of margin target
MARGIN_PCT = 0.10  # assumed MCX futures margin (=> 10x leverage)
MARGIN_SWEEP = (0.05, 0.10, 0.20, 1.00)  # sensitivity: incl. 100% = pure price 7/25

MIN_ORB_RANGE_PCT = 0.05  # skip dead/near-flat opening ranges
RISK_PER_TRADE_CAPITAL = 0.01  # equity curve assumes 1% of capital risked per trade

IST = "Asia/Kolkata"
OUT_CSV = f"scripts/silver_orb_futures_trades_{PERIOD}.csv"

# Options backtest baseline (silver_orb_backtest.py, DTE=10 row, same 1-month window):
OPTIONS_BASELINE = {"n": 21, "wr": 19.0, "net_r": -8.58, "pf": 0.50}

_DF_CACHE: pd.DataFrame = None  # download once, reuse across margin runs


# ----------------------------- data -----------------------------
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


def download() -> pd.DataFrame:
    global _DF_CACHE
    if _DF_CACHE is not None:
        return _DF_CACHE
    if LOCAL_CSV:
        _DF_CACHE = _load_local_csv(INTERVAL)
        return _DF_CACHE
    if PERIOD.endswith("d") and PERIOD[:-1].isdigit():
        start = (
            pd.Timestamp.today().normalize() - pd.Timedelta(days=int(PERIOD[:-1]) - 1)
        ).strftime("%Y-%m-%d")
        df = yf.download(TICKER, start=start, interval=INTERVAL, progress=False)
    else:
        df = yf.download(TICKER, period=PERIOD, interval=INTERVAL, progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna(subset=["Close"]).copy()
    if df.index.tz is None:
        df.index = df.index.tz_localize("UTC")
    df.index = df.index.tz_convert(IST)
    df = df.sort_index()
    _DF_CACHE = df
    return df


@dataclass
class Trade:
    date: object
    entry_ts: pd.Timestamp
    side: str  # LONG / SHORT
    entry_price: float
    margin_pct: float
    exit_ts: pd.Timestamp = None
    exit_price: float = 0.0
    reason: str = ""
    points: float = 0.0  # price points gained (signed by direction)
    price_ret_pct: float = 0.0  # % of notional
    margin_ret_pct: float = 0.0  # % of margin deployed (== options' premium %)
    r_multiple: float = 0.0

    def settle(self, exit_ts, exit_price, reason):
        self.exit_ts = exit_ts
        self.exit_price = exit_price
        self.reason = reason
        sign = 1.0 if self.side == "LONG" else -1.0
        self.points = sign * (exit_price - self.entry_price)
        self.price_ret_pct = self.points / self.entry_price * 100.0
        self.margin_ret_pct = self.price_ret_pct / self.margin_pct  # leverage
        self.r_multiple = self.margin_ret_pct / (RISK_PCT * 100.0)


def backtest(df: pd.DataFrame, margin: float) -> list:
    """Run the ORB signal + margin-scaled stops. Entries are identical across margin
    assumptions (one trade/day, exits never feed back into entries)."""
    dates = pd.unique(df.index.date)
    trades = []

    for d in dates:
        day = df[df.index.date == d]

        orb = day[(day.index.time >= ORB_START) & (day.index.time < ORB_END)]
        if len(orb) < 4:  # tolerate one missing bar; skip truncated ranges
            continue
        orb_high, orb_low = float(orb["High"].max()), float(orb["Low"].min())
        if (orb_high - orb_low) / orb_low * 100.0 < MIN_ORB_RANGE_PCT:
            continue

        session = day[(day.index.time >= ORB_END) & (day.index.time <= SQUAREOFF)]
        if session.empty:
            continue

        trade: Trade = None
        for ts, bar in session.iterrows():
            c, lo, hi = float(bar["Close"]), float(bar["Low"]), float(bar["High"])
            now = ts.time()

            if trade is None:
                if now >= ENTRY_CUTOFF:
                    continue  # entries closed; exits still evaluated below
                side = None
                if c > orb_high:
                    side = "LONG"
                elif c < orb_low:
                    side = "SHORT"
                if side:
                    trade = Trade(
                        date=d,
                        entry_ts=ts,
                        side=side,
                        entry_price=c,
                        margin_pct=margin,
                    )
                    continue  # exits start from the NEXT bar
                continue

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
            break  # one ORB trade per day — no re-entries after an exit

        if trade is not None:  # day ran out before square-off time
            last_ts, last_close = session.index[-1], float(session["Close"].iloc[-1])
            trade.settle(last_ts, last_close, "SQUAREOFF")
            trades.append(trade)

    return trades


def summarize(trades: list, margin: float, verbose: bool = True):
    out = print if verbose else (lambda *a, **k: None)
    if not trades:
        out("\nNo trades generated.")
        return None

    tdf = pd.DataFrame([vars(t) for t in trades])
    wr = 100.0 * (tdf["margin_ret_pct"] > 0).mean()
    out(
        f"\nTrades: {len(tdf)} over {tdf['date'].nunique()} days "
        f"(max 1 per day) | win rate {wr:.1f}%"
    )
    out(
        f"Net: {tdf['margin_ret_pct'].sum():+.2f}% of margin traded | "
        f"sum R: {tdf['r_multiple'].sum():+.2f}R | "
        f"avg R: {tdf['r_multiple'].mean():+.2f}R | "
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
            f"{'date':<11} {'entry IST':<7} {'side':<6} {'S entry':>9} {'S exit':>9} "
            f"{'exit':<10} {'price%':>7} {'R':>6} {'equity%':>8}"
        )
        for _, r in tdf.iterrows():
            out(
                f"{str(r['date']):<11} {r['entry_ts'].strftime('%H:%M'):<7} {r['side']:<6} "
                f"{r['entry_price']:>9.2f} {r['exit_price']:>9.2f} {r['reason']:<10} "
                f"{r['price_ret_pct']:>+7.2f} {r['r_multiple']:>+6.2f} {r['equity_pct']:>+8.2f}"
            )
        tdf.to_csv(OUT_CSV, index=False)
        out(f"\nTrades written to {OUT_CSV}")

    return {
        "n": len(tdf),
        "wr": wr,
        "net_r": float(tdf["r_multiple"].sum()),
        "pf": pf,
        "exits": tdf["reason"].value_counts().to_dict(),
    }


def run(margin: float = MARGIN_PCT, verbose: bool = True) -> dict:
    out = print if verbose else (lambda *a, **k: None)
    out("\U0001F48E SILVER ORB — FUTURES EDITION — "
        f"{PERIOD.upper()} 5M BACKTEST ({'local MCX CSV' if LOCAL_CSV else 'yfinance SI=F'})")
    out("=" * 100)
    out(
        f"ORB window {ORB_START.strftime('%H:%M')}-{ORB_END.strftime('%H:%M')} IST | "
        f"entry on 5m close beyond ORB (cutoff {ENTRY_CUTOFF.strftime('%H:%M')}) | "
        f"SL -{RISK_PCT:.0%} / TGT +{TARGET_PCT:.0%} of margin @ {margin:.0%} margin "
        f"({1/margin:.1f}x) => SL {RISK_PCT*margin:.2%} / TGT {TARGET_PCT*margin:.2%} of price | "
        f"square-off {SQUAREOFF.strftime('%H:%M')} IST"
    )

    df = download()
    if len(df) < 100:
        out(f"Insufficient data for {TICKER}: {len(df)} bars — aborting.")
        return None
    out(
        f"Data: {len(df)} bars | {df.index.min():%Y-%m-%d %H:%M} to "
        f"{df.index.max():%Y-%m-%d %H:%M} IST"
    )

    trades = backtest(df, margin)
    dates = pd.unique(df.index.date)
    out(
        f"\nDays: {len(dates)} | trades: {len(trades)} "
        f"(1 per valid-ORB day with a signal)"
    )
    return summarize(trades, margin, verbose=verbose)


if __name__ == "__main__":
    summary = run()
    if summary:
        be = RISK_PCT / (RISK_PCT + TARGET_PCT) * 100.0
        print(
            f"\nBreakeven win rate for {RISK_PCT:.0%}:{TARGET_PCT:.0%} R:R = {be:.1f}% "
            f"vs actual {summary['wr']:.1f}% over {summary['n']} trades"
        )
        print("\nSensitivity — margin assumption (sets SL/TGT width in price points):")
        print(
            f"  {'margin':>7} {'lev':>5} {'SL px':>7} {'TGT px':>7} {'trades':>6} "
            f"{'win%':>6} {'net R':>8} {'PF':>6}  exits"
        )
        for m in MARGIN_SWEEP:
            s = run(margin=m, verbose=False)
            if s:
                ex = s["exits"]
                ex_str = " ".join(
                    f"{k}:{ex.get(k, 0)}" for k in ("SL", "TARGET", "SQUAREOFF")
                )
                print(
                    f"  {m:>6.0%} {1/m:>4.1f}x {RISK_PCT*m:>6.2%} {TARGET_PCT*m:>6.2%} "
                    f"{s['n']:>6} {s['wr']:>6.1f} {s['net_r']:>8.2f} {s['pf']:>6.2f}  {ex_str}"
                )

        print("\nvs options backtest (same signals, delta-proxy premium, 10 DTE):")
        print(
            f"  options: {OPTIONS_BASELINE['n']} trades | WR {OPTIONS_BASELINE['wr']:.1f}% | "
            f"net {OPTIONS_BASELINE['net_r']:+.2f}R | PF {OPTIONS_BASELINE['pf']:.2f}"
        )
        print(
            f"  futures @ {MARGIN_PCT:.0%} margin: {summary['n']} trades | "
            f"WR {summary['wr']:.1f}% | net {summary['net_r']:+.2f}R | PF {summary['pf']:.2f}"
        )
