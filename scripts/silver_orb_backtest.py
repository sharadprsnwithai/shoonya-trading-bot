"""
Silver ORB Option-Buying Strategy — 1-month backtest on yfinance data.

Spec:
  - Mark the Opening Range (ORB) from the 18:00-18:30 IST 5-minute candles (silver
    evening-session open range on MCX).
  - After 18:30 IST, the first 5m candle whose CLOSE breaks above the ORB high buys the
    ATM CALL; a close below the ORB low buys the ATM PUT.
  - Stop-loss at -7% and target at +25% of the OPTION PREMIUM; positions are squared
    off at 23:00 IST if neither is hit.

IMPORTANT — premium modelling: yfinance does not expose historical option chains, so the
backtest cannot use real premiums. It estimates the ATM premium from trailing realized
volatility (Black-Scholes ATM approx: P0 ~ 0.4 * S * IV * sqrt(T)) and translates the
-7%/+25% premium stops into equivalent underlying moves using the ATM delta (~0.5):
    dS_stop   = -0.07 * P0 / delta
    dS_target = +0.25 * P0 / delta
Premium is then assumed to move linearly with the underlying at constant delta (gamma
and theta are not modelled; over a few intraday hours both are second-order). Underlying
data is CME silver futures (SI=F) as a proxy for MCX silver — % moves are comparable,
absolute levels are not.
"""

import sys
import io
import math
from dataclasses import dataclass
from datetime import time

import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

# ----------------------------- parameters -----------------------------
TICKER = "SI=F"
PERIOD = "1mo"  # last 1 month of 5m data
INTERVAL = "5m"

ORB_START = time(18, 0)  # IST
ORB_END = time(18, 30)  # IST, exclusive — 5 candles of range
ENTRY_CUTOFF = time(21, 0)  # IST, no fresh entries after this
SQUAREOFF = time(23, 0)  # IST, flat by this time

RISK_PCT = 0.07  # premium stop: -7%
TARGET_PCT = 0.25  # premium target: +25%
DTE = 10  # assumed days-to-expiry for the ATM option (sets premium scale)
IV_FLOOR, IV_CAP = 0.10, 1.00  # sanity bounds on the realized-IV estimate
IV_BARS = 550  # ~2 trailing days of 5m bars for realized vol
BARS_PER_YEAR = 69552.0  # ~276 5m bars/day (23h session) x 252 sessions
MIN_ORB_RANGE_PCT = 0.05  # skip dead/near-flat opening ranges
RISK_PER_TRADE_CAPITAL = 0.01  # equity curve assumes 1% of capital risked per trade

IST = "Asia/Kolkata"
OUT_CSV = "scripts/silver_orb_trades.csv"
_DF_CACHE: pd.DataFrame = None  # download once, reuse across sensitivity runs


