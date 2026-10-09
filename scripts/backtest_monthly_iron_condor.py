"""Monthly NIFTY Iron Condor - 2% target on full capital.

Reproduces the backtest in monthly_nifty_iron_condor_spec.md section 8.

Structure : SELL ATM+400 CE / ATM-400 PE, BUY ATM+900 CE / ATM-900 PE
Lots      : 7 (70% of Rs10,00,000 deployed as margin, 30% held free)
Exits     : +Rs65,000 target | -Rs50,000 stop | 3.0% re-centre (max 1) | expiry

Usage: python scripts/backtest_monthly_iron_condor.py
"""

import sys
import io
import math

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

import yfinance as yf
import pandas as pd

CAPITAL = 1_000_000.0
LOT_SIZE = 75
LOTS = 7
MARGIN_PER_LOT = 100_000.0
TARGET_RS = 65_000.0
STOP_RS = 50_000.0
RECENTER_PCT = 0.03
MAX_ADJ = 1
SHORT_OFFSET = 400
WING_OFFSET = 900
COST_PER_LOT = 110.0
RISK_FREE = 0.065
TUE = 1  # NSE index expiry moved Thu -> Tue effective 01-Sep-2025
THU = 3


def norm_cdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bs_price(spot, strike, t_years, sigma, option_type="CE"):
    if t_years <= 0.0001:
        if option_type == "CE":
            return max(0.0, spot - strike)
        return max(0.0, strike - spot)
    sigma = max(0.05, sigma)
    d1 = (math.log(spot / strike) + (RISK_FREE + 0.5 * sigma**2) * t_years) / (
        sigma * math.sqrt(t_years)
    )
    d2 = d1 - sigma * math.sqrt(t_years)
    if option_type == "CE":
        return spot * norm_cdf(d1) - strike * math.exp(-RISK_FREE * t_years) * norm_cdf(d2)
    return strike * math.exp(-RISK_FREE * t_years) * norm_cdf(-d2) - spot * norm_cdf(-d1)


def load_data():
    df = yf.download("^NSEI", period="4y", interval="1d", progress=False)
    vix = yf.download("^INDIAVIX", period="4y", interval="1d", progress=False)
    for frame in (df, vix):
        if isinstance(frame.columns, pd.MultiIndex):
            frame.columns = frame.columns.get_level_values(0)
    df["VIX"] = vix["Close"].ffill().bfill()
    return df.dropna()


def find_monthly_expiries(df):
    """Last Tuesday from Sep-2025 onwards, last Thursday before that."""
    expiries = []
    for ym, group in df.groupby(df.index.to_period("M")):
        weekday = TUE if ym.start_time >= pd.Timestamp("2025-09-01") else THU
        thursday_tuesday = group[group.index.dayofweek == weekday]
        if len(thursday_tuesday) > 0:
            expiries.append(thursday_tuesday.index[-1])
        else:
            expiries.append(group.index[-1])
    return expiries


def build_condor(spot):
    atm = int(round(spot / 100.0) * 100)
    return [
        (atm + SHORT_OFFSET, "CE", 1),   # short call
        (atm - SHORT_OFFSET, "PE", 1),   # short put
        (atm + WING_OFFSET, "CE", -1),   # long call wing
        (atm - WING_OFFSET, "PE", -1),   # long put wing
    ]


def position_value(legs, spot, t_years, sigma):
    """Net cost to close the position, in points per lot. sign +1 = short leg."""
    return sum(sign * bs_price(spot, strike, t_years, sigma, kind) for strike, kind, sign in legs)


def expiry_settlement(legs, spot):
    """Intrinsic value of closing the position at expiry, in points per lot."""
    return sum(
        sign * (max(0.0, spot - strike) if kind == "CE" else max(0.0, strike - spot))
        for strike, kind, sign in legs
    )


