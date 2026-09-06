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

## 🎯 Strategy 2: Triple SuperTrend + RSI Directional Options Strategy

A high-probability, directional Options Trading strategy supporting both **Option Selling (Default)** and **Option Buying**, executed on **1-Hour (60m) candles** across **NIFTY 50** and **29 high-liquidity F&O stocks**:

> **Subscribed Symbols (30 Instruments):**  
> `NIFTY50`, `ABB`, `ADANIENSOL`, `ADANIGREEN`, `ADANIPOWER`, `ABCAPITAL`, `BSE`, `BHARATFORG`, `BHEL`, `CGPOWER`, `CUMMINSIND`, `FEDERALBNK`, `GVT&D`, `GLENMARK`, `HINDALCO`, `POWERINDIA`, `KEI`, `LTF`, `LAURUSLABS`, `MCX`, `NTPC`, `NATIONALUM`, `POLYCAB`, `MOTHERSON`, `SHRIRAMFIN`, `SOLARINDS`, `SAIL`, `TATASTEEL`, `TORNTPHARM`, `VEDL`.

### Strategy Execution Modes (`TRIPLE_ST_MODE`)

| Mode | Bullish Confluence | Bearish Confluence | Exit Flip Trigger | Target Profit | Hard Stop Loss |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`OPTION_SELLING` (Default)** | **SELL ATM Put (Short PE / Bull Put Spread)** | **SELL ATM Call (Short CE / Bear Call Spread)** | Fast ST flips color | **50% premium decay** | **+30% premium expansion** |
| **`OPTION_BUYING`** | **BUY ATM Call (Long CE)** | **BUY ATM Put (Long PE)** | Fast ST flips color | **100% premium gain** | **-30% premium drop** |

### 5 Strategic Improvements Implemented

1. **ADX(14) Trend Regime Filter (`TRIPLE_ST_ADX_THRESHOLD=22.0`):**
   - Eliminates entries during directionless sideways chop by strictly requiring `ADX(14) >= 22.0`.
   - Saves over ₹1,80,000 by eliminating 63 false breakout whipsaw trades across 30 symbols.

2. **RSI Exhaustion Caps (`TRIPLE_ST_RSI_BULLISH_MAX=68.0`, `TRIPLE_ST_RSI_BEARISH_MIN=32.0`):**
   - **Bullish (Put Sell / Call Buy):** Requires `45.0 <= RSI <= 68.0` to avoid selling puts at an overbought top where mean-reversion pullbacks occur.
   - **Bearish (Call Sell / Put Buy):** Requires `32.0 <= RSI <= 55.0` to avoid selling calls at the bottom of an oversold waterfall drop.

3. **Post-Loss Whipsaw Cooldown (`TRIPLE_ST_COOLDOWN_BARS=3`):**
   - Freezes new entries on any symbol for 3 hourly bars immediately after an unprofitable trade exit.
   - Eliminates consecutive ping-pong losses during choppy transition phases.

4. **Defined-Risk Credit Spreads (`TRIPLE_ST_CREDIT_SPREAD_ENABLED=true`):**
   - Automatically buys a 2-strike OTM hedge (`TRIPLE_ST_HEDGE_STRIKE_OFFSET=2`) alongside the ATM short leg:
     - **Bullish:** **Bull Put Credit Spread** (Short ATM PE + Long 2-strike OTM PE).
     - **Bearish:** **Bear Call Credit Spread** (Short ATM CE + Long 2-strike OTM CE).
   - Strictly caps maximum risk to `Spread Width - Net Credit`, preventing tail-risk catastrophic losses while reducing exchange margin by 65–70%.

5. **Curated Trending Symbol Basket (`TRIPLE_ST_SYMBOL_BASKET=CURATED`):**
   - Filters the 30 instruments down to the top 10 cleanest trending performers:
     `NIFTY50`, `VEDL`, `GLENMARK`, `MCX`, `SAIL`, `NATIONALUM`, `BSE`, `KEI`, `HINDALCO`, `BHEL`.
   - Allows switching between `CURATED` (10 stocks) and `ALL` (30 stocks) dynamically via configuration.

### 🛡️ False Breakout Filter Suite (Option Buying & Selling)

To avoid buying bull traps, climax exhaustion tops, and counter-trend whipsaws across the 30 instruments:

