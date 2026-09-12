# Shoonya Algorithmic Trading Bot

> **Multi-Strategy Intraday & Positional Options Bot (NIFTY 50 + F&O Stocks)**  
> Built with Java 21, Spring Boot 3.3.5, and Finvasia Shoonya (NorenAPI). Optimized for low-footprint VPS deployments.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Active Strategies](#active-strategies)
   - [Lowest Volume Reversal (LVR)](#lowest-volume-reversal-lvr)
   - [NIFTY RSI Crossover (5m vs 15m)](#nifty-rsi-crossover-5m-vs-15m)
3. [Execution Modes](#execution-modes)
4. [Environment Configuration (.env)](#environment-configuration-env)
5. [Docker & Deployment Commands](#docker--deployment-commands)
   - [Quick Start with Docker Compose](#quick-start-with-docker-compose)
   - [Docker Build & Push Commands](#docker-build--push-commands)
   - [Container Operations & Maintenance](#container-operations--maintenance)
   - [Viewing Logs](#viewing-logs)
   - [Health & Monitoring](#health--monitoring)
6. [Local Development & Code Quality](#local-development--code-quality)
   - [Gradle Commands](#gradle-commands)
7. [REST API Endpoints](#rest-api-endpoints)

---

## Project Overview

The **Shoonya Trading Bot** is an automated and advisory trading engine designed for the Indian derivatives market (NSE/NFO).

- **Broker API:** Headless OAuth session manager for Finvasia Shoonya (`NorenAPI`) with disk caching.
- **Telegram Integration:** Instant rich notifications for trade entries, hedge purchases, stop-loss triggers, target profit hits, and end-of-day square-offs.
- **Low Footprint:** Containerized runtime configured with serial GC (`-XX:+UseSerialGC -Xms256m -Xmx384m`) and resource caps (300 MB reservation, 600 MB max) for 1 GB RAM cloud VPS nodes.
- **Quality Assured:** Rigorous unit and integration testing, Spotless (AOSP standard), and SpotBugs static code analysis.

---

## Active Strategies

The bot currently runs **three live strategies** on the NIFTY 50 index and high-liquidity F&O stocks:

### Lowest Volume Reversal (LVR)

Intraday cash‑stock momentum reversal on 5‑minute candles (`NSE` equities):

1. **Morning Universe Scan (09:25:10 IST):** Fixes the daily watchlist from the F&O universe (top gainers/losers ≥ +/−1.0%) and seeds initial setups.
2. **Pullback Volume Exhaustion:** A stock in a longer timeframe position that pulls back on the **lowest session volume** (evaluated across all candles from 09:15 open) signals reversal.
3. **Entry Cutoff:** Hard cutoff at **11:00 AM IST** — no new setups or armed triggers after 11:00 AM; open trades continue to target / stop-loss / SuperTrend trailing.
4. **Execution:** ATM options bought (CE for longs, PE for shorts) via `ExecutionManager`; SL, Target 1, and 5m SuperTrend(10, 3) trailing exit.
5. **Actionable Alerts:** Telegram `[TRADE SIGNAL: BUY CALL/PUT]` messages fire on actual trigger breach and fill (armed‑setup alerts disabled by default).

### NIFTY RSI Crossover (5m vs 15m)

Intraday RSI(14) crossover on **5‑minute vs 15‑minute resampled candles** of NIFTY 50 (`NSE:10576`):

1. **Signal:** 5m RSI crosses above the 15m RSI → **Short OTM Put Spread**; crosses below → **Short OTM Call Spread** - both with a **2% OTM hedge buy leg** for defined risk.
2. **Active Window:** 09:45:10 → 15:00:10 IST; **1 trade/day** (configurable); mandatory **EOD square-off at 15:05:10 IST**.
3. **Strikes:** ATM weekly options with standard NSE symbols (e.g. `NIFTY18SEP2524850PE`), hedge strike ≥ 50 points from ATM.
4. **Execution:** Multi-leg `ExecutionManager` flow (buy hedge first for margin relief, rollback on failure).

---

## Execution Modes

The bot provides flexible operational modes configured via environment variables:

| Mode | `EXECUTION_MODE` | Description |
| :--- | :--- | :--- |
| **Advisory Mode (Recommended for dry runs)** | `PAPER` or `LIVE` | Scans live market data, calculates exact strikes, and sends immediate Telegram entry/exit alerts without executing broker orders. |
| **Paper Trading** | `PAPER` | Simulates orders locally with live market feeds. |
| **Full Live Execution** | `LIVE` | Executes real multi-leg option orders directly through Finvasia Shoonya API (per-strategy auto-execute flags). |

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
| `SHOONYA_VENDOR_CODE`| `NOREN_API`| Shoonya Vendor Code. |
| `TELEGRAM_ENABLED` | `true` | Enable/disable Telegram alerts. |
| `TELEGRAM_BOT_TOKEN` | — | Telegram Bot API token from BotFather. |
| `TELEGRAM_CHAT_ID` | — | Target Telegram Chat / Channel ID. |
| `LVR_ENABLED` | `true` | Enable/disable Lowest Volume Reversal strategy. |
| `LVR_CRON` | `10 */5 9-11 ? * MON-FRI` | LVR 5m candle evaluation (11:00 AM entry cutoff). |
| `LVR_PAPER_CAPITAL` | `1000000.0` | LVR paper-trading capital. |
| `LVR_MAX_CONCURRENT_TRADES` | `5` | Max simultaneous LVR positions. |
| `RSI_*` | — | NIFTY RSI Crossover schedule / trade-limit settings. |

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
start build/reports/spotbugs/main.html  # Windows
open build/reports/spotbugs/main.html   # macOS
xdg-open build/reports/spotbugs/main.html # Linux
```

---

## REST API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/actuator/health` | Container and application health probe. |
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