def run_backtest():
    df = load_data()
    expiries = find_monthly_expiries(df)
    qty = LOTS * LOT_SIZE
    results = []

    for i in range(1, len(expiries)):
        prev_exp, curr_exp = expiries[i - 1], expiries[i]
        cycle = df[(df.index > prev_exp) & (df.index <= curr_exp)]
        if len(cycle) < 5:
            continue

        entry_date = cycle.index[0]
        entry_spot = float(cycle.loc[entry_date, "Close"])
        entry_iv = float(cycle.loc[entry_date, "VIX"]) / 100.0
        entry_t = max((curr_exp - entry_date).days, 1) / 365.0

        legs = build_condor(entry_spot)
        credit = position_value(legs, entry_spot, entry_t, entry_iv)

        booked_pts = 0.0   # realised P&L of closed structures, in points per lot
        spot_ref = entry_spot
        adjustments = 0
        exit_reason = "Expiry"
        exit_date = curr_exp
        final_pnl = 0.0
        max_dd = 0.0

        for date, row in cycle.iterrows():
            spot = float(row["Close"])
            iv = float(row["VIX"]) / 100.0
            t_left = max((curr_exp - date).days, 0) / 365.0

            open_pts = credit - position_value(legs, spot, t_left, iv)
            total_mtm = (booked_pts + open_pts) * qty - COST_PER_LOT * LOTS
            max_dd = min(max_dd, total_mtm)

            if total_mtm >= TARGET_RS:
                exit_reason, exit_date, final_pnl = "Target", date, total_mtm
                break
            if total_mtm <= -STOP_RS:
                exit_reason, exit_date, final_pnl = "StopLoss", date, total_mtm
                break

            drift = abs(spot - spot_ref) / spot_ref
            if drift >= RECENTER_PCT and adjustments < MAX_ADJ:
                booked_pts += open_pts - COST_PER_LOT / LOT_SIZE
                legs = build_condor(spot)
                credit = position_value(legs, spot, t_left, iv)
                spot_ref = spot
                adjustments += 1
        else:
            settle_spot = float(cycle.iloc[-1]["Close"])
            final_pnl = (booked_pts + credit - expiry_settlement(legs, settle_spot)) * qty - (
                COST_PER_LOT * LOTS
            )
            max_dd = min(max_dd, final_pnl)

        nifty_move = round(
            ((float(cycle.iloc[-1]["Close"]) - entry_spot) / entry_spot) * 100.0, 2
        )
        results.append(
            {
                "Month": curr_exp.strftime("%b%y"),
                "Nifty_Move_%": nifty_move,
                "Entry_Spot": round(entry_spot),
                "Strikes": f"{build_condor(entry_spot)[3][0]}/{build_condor(entry_spot)[1][0]}"
                f"/{build_condor(entry_spot)[0][0]}/{build_condor(entry_spot)[2][0]} CE/PE",
                "Adjusted": adjustments,
                "Exit": exit_reason,
                "PnL_Rs": round(final_pnl),
                "ROI_%": round(final_pnl / CAPITAL * 100.0, 2),
                "MaxDD_Rs": round(max_dd),
                "Exit_Date": pd.Timestamp(exit_date).strftime("%Y-%m-%d"),
            }
        )

    return pd.DataFrame(results)


def summarise(results):
    pnl = results["PnL_Rs"]
    cumulative = pnl.cumsum()
    max_dd = float((cumulative.cummax() - cumulative).max())
    print("\n" + "=" * 108)
    print(
        f" MONTHLY NIFTY IRON CONDOR | {LOTS} lots | target +Rs{TARGET_RS:,.0f} "
        f"| stop -Rs{STOP_RS:,.0f} | re-centre {RECENTER_PCT:.1%}"
    )
    print("=" * 108)
    print(f" Cycles            : {len(results)}")
    print(f" Total net P&L     : Rs{pnl.sum():,.0f}  ({pnl.sum() / CAPITAL * 100:.1f}% of capital)")
    print(f" Average per month : Rs{pnl.mean():,.0f}  ({pnl.mean() / CAPITAL * 100:.2f}% of capital)")
    print(f" Winning months    : {(pnl > 0).sum()} / {len(results)} ({(pnl > 0).mean() * 100:.1f}%)")
    print(f" Target exits      : {(results['Exit'] == 'Target').sum()} / {len(results)}")
    print(f" Stop-loss exits   : {(results['Exit'] == 'StopLoss').sum()} / {len(results)}")
    print(f" Expiry hold-outs  : {(results['Exit'] == 'Expiry').sum()} / {len(results)}")
    print(f" Re-centres used   : {results['Adjusted'].sum()} across {len(results)} cycles")
    print(f" Worst cycle       : Rs{pnl.min():,.0f}  ({pnl.min() / CAPITAL * 100:.2f}%)")
    print(f" Max drawdown      : Rs{max_dd:,.0f}  ({max_dd / CAPITAL * 100:.2f}%)")
    print(f" Margin deployed   : Rs{LOTS * MARGIN_PER_LOT:,.0f} "
          f"({LOTS * MARGIN_PER_LOT / CAPITAL * 100:.0f}% of capital)")
    print(f" Free buffer       : Rs{CAPITAL - LOTS * MARGIN_PER_LOT:,.0f} "
          f"({(CAPITAL - LOTS * MARGIN_PER_LOT) / CAPITAL * 100:.0f}% of capital)")
    print("=" * 108)


if __name__ == "__main__":
    table = run_backtest()
    print("=== MONTHLY NIFTY IRON CONDOR (47-MONTH BACKTEST) ===")
    print(table.to_string(index=False))
    summarise(table)