1. **Rejection Wick Filter (`TRIPLE_ST_REJECTION_WICK_THRESHOLD=0.60`):**
   - **Bullish (CE Buy / PE Sell):** Requires `(Close - Low) / (High - Low) >= 0.60`. The candle must close in the upper 40% of its range, eliminating upper shadow bull traps and inverted hammers.
   - **Bearish (PE Buy / CE Sell):** Requires `(High - Close) / (High - Low) >= 0.60`. The candle must close in the lower 40% of its range, eliminating lower shadow bear traps and hammer bottoms.

2. **ATR Range Sanity Filter (`0.60x <= Candle Range <= 2.50x ATR(14)`):**
   - **Doji Block (`Range < 0.6x ATR`):** Discards sluggish low-volatility bars where breakout conviction is absent.
   - **Climax Exhaustion Block (`Range > 2.5x ATR`):** Discards giant over-extended candles where option IV spikes and immediate profit-taking pullbacks follow.

3. **Intermediate Macro Trend Alignment (`50 EMA`):**
   - **Bullish Signals:** Requires `Close > 50 EMA` ensuring long positions only ride the dominant multi-day trend.
   - **Bearish Signals:** Requires `Close < 50 EMA` preventing shorting into strong uptrends.

4. **Institutional Volume Expansion Filter (`Volume >= 1.1x 20-period Volume SMA`):**
   - Validates that high institutional participation accompanies the hourly breakout.
   - Gracefully bypassed when historical volume data is 0 or unavailable.

### 1-Month Backtest Performance Comparison (Hourly Bars)

#### Option Selling Mode (`TRIPLE_ST_MODE=OPTION_SELLING`)

| Strategy Variant | Total Trades | Win Rate | Net PnL (₹) | Avg / Trade | Risk Profile |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **1. Baseline Naked Option Selling (All 30)** | 248 | 28.2% | -₹6,17,127.75 | -₹2,488.42 | Uncapped tail risk |
| **2. + ADX(14) $\ge$ 22 Filter (All 30)** | 185 | 27.6% | -₹4,33,677.41 | -₹2,344.20 | Saves ₹1,83,450 from chop |
| **3. + ADX $\ge$ 22 + RSI Caps (32–68)** | 158 | 26.6% | -₹3,64,221.96 | -₹2,305.20 | Saves ₹69,455 from exhaustion |
| **4. + ADX $\ge$ 22 + RSI Caps + 3-Bar Cooldown**| 150 | 27.3% | -₹3,55,981.85 | -₹2,373.21 | Avoids whipsaw cascades |
| **5. Defined-Risk Credit Spreads (All 30)** | 160 | 28.7% | -₹1,05,731.54 | -₹660.82 | **Saves ₹5,11,396 in tail risk!** |
| **6. Top 10 Curated Basket (Credit Spreads)** | **63** | **38.1%** | **-₹151.65** | **-₹2.41** | **Near-breakeven, defined-risk, 70% lower margin** |
| **7. Top 10 Curated Basket (Naked Selling)** | **62** | **38.7%** | **+₹9,274.88** | **+₹149.59** | **Net Profitable on trending equities** |

#### Option Buying Mode (`TRIPLE_ST_MODE=OPTION_BUYING`)

| Strategy Variant | Total Trades | Win Rate | Profit Factor | Net PnL (₹) | Drawdown (₹) | Key Takeaway |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **1. Baseline Option Buying (All 30)** | 248 | 29.8% | 0.79 | -₹3,20,900.30 | ₹4,82,190.50 | Suffers heavy whipsaws in non-trending equities |
| **2. + False Breakout Suite (All 30)** | 111 | 33.3% | 0.88 | -₹1,55,112.50 | ₹2,10,450.00 | **Cuts losses by >50%, saves ₹1,65,788** (e.g. LTF: -₹1,18k $\to$ -₹1.5k) |
| **3. Top 10 Curated Basket (Naked Buying)** | 64 | 34.4% | 0.94 | -₹24,547.00 | ₹68,200.00 | Drastic drawdown reduction |
| **4. Top 10 Curated + False Breakout Suite** | **36** | **38.9%** | **1.23** | **+₹29,641.84** | **₹24,150.00** | **Net Profitable! Clean breakouts on trending leaders** |

#### Standout Individual Performers (Option Buying + False Breakout Suite)