# ----------------------------- helpers -----------------------------
def norm_cdf(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def download() -> pd.DataFrame:
    global _DF_CACHE
    if _DF_CACHE is not None:
        return _DF_CACHE
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
    side: str  # CALL / PUT
    entry_price: float
    atm_premium: float
    delta: float
    exit_ts: pd.Timestamp = None
    exit_price: float = 0.0
    reason: str = ""
    points: float = 0.0
    premium_pct: float = 0.0  # % change of option premium
    r_multiple: float = 0.0

    def settle(self, exit_ts, exit_price, reason):
        self.exit_ts = exit_ts
        self.exit_price = exit_price
        self.reason = reason
        sign = 1.0 if self.side == "CALL" else -1.0
        self.points = sign * (exit_price - self.entry_price)
        self.premium_pct = self.delta * self.points / self.atm_premium * 100.0
        self.r_multiple = self.premium_pct / (RISK_PCT * 100.0)


def run(dte: float = DTE, verbose: bool = True):
    out = print if verbose else (lambda *a, **k: None)
    out("\U0001F48E SILVER ORB OPTION-BUYING STRATEGY — 1-MONTH 5M BACKTEST (yfinance SI=F)")
    out("=" * 100)
    out(
        f"ORB window {ORB_START.strftime('%H:%M')}-{ORB_END.strftime('%H:%M')} IST | "
        f"entry on 5m close beyond ORB (cutoff {ENTRY_CUTOFF.strftime('%H:%M')}) | "
        f"SL -{RISK_PCT:.0%} / TGT +{TARGET_PCT:.0%} of premium | "
        f"square-off {SQUAREOFF.strftime('%H:%M')} IST"
    )

    df = download()
    if len(df) < 100:
        out(f"Insufficient data for {TICKER}: {len(df)} bars — aborting.")
        return None
    out(
        f"Data: {len(df)} bars | {df.index.min():%Y-%m-%d %H:%M} to {df.index.max():%Y-%m-%d %H:%M} IST"
    )

    # Trailing realized IV on 5m log returns -> annualized (used only for premium scale).
    # min_periods avoids the warm-up gap: early bars estimate IV from whatever trailing
    # data exists instead of falling back to the floor.
    log_ret = np.log(df["Close"] / df["Close"].shift(1))
    iv = log_ret.rolling(IV_BARS, min_periods=100).std() * math.sqrt(BARS_PER_YEAR)
    iv = iv.clip(IV_FLOOR, IV_CAP)
    df["IV"] = iv

    dates = pd.unique(df.index.date)
    trades: list[Trade] = []
    skipped = {"no_orb_data": 0, "flat_orb": 0}
    orb_ranges = []

    for d in dates:
        day = df[df.index.date == d]

        orb = day[(day.index.time >= ORB_START) & (day.index.time < ORB_END)]
        if len(orb) < 4:  # tolerate one missing bar; skip truncated ranges
            skipped["no_orb_data"] += 1
            continue
        orb_high, orb_low = float(orb["High"].max()), float(orb["Low"].min())
        rng_pct = (orb_high - orb_low) / orb_low * 100.0
        if rng_pct < MIN_ORB_RANGE_PCT:
            skipped["flat_orb"] += 1
            continue
        orb_ranges.append(rng_pct)

        session = day[
            (day.index.time >= ORB_END) & (day.index.time <= SQUAREOFF)
        ]  # bars eligible for entries/exits
        if session.empty:
            skipped["no_orb_data"] += 1
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
                    side = "CALL"
                elif c < orb_low:
                    side = "PUT"
                if side:
                    iv_bar = bar["IV"]
                    if pd.isna(iv_bar):
                        iv_bar = IV_FLOOR
                    T = dte / 365.0
                    delta = norm_cdf(0.5 * iv_bar * math.sqrt(T))  # ATM N(d1) ~ 0.5
                    p0 = 0.4 * c * iv_bar * math.sqrt(T)  # BS ATM premium approximation
                    trade = Trade(
                        date=d,
                        entry_ts=ts,
                        side=side,
                        entry_price=c,
                        atm_premium=p0,
                        delta=delta,
                    )
                    continue  # exits start from the NEXT bar

                # flat and no signal: still allow nothing else
                continue

            # ---- manage open position ----
            sl_dist = RISK_PCT * trade.atm_premium / trade.delta
            tgt_dist = TARGET_PCT * trade.atm_premium / trade.delta
            if trade.side == "CALL":
                sl, tgt = trade.entry_price - sl_dist, trade.entry_price + tgt_dist
                hit_sl = lo <= sl  # conservative: SL checked before target
                hit_tgt = hi >= tgt
            else:
                sl, tgt = trade.entry_price + sl_dist, trade.entry_price - tgt_dist
                hit_sl = hi >= sl
                hit_tgt = lo <= tgt

            if hit_sl:
                trade.settle(ts, sl, "SL")
                trades.append(trade)
                trade = None
                break  # one ORB trade per day — no re-entries after an exit
            elif hit_tgt:
                trade.settle(ts, tgt, "TARGET")
                trades.append(trade)
                trade = None
                break
            elif now >= SQUAREOFF:
                trade.settle(ts, c, "SQUAREOFF")
                trades.append(trade)
                trade = None
                break

        if trade is not None:  # day ran out before square-off time
            last_ts, last_close = session.index[-1], float(session["Close"].iloc[-1])
            trade.settle(last_ts, last_close, "SQUAREOFF")
            trades.append(trade)

    # ----------------------------- report -----------------------------
    out(f"\nDays: {len(dates)} | valid ORB days: {len(orb_ranges)} | "
          f"skipped (no data): {skipped['no_orb_data']} | skipped (flat ORB): {skipped['flat_orb']}")
    if orb_ranges:
        out(
            f"ORB range: avg {np.mean(orb_ranges):.2f}% | median {np.median(orb_ranges):.2f}%"
        )
    if not trades:
        out("\nNo trades generated.")
        return None

    tdf = pd.DataFrame([vars(t) for t in trades])
    out(f"\nTrades: {len(tdf)} over {tdf['date'].nunique()} days "
          f"(max 1 per day) | win rate {100.0 * (tdf['premium_pct'] > 0).mean():.1f}%")
    out(
        f"Net: {tdf['premium_pct'].sum():+.2f}% of premium traded | "
        f"sum R: {tdf['r_multiple'].sum():+.2f}R | "
        f"avg R: {tdf['r_multiple'].mean():+.2f}R | "
        f"avg premium %/trade: {tdf['premium_pct'].mean():+.2f}%"
    )
    out("\nBy side:")
    for side, g in tdf.groupby("side"):
        out(
            f"  {side:<5} n={len(g):<3} wins={int((g['premium_pct'] > 0).sum()):<3} "
            f"net {g['premium_pct'].sum():+7.2f}% | {g['r_multiple'].sum():+6.2f}R"
        )
    out("\nBy exit reason:")
    for reason, g in tdf.groupby("reason"):
        out(
            f"  {reason:<10} n={len(g):<3} avg {g['premium_pct'].mean():+7.2f}% | "
            f"{g['r_multiple'].sum():+6.2f}R"
        )

    # Equity curve at 1% capital risk per trade (R -> % of capital)
    tdf = tdf.reset_index(drop=True)
    tdf["equity_r"] = tdf["r_multiple"].cumsum()
    tdf["equity_pct"] = tdf["equity_r"] * RISK_PER_TRADE_CAPITAL * 100.0
    peak = tdf["equity_pct"].cummax()
    max_dd = (tdf["equity_pct"] - peak).min()
    out(
        f"\nEquity (1% risk/trade): final {tdf['equity_pct'].iloc[-1]:+.2f}% | "
        f"max drawdown {max_dd:.2f}% | profit factor "
        f"{tdf.loc[tdf['premium_pct'] > 0, 'premium_pct'].sum() / max(1e-9, -tdf.loc[tdf['premium_pct'] < 0, 'premium_pct'].sum()):.2f}"
    )

    out("\nTrade log:")
    out(
        f"{'date':<11} {'entry IST':<7} {'side':<5} {'S entry':>9} {'S exit':>9} "
        f"{'exit':<10} {'prem%':>7} {'R':>6} {'equity%':>8}"
    )
    for _, r in tdf.iterrows():
        out(
            f"{str(r['date']):<11} {r['entry_ts'].strftime('%H:%M'):<7} {r['side']:<5} "
            f"{r['entry_price']:>9.2f} {r['exit_price']:>9.2f} {r['reason']:<10} "
            f"{r['premium_pct']:>+7.2f} {r['r_multiple']:>+6.2f} {r['equity_pct']:>+8.2f}"
        )

    if verbose:
        tdf.to_csv(OUT_CSV, index=False)
        out(f"\nTrades written to {OUT_CSV}")
    out("\nCaveats: option premium is a delta-proxy (no gamma/theta/slippage), SI=F stands in")
    out("for MCX silver, and 1 month is a very small sample. Validate on MCX data next.")

    gross_win = tdf.loc[tdf["premium_pct"] > 0, "premium_pct"].sum()
    gross_loss = -tdf.loc[tdf["premium_pct"] < 0, "premium_pct"].sum()
    return {
        "n": len(tdf),
        "wr": 100.0 * (tdf["premium_pct"] > 0).mean(),
        "net_r": float(tdf["r_multiple"].sum()),
        "pf": gross_win / max(1e-9, gross_loss),
    }


if __name__ == "__main__":
    summary = run()
    if summary:
        be = RISK_PCT / (RISK_PCT + TARGET_PCT) * 100.0
        print(
            f"\nBreakeven win rate for {RISK_PCT:.0%}:{TARGET_PCT:.0%} R:R = {be:.1f}% "
            f"vs actual {summary['wr']:.1f}% over {summary['n']} trades"
        )
        print("\nSensitivity — assumed DTE at entry (premium scale; sets SL/TGT width in points):")
        print(f"  {'DTE':<5} {'trades':>6} {'win%':>6} {'net R':>8} {'PF':>6}")
        for d in (7, 10, 15, 25):
            s = run(dte=d, verbose=False)
            if s:
                print(
                    f"  {d:<5} {s['n']:>6} {s['wr']:>6.1f} {s['net_r']:>8.2f} {s['pf']:>6.2f}"
                )
