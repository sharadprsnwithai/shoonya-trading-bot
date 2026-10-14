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
3. **Entry Cutoff:** Hard cutoff at **11:00 AM / 11:30 AM IST** — no new setups or armed triggers after cutoff; open trades continue to target / stop-loss / trailing exit.
4. **Execution:** Futures or ATM options bought via decoupled execution consumers with 1:2 risk-reward, cost floor, and 10 EMA trailing.

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

---

## Telegram Bot Commands

The bot provides a bidirectional polling listener supporting the following commands:

| Command | Description |
| :--- | :--- |
| `/monthlyrange`, `/monthly_range`, `/garch` | **Calculate GARCH(1,1) Monthly Option Range & Safe CE/PE Strikes** for `RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `TATAMOTORS`, `NIFTY50`. |
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
| `KITE_ENABLED` | `true` | Enable Zerodha Kite Connect API integration. |
| `KITE_API_KEY` | — | Kite Connect API key. |
| `KITE_API_SECRET` | — | Kite Connect API secret. |
| `TELEGRAM_ENABLED` | `true` | Enable/disable Telegram alerts and polling bot. |
| `TELEGRAM_BOT_TOKEN` | — | Telegram Bot API token from BotFather. |
| `TELEGRAM_CHAT_ID` | — | Target Telegram Chat / Channel ID. |
| `MONTHLY_RANGE_ENABLED` | `true` | Enable/disable GARCH Monthly Option Range strategy. |
| `MONTHLY_RANGE_CRON` | `0 0 10 ? * WED` | Schedule for monthly post-expiry range calculation. |
| `MONTHLY_RANGE_SYMBOLS` | `RELIANCE,TCS,HDFCBANK,INFY,ICICIBANK,SBIN,TATAMOTORS,NIFTY50` | Comma-separated symbols for GARCH range forecast. |
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
| `LVR_PCR_NEUTRAL_MIN` | `0.85` | Lower edge of the PCR no-trade band: SHORT requires PCR below it. |
| `LVR_PCR_NEUTRAL_MAX` | `1.15` | Upper edge of the PCR no-trade band: LONG requires PCR above it; in between blocks both directions. |
| `POSTCLOSE_SCHEDULER_ENABLED` | `true` | Enable the 20:00 IST post-close data job (high/low sync + option-chain snapshots). |
| `POSTCLOSE_CRON` | `0 0 20 ? * MON-FRI` | Schedule of the post-close data job. |

### Scheduled Data Jobs

| Time (IST) | Job | What it does |
| :--- | :--- | :--- |
| 08:00, Mon-Fri | Morning OHLC sync | Refreshes daily/weekly/monthly candles from Yahoo Finance before the open. |
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

---

## License

This project is proprietary and intended for private automated trading. Use at your own risk. Past performance does not guarantee future financial returns.