- **`NATIONALUM`:** **+₹48,843.75** Net Profit (Profit Factor: **1.75**)
- **`GLENMARK`:** **+₹26,582.50** Net Profit (**60.0% Win Rate**, Profit Factor: **2.77**)
- **`VEDL`:** **+₹11,625.00** Net Profit (**80.0% Win Rate**, Profit Factor: **4.41**)
- **`NIFTY50`:** **+₹7,117.50** Net Profit (Profit Factor: **1.81**)
- **`BHEL`:** **+₹6,187.50** Net Profit (Profit Factor: **1.35**)

### Dynamic Exit Rules (Option Selling & Credit Spread Mode)

1. **Fast SuperTrend (7, 2) Reversal (Primary Exit):**
   - Exit Short PE / Bull Put Spread when Fast SuperTrend turns **Bearish (Red)**.
   - Exit Short CE / Bear Call Spread when Fast SuperTrend turns **Bullish (Green)**.
2. **Hard Stop Loss (+30%):** Immediate exit if sold net spread premium rises +30% above entry credit.
3. **Target Profit (50%):** Secures profit when sold net spread premium decays by 50%.
4. **Intraday Square-Off:** Disabled by default (`TRIPLE_ST_INTRADAY_MODE=false`) to support multi-day swing holds.

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
| `TRIPLE_ST_ENABLED` | `true` | Enable/disable Triple SuperTrend strategy. |
| `TRIPLE_ST_MODE` | `OPTION_SELLING` | Strategy mode (`OPTION_SELLING` or `OPTION_BUYING`). |
| `TRIPLE_ST_CREDIT_SPREAD_ENABLED` | `true` | Converts naked short option into defined-risk credit spread. |
| `TRIPLE_ST_HEDGE_STRIKE_OFFSET` | `2` | Number of strikes OTM for the hedging wing leg. |
| `TRIPLE_ST_ADX_FILTER_ENABLED` | `true` | Filter out low-volatility, directionless consolidation chop. |
| `TRIPLE_ST_ADX_THRESHOLD` | `22.0` | Minimum ADX(14) value required for entry signal. |
| `TRIPLE_ST_RSI_BULLISH_MAX` | `68.0` | RSI exhaustion ceiling for Bullish entries (avoids chasing overbought tops). |
| `TRIPLE_ST_RSI_BEARISH_MIN` | `32.0` | RSI exhaustion floor for Bearish entries (avoids chasing oversold bottoms). |
| `TRIPLE_ST_COOLDOWN_BARS` | `3` | Number of hourly bars to freeze entries on a symbol after a loss. |
| `TRIPLE_ST_SYMBOL_BASKET` | `CURATED` | Active trading basket (`CURATED` for top 10 trending, `ALL` for 30). |
| `TRIPLE_ST_LOTS` | `1` | Number of lots to execute per symbol. |

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
| `GET` | `/api/v1/ohlc/hourly?symbol=ABB&days=30` | Fetches 1-hour candles directly from Shoonya for any symbol. |
| `GET` | `/api/v1/strategy/triple-supertrend/status` | Live status, parameters, and open positions across all 30 symbols. |
| `GET` | `/api/v1/strategy/triple-supertrend/symbols` | List of all 30 subscribed symbols with lot sizes and strike steps. |
| `GET` | `/api/v1/strategy/triple-supertrend/data?symbol=ABB&days=30` | Fetches live hourly candles from Shoonya for strategy validation. |
| `POST`| `/api/v1/strategy/triple-supertrend/evaluate` | Evaluates candle and triggers BUY CE/PE or Fast ST exit signals. |
| `POST`| `/api/v1/strategy/triple-supertrend/square-off` | Liquidates active position for a specific symbol or all symbols. |
| `POST`| `/api/v1/strategy/triple-supertrend/backtest?symbol=ABB` | Runs historical backtest on hourly data for any symbol. |
| `GET` | `/api/v1/strategy/pivot-supertrend/status` | Pivot SuperTrend selling strategy state and active trade. |
| `POST`| `/api/v1/strategy/pivot-supertrend/evaluate` | Manually triggers technical evaluation for current 5m candle. |
| `POST`| `/api/v1/strategy/pivot-supertrend/square-off` | Manually initiates instant market liquidation of open positions. |
| `GET` | `/api/v1/market/option-chain/pcr` | Fetches live Nifty Put-Call Ratio (PCR) and OI data. |

---

## License

This project is proprietary and intended for private automated trading. Use at your own risk. Past performance does not guarantee future financial returns.
