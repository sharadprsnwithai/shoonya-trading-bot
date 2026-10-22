# Shoonya Algorithmic Trading Bot

> **Multi-Strategy Intraday & Positional Options Bot (NIFTY 50 + F&O Stocks)**  
> Built with Java 21, Spring Boot 3.3.5, and Finvasia Shoonya (NorenAPI) / Zerodha Kite Connect. Optimized for low-footprint VPS deployments.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Active Strategies](#active-strategies)
   - [Lowest Volume Reversal (LVR)](#lowest-volume-reversal-lvr)
   - [Cumulative Average Reversal (CAR) Weekly GTT](#cumulative-average-reversal-car-weekly-gtt)
   - [Monthly Option Range GARCH(1,1)](#monthly-option-range-garch11)
3. [Telegram Bot Commands](#telegram-bot-commands)
4. [Execution Modes](#execution-modes)
5. [Environment Configuration (.env)](#environment-configuration-env)
6. [Docker & Deployment Commands](#docker--deployment-commands)
   - [Quick Start with Docker Compose](#quick-start-with-docker-compose)
   - [Docker Build & Push Commands](#docker-build--push-commands)
   - [Container Operations & Maintenance](#container-operations--maintenance)
   - [Viewing Logs](#viewing-logs)
   - [Health & Monitoring](#health--monitoring)
7. [Local Development & Code Quality](#local-development--code-quality)
   - [Gradle Commands](#gradle-commands)
8. [REST API Endpoints](#rest-api-endpoints)

---

## Project Overview

The **Shoonya Trading Bot** is an automated and advisory trading engine designed for the Indian derivatives and equity markets (NSE/NFO/BSE).

- **Broker APIs:** Headless OAuth session manager for Finvasia Shoonya (`NorenAPI`) and Zerodha Kite Connect (`KiteRestClient`) with disk caching.
- **Telegram Integration:** Bidirectional command listener and rich notifications for trade entries, hedge purchases, trailing exits, weekly GTT triggers, and monthly GARCH range advisory reports.
- **Quantitative Engine:** Pure Java mathematical optimization (GARCH(1,1) Nelder-Mead MLE, multi-period variance forecasting, TA-Lib, TA4J).
- **Low Footprint:** Containerized runtime configured with serial GC (`-XX:+UseSerialGC -Xms256m -Xmx384m`) and resource caps (300 MB reservation, 600 MB max) for 1 GB RAM cloud VPS nodes.
- **Quality Assured:** Rigorous unit and integration testing, Spotless (AOSP standard), and SpotBugs static code analysis.

---

## Active Strategies

The bot runs automated strategies on the NIFTY 50 index and high-liquidity F&O equities:

### Lowest Volume Reversal (LVR)

Intraday stock futures and options momentum reversal on 5‑minute candles (`NSE` equities):

1. **Morning Universe Scan (09:25 IST, two-phase):** the 09:25:00 job fetches NSE's live OI spurts feed (`LVR_NSE_OI_*` keys); the 09:25:20 scan fixes the sector watchlist (top 3 of the winning sectoral index), then merges the top 3 OI-spurt stocks at scan completion — a 6-stock `HYBRID` watchlist (`OI_SPURTS` mode leads with OI picks and demotes sector picks to the reservoir). NSE fetch failures degrade to sector-only selection.
2. **Direction & Lowest Volume Candle (≥ 09:30 IST):** Strategy direction is determined by the 1st 5-minute candle (Green → LONG, Red → SHORT). Evaluates completed 5m candles after 09:30 AM to find the lowest volume opposite-color candle as the trigger.
3. **Entry Cutoff:** Hard cutoff at **14:30 IST (2:30 PM)** — no new setups or armed triggers after cutoff; open trades continue to target / stop-loss / trailing exit until 15:00 hard exit.
4. **Execution:** Futures or ATM options bought via decoupled execution consumers with 1:2 risk-reward, cost floor, and 10 EMA trailing.

---

### Intraday Double-Session Straddle (1 & 0 NS 916 TTC)

Non-directional clock-driven intraday option selling on NIFTY (Mon 1-DTE / Tue 0-DTE) and SENSEX (Wed 1-DTE):

1. **Schedule & Focus Days:**
   - **Monday:** NIFTY (1-DTE, Tuesday expiry) — 81.8% historical win rate.
   - **Tuesday:** NIFTY (0-DTE, same-day expiry).
   - **Wednesday:** SENSEX (1-DTE, Thursday expiry).
   - **Thursday:** SENSEX (0-DTE, same-day expiry).
   - *Friday:* Cash-protected (off).
2. **Execution Framework:**
   - **09:30 AM:** Buy 20-strikes OTM protection wings (`ATM ± 1,000 pts` on Nifty, `ATM ± 2,000 pts` on Sensex) to drop exchange margin from ~₹1.3L to ~₹50,000/lot.
   - **10:00 AM:** Sell ATM Call & ATM Put (1x CE, 1x PE) at 10:00 AM spot.
   - **30% Independent Stop Loss:** If either leg rises by 30%, exit that leg immediately. Surviving leg continues running. If other leg ALSO rises by 30% from entry, exit it as well.
   - **15:00 PM:** Hard square-off of surviving short leg and hedges (flat EOD, zero overnight risk).
3. **Institutional Armor:**
   - Hard daily loss kill-switch (default ₹8,000 on 10 lots) that automatically latches the strategy OFF on bad whipsaw days.
   - Telegram commands: `/straddle_status` for live positions and `/straddle_exit` for manual emergency square-off.

---

### Cumulative Average Reversal (CAR) Weekly GTT

Positional swing accumulation and profit extraction on NIFTY 100 universe:

1. **Schedule:** Evaluates weekly candles every Sunday at **10:00 AM IST**.
2. **Setup Detection:** Identifies 52-week high anchor points and calculates post-anchor cumulative average slope (requiring $\ge 10$ positive days).
3. **Trigger Placement:** Automatically arms GTT Buy orders at `Last Week High + Buffer` and GTT Sell targets (+6.28% target) using 1/40th portfolio unit sizing.

---

### Monthly Option Range GARCH(1,1)

Quantitative volatility forecasting and safe strike selection for monthly stock option sellers (`RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `TATAMOTORS`, `NIFTY50`):

1. **Schedule:** Runs every Wednesday at **10:00 AM IST**, automatically triggering post-expiry on the **last Wednesday of each month** (immediately following last-Tuesday monthly stock option expiry).
2. **Quantitative Engine:** Pure Java Nelder-Mead MLE optimizer fitting GARCH(1,1) conditional volatility ($\omega, \alpha, \beta$) over 2 years of daily returns.
3. **Multi-Step Projection:** Computes 22-trading-day forward cumulative monthly volatility $\sigma_{\text{month}}$ and annualized volatility $\sigma_{\text{ann}}$.
4. **Safe Strike Boundaries:**
   - **1-SD (68.3% Confidence Band):** $[S_0 \cdot e^{-\sigma_m}, \; S_0 \cdot e^{+\sigma_m}]$
   - **2-SD (95.4% Confidence Band):** $[S_0 \cdot e^{-2\sigma_m}, \; S_0 \cdot e^{+2\sigma_m}]$
   - **Safe PE Strike:** 2-SD lower price snapped down (floor) to official NSE strike step.
   - **Safe CE Strike:** 2-SD upper price snapped up (ceiling) to official NSE strike step.
5. **Advisory Alerts:** Dispatches formatted Telegram summary table with safe strikes, percentage safety buffers, ATR-22, and HV-30.

### Weekly / Daily Range Forecast (GJR-GARCH, calibrated)

Short-dated range forecasting for structures held to a single weekly expiry — the question a
weekly condor actually poses, as opposed to the 22-session monthly projection above.

1. **Schedule:** `08:00 IST` on the deployment weekday (Wednesday), so the band is in hand before
   the open while the full window is still deployable.
2. **Derived horizon, never hardcoded:** the session count from the last completed close to the
   contractual expiry resolved through the trading calendar. A Wednesday deployment reads as
   **5** sessions (Wed→Tue); the same endpoint called on a Monday correctly reads as **2**. A
   Dussehra-style holiday that rolls Tuesday expiry back to Monday shortens it to **4**.
3. **Spot reference is the last close**, not a live print — a forecast struck pre-open has no
   Wednesday candle to use.
4. **Bands at k = 1.0 / 1.5 / 2.0 σ**, each carrying *two* numbers:
   - **Nominal** — the Gaussian `2Φ(k) − 1` the band is assumed to represent.
   - **Measured** — the share of out-of-sample walk-forward windows whose **full high/low
     excursion** actually stayed inside it.
5. **Walk-forward calibration** fits parameters on the leading 60% of the series, then replays the
   volatility filter at each origin using only the returns observed up to that point. The gap
   between nominal and measured is the cost of being marked against an excursion rather than a
   close, and it is the figure to size against.
6. **GJR-GARCH alongside symmetric GARCH(1,1).** Both are fitted, both calibrated, and a verdict
   is reported. The primary lens is out-of-sample predictive log-likelihood — parameters refit on
   the training segment only, then scored on returns neither model saw — with AIC/BIC as a
   penalised cross-check and coverage error as the decision-relevant one. The leverage term `γ`
   lets negative shocks raise tomorrow's volatility more than equal positive shocks, which a
   symmetric model cannot express; `MIXED` is returned whenever the lenses disagree rather than
   forcing a winner. Disable with `MONTHLY_RANGE_GJR_COMPARISON_ENABLED=false` to report the
   symmetric bands alone.
7. **Wing-structure containment:** for a buy-far-wing / sell-two-near-wings / buy-far-wing
   structure the profit zone is bounded by `2·short − wing` **on both sides** (250 pts for the
   reference 150/200/350), plus the net credit — *not* by the short strike, which is where profit
   peaks rather than where it dies. Falling below the long wing keeps the credit, so the downside
   is not clipped at the wing offset. The zone is anchored to points, so it does not move with
   the volatility model; only the σ-bands do.
8. **Honest limits, stated in the payload:** both models see volatility clustering, not expiry-day
   pinning or the post-expiry IV reset a Wednesday entry sits on. The Gaussian figure covers the
   terminal return only and overstates hold-to-expiry probability.

| Endpoint | Description |
| :--- | :--- |
| `/api/v1/monthly-range/weekly/forecast` | Calibrated weekly range for every configured symbol. |
| `/api/v1/monthly-range/weekly/forecast/{symbol}` | Weekly range for a single symbol. |
| `/api/v1/monthly-range/weekly/run` | Run the routine and dispatch the Telegram alert. |

---

### NSE F&O 52-Week-High Breakout Scanner

Momentum breakout screen over the NSE F&O equity universe (indices excluded):

1. **Schedule:** Runs each trading morning at **09:25 IST**, so the prior session's **completed** daily candle is evaluated rather than an in-progress one. Skips NSE holidays.
2. **Definition (fresh breakout):** the latest daily candle's **high** exceeded the highest high of the preceding **52 weeks** (a 364-calendar-day window that *excludes* the latest candle itself).
3. **Daily candles only:** no intraday resampling. Reads the shared OHLC cache and re-syncs any symbol whose latest candle is stale.
4. **Gates:** a minimum prior-session count guards thin recent listings; optional `MIN_BREAKOUT_PCT` demands a bigger margin; optional close-confirmation requires the close to also finish above the prior high.
5. **Delivery:** REST (`/api/v1/fifty-two-week-high/scan`) and a Telegram command (`/52wh`), plus an automatic 09:25 Telegram summary.
6. **NIFTY 50 context:** every report also carries the NIFTY 50 index's last-session **Heikin-Ashi** color (green/red) and streak, so the breakout list can be read against the prevailing index trend.

| Endpoint | Description |
| :--- | :--- |
| `GET /api/v1/fifty-two-week-high/scan` | Full breakout report (JSON). |
| `POST /api/v1/fifty-two-week-high/run` | Scan + dispatch Telegram summary. |
| `GET /api/v1/fifty-two-week-high/scan/{symbol}` | Single-symbol breakout check. |

---

## Telegram Bot Commands

The bot provides a bidirectional polling listener supporting the following commands:

| Command | Description |
| :--- | :--- |
| `/monthlyrange`, `/monthly_range`, `/garch` | **Calculate GARCH(1,1) Monthly Option Range & Safe CE/PE Strikes** for `RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `TATAMOTORS`, `NIFTY50`. |
| `/garch_weekly` | **Force the weekly GARCH range forecast for the coming week** — runs the same routine as the Wednesday 08:00 scheduler and posts the full report to the chat (handy after a missed scheduled run). |
| `/52wh`, `/52weekhigh`, `/breakout` | **NSE F&O 52-Week-High Breakout Scan** — reports F&O equities whose latest completed daily candle crossed their 52-week high, plus the NIFTY 50 index's last-session Heikin-Ashi color (green/red). Highlights any **trade-ready setups** that also cleared all five research filters, with the recommended bull-call-spread strikes. |
| `/status`, `/lvr`, `/lvr_status` | View live LVR strategy state, market sentiment breadth, winning sector, candidates, and open positions. |
| `/scan`, `/lvr_scan` | Force execute an immediate 5-minute LVR strategy cycle. |
| `/morning_scan` | Force execute 09:25 AM LVR morning universe scan and sectoral ranking. |
| `/car`, `/car_status` | View live CAR Weekly GTT portfolio state, capital, invested units, demat holdings, and active GTT orders. |
| `/car_run`, `/car_weekly` | Force execute the CAR Weekly Sunday routine and place GTT orders on Zerodha Kite. |
| `/exit`, `/squareoff` | Square off all open intraday positions immediately. |
| `/reset` | Reset daily session state (requires `/reset force` during active market hours). |
| `/help` | Display the interactive Telegram command help menu. |

---

## Execution Modes

The bot provides flexible operational modes configured via environment variables:

| Mode | `EXECUTION_MODE` | Description |
| :--- | :--- | :--- |
| **Advisory Mode (Recommended for dry runs)** | `PAPER` or `LIVE` | Scans live market data, calculates exact strikes, and sends immediate Telegram entry/exit alerts without executing broker orders. |
| **Paper Trading** | `PAPER` | Simulates orders locally with live market feeds and tracks MTM. |
| **Full Live Execution** | `LIVE` | Executes real multi-leg option orders directly through Finvasia Shoonya or Zerodha Kite Connect APIs. |

---

## Environment Configuration (.env)

Create a `.env` file in the project root based on `.env.example`:

```bash
cp .env.example .env
```

### Key Configuration Parameters

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | Port for Spring Boot HTTP server and Actuator. |
| `EXECUTION_MODE` | `PAPER` | `PAPER` for simulation, `LIVE` for real trading. |
| `SHOONYA_USER_ID` | — | Shoonya Trading Account User ID. |
| `SHOONYA_PASSWORD` | — | Shoonya Account Password. |
| `SHOONYA_TOTP_SECRET` | — | Base32 TOTP secret key for automatic 2FA. |
| `SHOONYA_CLIENT_ID` | — | Shoonya API Client ID. |
| `SHOONYA_SECRET_KEY` | — | Shoonya API Secret Key. |
| `SHOONYA_FEED_HEALTH_ALERTS_ENABLED` | `true` | Telegram alert when the Shoonya live feed goes down / recovers (debounced so brief reconnect blips are ignored). |
| `SHOONYA_FEED_HEALTH_DOWN_THRESHOLD_SECONDS` | `60` | How long the feed must stay down before the "down" alert fires. |
| `KITE_ENABLED` | `true` | Enable Zerodha Kite Connect API integration. |
| `KITE_API_KEY` | — | Kite Connect API key. |
| `KITE_API_SECRET` | — | Kite Connect API secret. |
| `TELEGRAM_ENABLED` | `true` | Enable/disable Telegram alerts and polling bot. |
| `TELEGRAM_BOT_TOKEN` | — | Telegram Bot API token from BotFather. |
| `TELEGRAM_CHAT_ID` | — | Target Telegram Chat / Channel ID. |
| `MONTHLY_RANGE_ENABLED` | `true` | Enable/disable GARCH Monthly Option Range strategy. |
| `MONTHLY_RANGE_CRON` | `0 0 10 ? * WED` | Schedule for monthly post-expiry range calculation. |
| `MONTHLY_RANGE_SYMBOLS` | `RELIANCE,TCS,HDFCBANK,INFY,ICICIBANK,SBIN,TATAMOTORS,NIFTY50` | Comma-separated symbols for GARCH range forecast. |
| `MONTHLY_WEEKLY_RANGE_ENABLED` | `true` | Enable/disable the short-dated weekly/daily range forecast. |
| `MONTHLY_WEEKLY_RANGE_CRON` | `0 0 8 ? * WED` | Pre-open schedule for the weekly range routine. |
| `MONTHLY_WEEKLY_SYMBOLS` | `NIFTY50` | Symbols to forecast over the weekly expiry window. |
| `MONTHLY_WEEKLY_EXPIRY_DOW` | `TUESDAY` | Contractual weekly expiry weekday. |
| `MONTHLY_WEEKLY_DEPLOYMENT_DOW` | `WEDNESDAY` | Weekday the routine fires on; must be a trading session. |
| `MONTHLY_RANGE_K_SIGMAS` | `1.0,1.5,2.0` | Band widths, each reported with nominal and measured coverage. |
| `MONTHLY_RANGE_CALIBRATION_TRAIN_PCT` | `0.60` | Share of history used to fit params; the rest calibrates out-of-sample. |
| `MONTHLY_RANGE_CALIBRATION_MIN_WINDOWS` | `25` | Minimum out-of-sample windows before bands are reported as measured. |
| `MONTHLY_RANGE_GJR_COMPARISON_ENABLED` | `true` | Fit GJR-GARCH alongside GARCH(1,1) and report a head-to-head verdict. |
| `MONTHLY_WING_OFFSET_PTS` | `150` | Long wing offset from spot, in points. |
| `MONTHLY_WING_SHORT_OFFSET_PTS` | `200` | Short strike offset from spot, in points. |
| `MONTHLY_WING_CREDIT_PTS` | `20` | Net credit collected on entry, in points. |
| `CAR_ENABLED` | `true` | Enable/disable Cumulative Average Reversal weekly GTT strategy. |
| `CAR_WEEKLY_SUNDAY_CRON` | `0 0 10 ? * SUN` | CAR Weekly GTT evaluation schedule. |
| `LVR_ENABLED` | `true` | Enable/disable Lowest Volume Reversal strategy. |
| `LVR_CRON` | `20 */5 9-15 ? * MON-FRI` | LVR 5m candle evaluation schedule. |
| `LVR_SCANNER_MODE` | `HYBRID` | `HYBRID` (sector picks + NSE OI spurts) or `OI_SPURTS` (OI-led watchlist, sector picks demoted to reservoir). |
| `LVR_NSE_OI_ENABLED` | `true` | Fetch NSE's live OI spurts feed at 09:25 for phase-2 watchlist augmentation. |
| `LVR_NSE_OI_TOP_N` | `3` | How many NSE OI-spurt stocks are merged into the HYBRID watchlist at 09:25. |
| `LVR_NSE_OI_ATTEMPTS` | `3` | HTTP retry attempts against the NSE OI spurts endpoint before degrading to sector-only selection. |
| `LVR_NSE_OI_CRON` | `0 25 9 ? * MON-FRI` | Schedule of the NSE OI spurts fetch (fires before the 09:25:20 morning scan). |
| `LVR_NSE_OI_MIDMORNING_REFRESH` | `true` | Top up the standby reservoir from the NSE OI feed during the mid-morning refresh. |
| `LVR_BAN_PERIOD_ENABLED` | `true` | Master switch for the F&O ban-period exclusion (NCL/CMPT/70085; SEBI 2025/79). |
| `LVR_BAN_LOG_ONLY` | `true` | Validation mode: fetch, log and alert the ban list but exclude nothing. Set `false` to enforce. |
| `LVR_BAN_FAIL_CLOSED` | `false` | Policy when the ban list is unavailable: `true` blocks fresh entries, `false` trades unfiltered and alerts. |
| `LVR_NSE_BAN_URL` | `https://nsearchives.nseindia.com/content/fo/ban_list_{date}.csv` | Ban-list endpoint; `{date}` is filled with `ddMMyyyy` for the current session. |
| `LVR_NSE_BAN_ATTEMPTS` | `3` | HTTP retry attempts against the ban-list endpoint before reporting the list unavailable. |
| `LVR_NSE_BAN_CRON` | `0 10 9 ? * MON-FRI` | Pre-market ban-list fetch (fires before the 09:25:20 morning scan). |
| `LVR_EXPIRY_DTE_ENABLED` | `true` | Master switch for the expiry/DTE entry gate and its OPTIONS universe exclusion. |
| `LVR_OPTION_MIN_DTE_SESSIONS` | `2` | Trading sessions before monthly expiry at which a fresh OPTIONS entry is barred (0 = legacy). |
| `LVR_FUTURES_MIN_DTE_SESSIONS` | `0` | Same floor for FUTURES; 0 leaves the live FUTURES strategy unchanged. |
| `LVR_NIFTY_BIAS_ENABLED` | `false` | `false` = two-sided selection (strongest sectors long, weakest short) with NIFTY breadth as reporting only. `true` restores the legacy behaviour where a mixed-breadth reading stands the day down. |
| `LVR_BULLISH_SECTORS` / `LVR_BEARISH_SECTORS` | `3` | How many sectors are taken from each side of the market at the 09:25 scan. |
| `LVR_STOCKS_PER_SECTOR` | `3` | Top stocks drawn from each selected sector (sides are capped at `sectors × stocks`). |
| `LVR_TRIANGLE_ENABLED` | `true` | Adds stocks whose last three completed daily bars print strictly contracting highs **and** strictly rising lows; both directions are armed and the first valid pullback wins. |
| `LVR_TRIANGLE_MAX` | `5` | Cap on triangle picks, tightest squeeze first. |
| `LVR_INDEX_ALIGNMENT_ENABLED` | `true` | Entry gate at trigger time: LONG requires NIFTY 50 above its previous close, SHORT below it. Fails **open** — a missing quote never blocks an entry. |
| `LVR_INDEX_ALIGNMENT_BUFFER_BPS` | `10.0` | Dead-band in basis points the index must clear the previous close by, so a sub-0.05% wobble cannot flip-flop the decision. `0` = a naked cross. |
| `LVR_INDEX_ALIGNMENT_SYMBOL` | `NIFTY 50` | Index instrument quoted for the gate (resolves to NSE token 26000). |
| `LVR_PCR_NEUTRAL_MIN` | `0.85` | Lower edge of the PCR no-trade band: SHORT requires PCR below it. |
| `LVR_PCR_NEUTRAL_MAX` | `1.15` | Upper edge of the PCR no-trade band: LONG requires PCR above it; in between blocks both directions. |
| `POSTCLOSE_SCHEDULER_ENABLED` | `true` | Enable the 20:00 IST post-close data job (high/low sync + option-chain snapshots). |
| `POSTCLOSE_CRON` | `0 0 20 ? * MON-FRI` | Schedule of the post-close data job. |
| `FIFTY_TWO_HIGH_ENABLED` | `true` | Enable/disable the NSE F&O 52-week-high breakout scanner. |
| `FIFTY_TWO_HIGH_SCHEDULER_ENABLED` | `true` | Arm the 09:25 IST scheduled scan (independent of on-demand REST/Telegram). |
| `FIFTY_TWO_HIGH_CRON` | `0 25 9 ? * MON-FRI` | Schedule of the breakout scan (IST). |
| `FIFTY_TWO_HIGH_TELEGRAM_ALERTS` | `true` | Push the breakout summary to Telegram on each scan. |
| `FIFTY_TWO_HIGH_LOOKBACK_DAYS` | `364` | Calendar-day window defining the "52 weeks" prior-high lookback. |
| `FIFTY_TWO_HIGH_MIN_PRIOR_SESSIONS` | `60` | Minimum prior sessions before a 52-week high is considered meaningful. |
| `FIFTY_TWO_HIGH_MIN_BREAKOUT_PCT` | `0.0` | Minimum % the latest high must exceed the prior 52w high by (0.0 = any strict cross). |
| `FIFTY_TWO_HIGH_REQUIRE_CLOSE_CONFIRMATION` | `false` | `true` reports only close-confirmed breakouts (close also above the prior 52w high). |
| `FIFTY_TWO_HIGH_SYMBOLS` | *(empty)* | Optional comma-separated universe; empty = full NSE F&O equity universe (indices excluded). |
| `FIFTY_TWO_HIGH_YEARS_BACK` | `2` | Years of daily history fetched per symbol. |
| `FIFTY_TWO_HIGH_MAX_STALE_DAYS` | `4` | Re-sync a symbol when its latest cached daily candle is older than this many days. |
| `FIFTY_TWO_HIGH_TELEGRAM_MAX_SYMBOLS` | `15` | How many breakouts a Telegram message lists (REST always returns all). |
| `FIFTY_TWO_HIGH_TRADE_SETUP_ENABLED` | `true` | **Experimental.** Score each close-confirmed breakout against five filters (vol > 1.3× its 20d avg, above 200-DMA, 20d momentum > 0, relative strength > 0 vs NIFTY, NIFTY > 200-DMA); the 09:25 alert fires **only** for setups clearing all five, carrying the recommended spread strikes. |
| `FIFTY_TWO_HIGH_TRADE_SETUP_MIN_VOLUME_RATIO` | `1.3` | Minimum breakout volume as a multiple of its 20-day average (volume-participation gate). |
| `FIFTY_TWO_HIGH_TRADE_SETUP_LONG_STRIKE_OFFSET` | `4` | Strikes above ATM for the **long** leg of the recommended bull-call debit spread (buy ATM+4). |
| `FIFTY_TWO_HIGH_TRADE_SETUP_SHORT_STRIKE_OFFSET` | `8` | Strikes above ATM for the **short** leg of the recommended debit spread (sell ATM+8). |

### Scheduled Data Jobs

| Time (IST) | Job | What it does |
| :--- | :--- | :--- |
| 08:00, Mon-Fri | Morning OHLC sync | Refreshes daily/weekly/monthly candles from Yahoo Finance before the open. |
| 09:25, Mon-Fri | 52-week-high breakout scan | Scans every NSE F&O equity for symbols whose latest **completed** daily candle crossed the prior 52-week high (daily candles only). With `FIFTY_TWO_HIGH_TRADE_SETUP_ENABLED` (default on), the pushed Telegram alert fires **only** for close-confirmed breakouts clearing all five filters (volume, 200-DMA, momentum, relative strength, NIFTY regime), listing the recommended bull-call-spread strikes; otherwise it suppresses the alert. Skips NSE holidays. |
| 20:00, Mon-Fri | Post-close data job | Force-syncs today's **completed** daily high/low into SQLite (the normal freshness check would skip a day that just closed) and captures the closing **option chain** for every F&O universe symbol (`NiftySectorRegistry`, deduplicated) into the `option_chain_snapshots` table of `data/trading_bot.db`. Snapshots are stored under the trading session they reflect and pruned after 30 days. |
| 23:00, daily | SQLite backup | Atomic defragmented backup of the active database. |

The 20:00 snapshots exist for **offline replay audits only**: `LvrTodayGateReplayAuditTest` reads
them (keyed by the replayed session, live fallback on a miss) so the gate audit can run after the
close — even during the overnight broker maintenance window — without hitting the API. Live trading
is untouched: LVR still fetches the option chain in real time for the armed symbol only, so no
stale data can ever reach a live entry gate.

---

## Docker & Deployment Commands

### Quick Start with Docker Compose

To pull the latest published image and run the bot with persistent logs and session data:

```bash
# 1. Pull latest image from Docker Hub
docker compose pull

# 2. Start services in background
docker compose up -d

# 3. View live logs
docker compose logs -f shoonya-trading-bot
```

### Docker Build & Push Commands

To rebuild the container locally and publish to Docker Hub:

```bash
# Build Docker image locally
docker build -t sharadprsn/shoonya-trading-bot:latest .

# Push image to Docker Hub
docker push sharadprsn/shoonya-trading-bot:latest
```

### Container Operations & Maintenance

```bash
# Check status of running containers
docker compose ps

# Stop the trading bot
docker compose stop

# Restart the trading bot
docker compose restart

# Stop and remove containers, networks, and volumes
docker compose down

# Force recreation of containers after .env update
docker compose up -d --force-recreate
```

### Viewing Logs

```bash
# Follow live container logs
docker logs -f shoonya-trading-bot

# View last 100 log lines
docker logs --tail 100 shoonya-trading-bot

# View persistent application log file from mounted volume
tail -f logs/shoonya-trading-bot.log
```

### Health & Monitoring

The container exposes a Docker health check linked to Spring Boot Actuator:

```bash
# Check Actuator Health via HTTP
curl http://localhost:8080/actuator/health

# Inspect Docker health status
docker inspect --format='{{json .State.Health}}' shoonya-trading-bot
```

---

## Local Development & Code Quality

### Gradle Commands

```bash
# Clean build without tests
./gradlew bootJar -x test

# Run all unit and integration tests
./gradlew test
```

### Spotless Code Formatting

The project enforces Java code formatting (AOSP standard with Google Java Format):

```bash
# Apply automatic formatting to all Java source files
./gradlew spotlessApply

# Check if any files violate formatting rules
./gradlew spotlessCheck
```

### SpotBugs Static Analysis

SpotBugs inspects compiled bytecode for common bugs and security vulnerabilities:

```bash
# Run SpotBugs static analysis
./gradlew spotbugsMain

# Open HTML report
start build/reports/spotbugs/main.html    # Windows
open build/reports/spotbugs/main.html     # macOS
xdg-open build/reports/spotbugs/main.html # Linux
```

---

## REST API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/actuator/health` | Container and application health probe. |
| `POST` | `/api/v1/monthly-range/run` | Triggers GARCH(1,1) monthly range calculation, sends Telegram alert, and returns full report. |
| `GET` | `/api/v1/monthly-range/forecast` | Returns latest GARCH monthly range forecast report for all configured symbols. |
| `GET` | `/api/v1/monthly-range/forecast/{symbol}?horizonDays=22` | Calculates GARCH monthly range forecast for a single stock or index. |
| `POST` | `/api/v1/car/run-weekly?force=false` | Triggers CAR weekly GTT routine and orders. |
| `GET` | `/api/v1/car/performance` | Returns CAR portfolio state and metrics. |
| `GET` | `/api/v1/car/holdings` | Returns active CAR demat holdings. |
| `GET` | `/api/v1/ohlc/nifty50?interval=5&count=2` | Fetches NIFTY 50 candles from Shoonya. |
| `GET` | `/api/v1/ohlc/hourly?symbol=ABB&days=30` | Fetches 1-hour candles directly from Shoonya for any symbol. |
| `GET` | `/api/v1/indicators/nifty50` | Computes technical indicators (SuperTrend, RSI, VWAP) for NIFTY 50. |
| `GET` | `/api/v1/optionchain/nifty50` | Fetches live NIFTY option chain (strikes, OI, LTP). |
| `GET` | `/api/v1/orders/*` | Broker order listing / status via Shoonya. |
| `GET/POST` | `/api/v1/execution/*` | Execution mode switch, directional spread trades, close, positions. |
| `GET/POST` | `/api/strategy/lowest-volume/*` | LVR scan, morning-scan, notify, status, setups, positions, history, reset, toggle. |
| `GET` | `/api/v1/fifty-two-week-high/scan` | Scan NSE F&O equities for fresh 52-week-high breakouts (JSON, no Telegram). |
| `POST` | `/api/v1/fifty-two-week-high/run` | Run the breakout scan and dispatch the Telegram summary. |
| `GET` | `/api/v1/fifty-two-week-high/scan/{symbol}` | Single-symbol 52-week-high breakout check (404 if none). |

---

## License

This project is proprietary and intended for private automated trading. Use at your own risk. Past performance does not guarantee future financial returns.
