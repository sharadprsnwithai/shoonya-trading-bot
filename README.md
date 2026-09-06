# Shoonya Algorithmic Trading Bot

> **Intraday Directional Option Selling Bot (Nifty 50 Credit Spread)**  
> Built with Java 21, Spring Boot 3.3.5, and Finvasia Shoonya (NorenAPI). Optimized for low-footprint VPS deployments.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Strategy Details (Pivot Points + SuperTrend Credit Spread)](#strategy-details)
   - [Core Technical Indicators](#core-technical-indicators)
   - [Entry Rules & Timing Filter](#entry-rules--timing-filter)
   - [Credit Spread Hedging (Risk Mitigation)](#credit-spread-hedging-risk-mitigation)
   - [Exit Criteria & Risk Management](#exit-criteria--risk-management)
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
   - [Spotless Code Formatting](#spotless-code-formatting)
   - [SpotBugs Static Analysis](#spotbugs-static-analysis)
7. [REST API Endpoints](#rest-api-endpoints)

---

## Project Overview

The **Shoonya Trading Bot** is an automated and advisory trading engine designed for the Indian derivatives market (NSE/NFO).

- **Broker API:** Headless OAuth session manager for Finvasia Shoonya (`NorenAPI`) with disk caching.
- **Telegram Integration:** Instant rich notifications for trade entries, hedge purchases, stop-loss triggers, target profit hits, and end-of-day square-offs.
- **Low Footprint:** Containerized runtime configured with serial GC (`-XX:+UseSerialGC -Xms256m -Xmx384m`) and resource caps (300 MB reservation, 600 MB max) for 1 GB RAM cloud VPS nodes.
- **Quality Assured:** Rigorous unit and integration testing, Spotless (AOSP standard), and SpotBugs static code analysis.

---

## Strategy Details

The primary strategy is the **Intraday Directional Option Selling (Daily Pivot Points + SuperTrend with Credit Spread)** on **NIFTY 50**.

### Core Technical Indicators

| Indicator | Configuration | Purpose |
| :--- | :--- | :--- |
| **Timeframe** | 5-Minute (5m) | Bar resolution evaluated strictly on candle close. |
| **Standard Daily Pivots** | P = (H + L + C) / 3<br>R1 = 2P - L<br>S1 = 2P - H | Calculated at 09:15 IST using previous day's daily candle to determine institutional breakout/breakdown levels. |
| **SuperTrend** | Period = 7, Multiplier = 3.0 | Fast ATR trend tracking and trailing stop-loss trigger. |
| **Hedge Leg** | +150 points OTM | Long option wing converting naked short into a defined-risk Credit Spread. |

### Entry Rules & Timing Filter

1. **Morning Noise Filter:** No entries are permitted before **09:30:00 IST** to eliminate first-candle opening whipsaws.
2. **Bullish Confluence:**
   - Evaluated on 5m candle close:
     `Close > SuperTrend(7, 3)` **AND** `Close > R1`
   - **Action:**
     - **Sell Leg:** Sell 1 lot ATM Put (`PE`) at current market price.
     - **Hedge Leg:** Buy 1 lot OTM Put (`PE`) strike (`ATM - 150`) at current market price.
3. **Bearish Confluence:**
   - Evaluated on 5m candle close:
     `Close < SuperTrend(7, 3)` **AND** `Close < S1`
   - **Action:**
     - **Sell Leg:** Sell 1 lot ATM Call (`CE`) at current market price.
     - **Hedge Leg:** Buy 1 lot OTM Call (`CE`) strike (`ATM + 150`) at current market price.

### Credit Spread Hedging (Risk Mitigation)

- **Capped Maximum Loss:** Buying an OTM option 150 points away ensures that black-swan events, circuit limits, or sudden gap movements cannot cause catastrophic account drawdowns.
- **Substantial Margin Reduction:** Exchange margin requirements for credit spreads are significantly lower than naked option writing (typically ₹35,000–₹45,000 vs. ₹1,25,000+ per lot).

### Exit Criteria & Risk Management

| Exit Condition | Rule | Trigger Behavior |
| :--- | :--- | :--- |
| **Hard Stop Loss** | **30%** | Exits both legs immediately if the short option premium rises 30% above entry price. |
| **Target Profit** | **50%** | Secures profit if the short option premium decays by 50% from entry price. |
| **SuperTrend Flip** | Trend Reversal | Exits position immediately when SuperTrend reverses color (Bullish to Bearish for PE; Bearish to Bullish for CE). |
| **Intraday Square-Off**| **15:15 IST** | Mandatory EOD square-off to avoid overnight gamma and assignment risk. |
| **Daily Trade Cap** | **1 Trade / Day** | Bot shuts down new entries after 1 trade for the session to enforce discipline and avoid over-trading. |

---

## Execution Modes

The bot provides flexible operational modes configured via environment variables:

| Mode | `EXECUTION_MODE` | `STRATEGY_AUTO_EXECUTE` | Description |
| :--- | :--- | :--- | :--- |
| **Advisory Mode (Recommended for dry runs)** | `PAPER` or `LIVE` | `false` | Scans live market data, calculates exact strikes, and sends immediate Telegram entry/exit alerts without executing broker orders. |
| **Paper Trading** | `PAPER` | `true` | Simulates orders locally with live market feeds. |
| **Full Live Execution** | `LIVE` | `true` | Executes real 2-leg credit spread orders directly through Finvasia Shoonya API. |

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
| `STRATEGY_SCHEDULER_ENABLED` | `true` | Enables scheduled market data polling. |
| `STRATEGY_AUTO_EXECUTE` | `false` | `false` = Advisory alerts only; `true` = Auto execution. |
| `STRATEGY_LOTS` | `1` | Number of lots to trade per signal. |
| `STRATEGY_LOT_SIZE` | `65` | Nifty contract lot size (standard: 65). |
| `STRATEGY_MAX_DAILY_TRADES` | `1` | Maximum allowed trades per calendar day. |
| `STRATEGY_BUY_HEDGE` | `true` | Enable credit spread hedge leg. |
| `STRATEGY_HEDGE_DISTANCE` | `150` | Strike distance (points) for the OTM hedge. |
| `STRATEGY_ENTRY_START_TIME` | `09:30:00` | Earliest entry time allowed. |
| `STRATEGY_STOP_LOSS_PERCENT`| `30.0` | Stop loss percentage on short premium. |
| `STRATEGY_TARGET_PROFIT_PERCENT`| `50.0` | Target profit percentage on short premium. |
| `STRATEGY_OI_FILTER_ENABLED`| `false` | Optional Open Interest differential filter. |

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
| `GET` | `/api/v1/strategy/pivot-supertrend/status` | Current strategy state, active trade, and daily count. |
| `POST`| `/api/v1/strategy/pivot-supertrend/evaluate` | Manually triggers technical evaluation for current 5m candle. |
| `POST`| `/api/v1/strategy/pivot-supertrend/square-off` | Manually initiates instant market liquidation of open positions. |
| `GET` | `/api/v1/market/option-chain/pcr` | Fetches live Nifty Put-Call Ratio (PCR) and OI data. |

---

## License

This project is proprietary and intended for private automated trading. Use at your own risk. Past performance does not guarantee future financial returns.
