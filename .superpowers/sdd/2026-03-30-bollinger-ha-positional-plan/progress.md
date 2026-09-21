# Positional Strategy Implementation Ledger

## Strategy Overview
- **Strategy**: Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy on Nifty 50 Spot
- **Structure**: Monthly ATM Option Selling paired with 0.20 Delta OTM Protective Hedge Leg (Bull Put Spread on Long, Bear Call Spread on Short)
- **Timeframe**: Daily evaluation at 3:00 PM IST (`0 0 15 * * MON-FRI`)
- **Execution Mode**: `MANUAL_CONFIRMATION` (default) with Telegram Bot inline interactive buttons and `/approve` / `/reject` / `/exit` / `/status` / `/scan` / `/mode` commands.

## Implementation Status
- [x] Task 1: Domain models (`PositionalStatus`, `PositionalAlert`, `PositionalTrade`, `PositionalState`), configuration (`PositionalStrategyConfig`), and serialization unit tests (`PositionalStateSerializationTest`).
- [x] Task 2: Indicator calculation engine (`HeikinAshiCandle`, `BollingerBandSnapshot`, `BollingerHaIndicatorService`) and unit tests (`BollingerHaIndicatorServiceTest`).
- [x] Task 3: Core strategy service (`BollingerHaPositionalService`), daily 3:00 PM scheduler (`PositionalTradingScheduler`), properties, and unit tests (`BollingerHaPositionalServiceTest`).
- [x] Task 4: Option execution engine for hedged credit spreads (`PositionalExecutionService`, `ShoonyaPositionalExecutionService`) and unit tests (`ShoonyaPositionalExecutionServiceTest`).
- [x] Task 5: Bidirectional Telegram bot listener (`TelegramBotCommandListener`) with interactive inline keyboard callbacks and unit tests (`TelegramBotCommandListenerTest`).
- [x] Task 6: REST API controller (`BollingerHaPositionalController`) and unit tests (`BollingerHaPositionalControllerTest`).
- [x] Task 7: Complete backtests (11-Year, 3-Year, 1-Year, 2-Month) and updated strategy structure with hedged credit spreads.
- [x] Task 8: Full code styling and test verification (`./gradlew spotlessCheck test` PASSED).
