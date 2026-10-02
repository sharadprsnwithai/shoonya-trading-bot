# LVR Strategy Bug Audit — Comprehensive List

**Date:** 2026-10-01
**Scope:** Read-only analysis of `LowestVolumeReversalService` (2621 L), `LowestVolumeReversalScanner`, `LowestVolumeReversalScheduler`, `model/strategy/*`, `LowestVolumeStrategyController`, plus cross-check against root specs (`lowest_volume_reversal_spec.md`, `lvr_30sec_live_check_spec.md`, `lvr_option_buying_spec.md`, `lvr_scanner_token_fix_and_retry_spec.md`).
**Totals:** 8 critical · 11 high · 28 medium · 28 low ≈ 75 findings. Line refs verified against source. Duplicates across audit passes merged.

---

## 🔴 CRITICAL

1. **Double-entry race overwrites an open position** — `LowestVolumeReversalService.java:713-725, 920-933, 982, 1062` — `executePositionEntry` is `synchronized` but never re-checks setup state / attempts / `openPositions` inside the lock, and uses `put` not `putIfAbsent` (spec `lvr_30sec_live_check_spec.md:122` requires putIfAbsent). The 30s tick and 5-min cron run on separate pool threads; both can pass the `TRIGGER_ARMED` check, both `recordTradeAttempt` and publish ENTRY → **two live orders, first position silently discarded** (never exited, never in history, P&L never counted).
2. **Qty-0 EXIT after full partial book = naked live position** — `:1387` — when `executePartialBook` books everything (`remainingQuantity==0`, reachable whenever `lots==1`), the full-close EXIT publishes `getRemainingQuantity()` *after* it was zeroed; consumer drops qty≤0 signals (`AbstractTradeExecutionConsumer:108-114`) → **broker never closes, paper thinks flat.**
3. **1:2 target exits send display symbol, not broker symbol** — `:1380, 1421` — these branches publish `pos.getContractSymbol()` (`"RELIANCE FUT"`, `"RELIANCE ATM 2700CE"`) instead of `resolveBrokerTradingSymbol` (correctly used at 1170/1296/1492/1579) → live partial book never executes; later SL/EOD exits trade against wrong quantity.
4. **OPTIONS mode direction inverted** — `ShoonyaTradeConsumer.java:37-43` vs `:1032, 1067-1069` — strategy's `ENTRY_SHORT` means *buy PE* (`optType=PE`), but consumer maps it to `TransactionType.SELL` (writes the put), `EXIT_SHORT`→BUY → **naked short option on every SHORT trade** in OPTIONS live mode.
5. **No `isClosed()` guard → duplicate EXIT signals + double-counted P&L** — `:1129-1146, 1163-1206` — SL branch runs `close()/remove()/tradeHistory.add/publishSignal` unconditionally (target branch checks `!isClosed()`, SL branch doesn't); the 5-min cycle, 30s tick, and hard-exit run on different threads → second full-qty EXIT opens a **naked reverse position** live, and the same object sits twice in `tradeHistory` → circuit breaker trips falsely or real losses masked.
6. **`executeHardExit` widens the duplicate window and can abort entirely** — `:1557-1619` — never calls `openPositions.remove()` per item (only `clear()` at 1618); it's `synchronized` while `evaluateOpenPositions` is not, so the 30s tick iterates already-closed positions for the whole loop. One throwing item (no per-position try/catch) skips `clear()` → **closed positions re-processed every tick for the rest of the day, remaining positions never squared off, no retry after 15:05.**
7. **All state is in-memory — JVM restart orphans live positions** — `:130-150` — `openPositions`/`tradeHistory`/`activeSetups` have no persistence or startup reconciliation; with `EXECUTION_MODE=LIVE`, a restart at 11:00 leaves the broker holding a position with **no SL, no target, no 15:00 exit** — unlimited unhedged risk.
8. **`resetDaily` clears positions with no EXIT signals** — `:1621-1640` (reachable unauthenticated via controller:170 and Telegram `/reset`:239) — broker legs orphaned, history wiped so circuit breaker forgets all losses.

---

## 🟠 HIGH

9. **10-EMA runner trail is dead code before 13:00** — `:1461-1465` vs `:228, 730, 1121-1127` — step 3 requires `isCandleClose==true`, but every pre-13:00 path passes `false` (only line 228, gated `isAfter(13:00)`, passes `true`) → runner booked at 10:30 protected only by cost SL; the spec'd dynamic trail never runs during 09:25–13:00 → profits left on table.
10. **Mid-morning refresh bypasses stand-down and the breadth gate; direction can mismatch** — `:1709-1764, 1726-1729, 1652-1653, 294-308` — on a NEUTRAL ("standing down") day it re-evaluates sentiment with **hardcoded 56.0** (ignores `minBreadthPct`), falls back to `niftyBullish` **default true**, invents a direction and trades; filters candidates with fresh sentiment but promotes them with stale `niftyBullish` (LONG filter → SHORT entry possible).
11. **`niftyBullish` never reset and never updated** — `:1621-1640, 294-309, 1726-1729` — omitted from `resetDaily`, NEUTRAL morning path returns before setting it, mid-morning refresh computes fresh sentiment but **never assigns it** → yesterday's direction drives all replenished setups.
12. **Setup state-machine race** — `:586-692` — `processCandidateSetups` reads state, does network I/O, then mutates (`setTriggerCandle:642`, `resetToScanning:655/692`) without re-checking → `IN_POSITION` setup flipped back to `TRIGGER_ARMED` (enables duplicate entries when attempts≥2) or `SCANNING` while position open (breaks exit transitions).
13. **Option entry booked with fabricated premium** — `:1038-1041` — spec (`lvr_option_buying_spec.md:88`) says *skip the trade* if premium unavailable; code uses `max(0.50, spot×0.018)` → fabricated entry price, breakeven, partial-book math, `calculateTodayRealizedPnl` (:2228) and circuit-breaker all computed off a made-up number; fake premium published in the ENTRY signal.
14. **No pre-trade risk budget; breaker counts only realized losses** — `:940-948, 713, 2228-2261` — `plannedRisk` never checked against `riskPerTradeAmount` or `maxDailyLoss`; `maxConcurrentTrades=5` allows 5× aggregate risk; breaker sums only *closed* trades → open positions' unrealized losses invisible → **₹15,000 limit can be blown far past before tripping.**
15. **Arming uses the still-forming 5-min candle** — `:602-626, 497-577` + `ShoonyaMarketDataService:462` — no `timestamp+300s <= now` guard; the 09:30:20 cycle arms on a ~20-second candle (partial volume almost certainly `< rollingLowest`, provisional color) and the 30s live check can fire a real entry off its arbitrary trigger/SL before the next cycle re-evaluates.
16. **Breadth gate has no minimum-coverage check; quote failures permanently lock the day** — `:285-309` — `niftyQuotes` only holds quotes that survived fetch failures; zero/partial data → sentiment NONE → `universeScanCompletedToday=true` → "Standing down" **with no retry ever** (violates retry-until-10:00 in `lvr_scanner_token_fix_and_retry_spec.md` §3.2); a 70% sample still clearing 56% locks a whole day's direction from partial coverage.
17. **Every LVR signal becomes a Zerodha SL order at spot-scale trigger** — `ZerodhaTradeConsumer:44-54` + `ZerodhaBrokerGateway:80-90` — spot `stopLoss` passed as `triggerPrice`, gateway converts any trigger to `SL`: BUY-SL with trigger below LTP rejected by Kite; ₹1450 trigger on a ₹50 option is nonsense → live entries/exits rejected while bot believes trade exists → subsequent EXITs create naked opposite positions.
18. **Sector-blocked entries re-fetch and re-alert every 30 seconds, forever** — `:909-918, 2156-2174, 2111-2131` — sector rejection `return`s with no bookkeeping; setup stays ARMED → each tick issues ~11-30 unpaced quote calls (exhausting the 10-req/s budget) + a new Telegram alert (~120/hour/symbol) until 13:00. (VWAP rejection by contrast transitions to `REJECTED_EXHAUSTED` :875-880 — inconsistent.)
19. **Exception between `pos.close()` and `publishSignal` strands the broker position** — `:1176-1217, 1362-1455, 1497-1544` with swallow-catch at `:1549` — position removed from map, no retry, no rollback → paper flat, broker open.

---

## 🟡 MEDIUM

### Entry & arming

20. **Trigger breach tested on LTP, not session high/low** — `:805-813` vs `lvr_30sec_live_check_spec.md:54-56` — a wick through the trigger between 30s ticks is missed entirely; `h`/`l` quote fields never used.
21. **Re-arm loop from the same stale candle** — `:754-799, 626` — `resetToScanning()` records no exclusion; `filterAfter` null until a real exit → setup re-arms with identical levels every cycle (and re-fires "Setup Armed" Telegram :669) until armed-timeout kills it.
22. **Stale previous-session candles used when today's missing** — `:610-621, 1830-1832` — triggers/SL/targets armed from yesterday's levels vs today's live spot → spurious entries; `fetchLiveVwap` falls back to a **multi-day** VWAP mixing sessions → can permanently `REJECTED_EXHAUSTED` a symbol on garbage data.
23. **Armed-timeout off-by-one** — `:527-529, 649-650` — `> setupTimeoutCandles` fires on the 7th candle (35 min), not 6.
24. **Missing 10:00 champion fallback; failed morning scan kills candle-close management** — `:212-217, 276/333/361, 226` — no fallback at cutoff; a failed `/morning-scan` flips `universeScanCompletedToday=false` → `runCycle` returns **before `evaluateOpenPositions`** for the rest of the day (only the 30s check continues).
25. **`runCycle` has no reentrancy guard** — `:188` — only `runMorningUniverseScan` has `isScanning`; slow cycle (>5 min plausible) overlaps the 30s live check and manual `/scan` on separate pool threads → concurrent state mutation.
26. **SL/target go off-tick when `minStopLossPct` floor binds** — `:551-566` — base path is 0.05-aligned, but `risk = max(candleRisk, trigger×0.35/100)` yields arbitrary precision (e.g. 1230.239040) → Zerodha SL path rejects non-multiples of 0.05.
27. **Fallback `lotSize=100`/`strikeStep=10` for unregistered symbols** — `:936-937` — candidates from `NiftySectorRegistry` can be absent from `StockFnoRegistry` → `lots×100` units on a stock with 250-lot, ATM on wrong grid → wrong qty + wrong contract on a live order.
28. **Daily-loss gate checked once per cycle, not per entry** — `:713` — a loss realized by a concurrent exit mid-loop (after up to ~30 serial HTTP calls per entry) still lets that cycle enter; also no `live-breach-check-enabled` flag exists (spec §3 requires it).
29. **Daily latch has no date stamp; single 09:15 cron is a single point of failure** — `:149, 1621` + scheduler `:40-53` — pool starvation can delay reset past the 09:25 scan (scan runs against yesterday's state, then reset wipes the watchlist mid-scan); if missed entirely, yesterday's `completed=true`, setups, and trade history carry into today.

### Exits & position management

30. **Exits/partial-books execute at model-estimated premium** — `:1149, 1171-1176, 1358-1362` — spec (`lvr_30sec_live_check_spec.md:71-72`) says *skip partial book, retry next cycle* on LTP failure; code locks in realized P&L from `estimateOptionPremium` with no retry.
31. **EOD hard exit fabricates a zero-loss fill on quote failure** — `:1565-1568, 1580` — `fetchLiveSpotPrice==0` falls back to entry price → runner P&L forced to ~0, fabricated exit lands in `tradeHistory`, corrupting daily P&L, breaker, `/status`.
32. **Quote failure silently skips all exit checks** — `:1146` — `if (spotLtp <= 0) continue;` → dead-feed symbol is unmanaged all day with no alert until the fabricated EOD exit.
33. **Options exits announce spot price as "Exit Price"** — `:1171-1174/1230, 1297-1300/1349` — cost-SL and 1:2 exit Telegram print `spotPrice` under "Exit Price ₹" when actual exit was `optionPremium` (trader sees ₹1870 for a ₹45 trade); `:1346` also hardcodes "+2.00 R".
34. **Partial-exit qty: paper ceiling vs signal floor** — `:1430` vs `LowestVolumePaperPosition:184-187` — paper books `(lots+1)/2` (3 lots→2), signal publishes `totalQuantity/2` (1.5) → broker and paper book different quantities for odd lots → runner qty, P&L, subsequent EXITs all diverge.
35. **Premium model has no theta → cost-floor/EOD exits overstate P&L** — `:1849-1895` — `max(intrinsic, entryPrem + spotMove×delta)` with ₹0.50 floor: a runner stopped at breakeven after hours of decay prices ≈ entry premium (P&L 0), worthless options exit at ₹0.50.
36. **FUTURES entered/monitored/closed on *spot* LTP, not the futures price** — `:965-995, 1148-1175` — entry, signal price, SL/target checks, exit all use spot; contract trades at spot+basis (0.3-1%+ near expiry) → paper P&L, Telegram prices, and any consumer using `signal.price()` as limit are systematically wrong.
37. **One hung quote stalls all exits → 30s ticks overlap** — `:1141` + `ShoonyaMarketDataService:296-316` — per-position serial `fetchQuote` (10s×2 attempts ≈ 20s worst case); 5 positions exceed the 30s `fixedRate` period → concurrent `evaluateOpenPositions` → the root cause of duplicate tradeHistory/EXIT records (finding 5). No overlap guard, no per-symbol timeout budget.
38. **`enabled=false` freezes position management** — `:189-192, 704` — `/toggle` mid-day stops SL/1:2/EMA exits for held positions (only the 15:00 hard exit, which ignores `enabled`, survives) → **toggle converts open positions into unprotected exposure.**

### Signals, state & reporting

39. **`publishSignal` ignores the bus result** — `:2605-2618` + `ReactiveSignalEventBus:34-47` — `tryEmitNext` can return `FAIL_ZERO_SUBSCRIBER`/overflow; position already in `openPositions` → bot tracks a position the broker never received; later EXIT = naked reverse order live.
40. **`/morning-scan` callable any time, destroys mid-session state** — controller `:57-71` + service `:386-389` — no time guard: mid-day call does `activeSetups.clear()` **without the `exhaustedSymbols` check** replenish applies (:1658) → wipes armed/in-position markers (post-exit transitions no-op :1180/1311), resets `tradeAttempts`, allows trades beyond `maxAttemptsPerSymbol`, and if a re-selected symbol has an open position, a fresh setup can later `put`-overwrite the live position (:982).
41. **VWAP confirmation fails open** — `:856-906` — enabled but `vwap ≤ 0` (data worst) → entire block skipped, entry proceeds; a configured risk filter silently disabled exactly when data is bad.
42. **Sector filter fails open and can judge the wrong sector** — `:2090-2104, 2133-2135` — unregistered-sector symbol checked against `sectorState.topSector()` (unrelated 09:25 winner); `count==0` (all fetches failed) returns `true` → filter silently passes under exactly the rate-limiting conditions the code itself creates; no alert distinguishes "aligned" from "no data".
43. **`replaySession` swaps `telegramAlerts` without try/finally** — `:2516-2521` — exception leaves it `false` for process lifetime (all production alerts suppressed until restart); also mutates live `openPositions` and publishes real bus signals.
44. **`/notify` bypasses `telegramAlerts` gate and hardcodes `dispatched: true`** — `:2576-2580` + controller `:74-89` — sends even when gated off, reports success regardless.
45. **Expiry resolution inconsistent: quotes roll, order symbols don't** — `:1034-1035, 964, 2586-2590` vs `:1925-1930` — entry/exit symbols use this-month unrolled expiry; `fetchOptionLtp` uses `minDte=1` rolled expiry → on expiry day and post-monthly-expiry, **symbols reference expired contracts** (orders rejected, quotes 404).
46. **Mid-morning breadth hardcoded** — `:1726` + `Scanner:29-31` — 1-arg overload uses 56.0, ignoring operator-tuned `minBreadthPct` for all post-10:30 decisions.
47. **Spec-vs-code exit rules** — `:954-956, 1464-1465, 1290` vs `lowest_volume_reversal_spec.md:75-76` — spec: Target1 = **1:4 RR** trailed by **Supertrend(10,3)**; code: 1:2 + 10-EMA, and `FULL_TARGET_1_4` alias still uses 1:2-computed targets (selecting "1.4" can't produce 1:4). *Note the specs conflict with each other — confirm authority before changing money logic.*

---

## 🟢 LOW

48. **`max-attempts-per-symbol` default 1 vs design doc §4.3 = 2** — `:1113-1116` + `application.properties:14`.
49. **Telegram "Remaining Attempts" hardcodes `2 - attempts`** — `:1253-1255` — ignores config; wrong with default 1.
50. **Equal-volume tie-break not implemented** — `:537, 573` — strict `<` means a tie neither re-anchors nor arms (spec §3.2 says most recent tie becomes trigger candle) → trigger stays on older, farther candle.
51. **Armed alert prints `Pullback Vol: X (< day lowest Y)` where X == Y** — `:571-572` vs `:674-682`.
52. **Zero-volume first 3 candles brick a symbol for the day** — `:485-487` — `baselineLowest=0`, `v > 0 && v < 0` never true.
53. **Scan exceptions swallowed after day already latched** — `:411, 450-451` — `universeScanCompletedToday=true` set before reservoir loop; throw mid-loop → partial reservoir, no alert, no re-run.
54. **Sentiment flip leaves stale `currentTopGainers`** — `:393-409` — only current direction's lists cleared; LONG attempt → SHORT success shows yesterday's gainers all day.
55. **`lastMidMorningRefreshTime` set before doing work** — `:1712` — failed refresh burns the 30-min cooldown.
56. **`resolveAtmStrike` can return strike 0** — `:1778-1782` — `spot < step/2` → `…0CE` contract, failed lookups, fallback premiums.
57. **`fetchOptionLtp` fallback can resolve a wrong token** — `:1923-1938` + `ShoonyaMarketDataService:240` — `resolveToken` returns first fuzzy SearchScrip hit on no match (never invalidated), exchange hardcoded `"NFO"` → exit priced off wrong instrument.
58. **`replenishActiveCandidatesIfNeeded` non-atomic** — `:1656-1664` — concurrent callers double-promote or `put` over an existing setup (discarding its `tradeAttempts`); skipped symbols dropped from reservoir permanently (:1657).
59. **Setup mutated before position committed** — `:931-933` — exception between `transitionTo(IN_POSITION)` and `openPositions.put` (swallowed :922) → setup stuck `IN_POSITION` with no position → **symbol silently dead for the day** (:590 skips it).
60. **PARTIAL_EXIT signal permanently lost after publish failure** — `:1362→1420` — `partialBooked=true` means target branch never re-fires → consumer holds full size with no retry.
61. **`nowTime` param of `evaluateOpenPositions` never used** — `:1129-1130` — no internal time guard; all 13:00/15:00 safety depends on callers.
62. **Runner EMA uses unfiltered 5-day window** — `:1466-1473` + `ShoonyaMarketDataService:140` — `fetch5MinCandles(symbol,2)` clamped to 5 days and **never filtered to today** (unlike :605-621) → "10-EMA" seeded across overnight gaps from up to 4 prior sessions.
63. **`plannedRisk` uses stock points × option units (delta=1)** — `:939-948` — ~2× overstatement in `/positions`, sizing off wrong denominator.
64. **Mixed clocks in same class** — `:605, 1822, 1927` vs `:194, 2229` — `LocalDate.now(IST)` ignores the injectable `clock` → replay/backtest with fixed clock diverges from production (candle filters and P&L date boundary disagree).
65. **`TIME_EVALUATION_START` (09:30) defined but never enforced** — `:56`; also `:226` uses `isAfter(13:00)` while `:713` uses `isBefore` → manual cycle at exactly 13:00:00 can **arm a setup the live check will refuse to fire** (misleading alert).
66. **Dead knobs**: `telegram-armed-alerts` (`:110`, injected, never read), `scanner-fallback-cutoff` (properties:28, never bound), `live-breach-check-enabled` (spec §3, absent) — operators can't configure what's advertised.
67. **Toggle fields non-volatile** — `:71, 107`, scheduler `:29` — `/toggle` writes from HTTP threads have no happens-before with scheduler threads → may never be observed.
68. **`replaySession` mutates live production state** — `:2516-2521` — writes real `openPositions`, publishes real bus signals, never removes replayed positions → phantom closed positions re-processed by `evaluateOpenPositions` → bogus EXIT (feeds finding 5).
69. **`/scan` can block HTTP >60s** — controller `:38-52` + `:1986-1993` — 115ms×~500 symbols inline; second concurrent request hits `isScanning` CAS and returns "SUCCESS" having done nothing.
70. **`/status` shows only realized P&L, no unrealized** — controller `:92-131` — operator sees healthy P&L while open positions carry large floating losses (compounds finding 14).
71. **Telegram money `String.format` has no locale** — `:1009-1028, 1222-1233, 1439-1453` — non-US JVM renders `1.234,56` in every alert; also `sendAsync` fire-and-forget + unordered (`TelegramService:837-845`) → out-of-order/dropped audit trail.
72. **`scheduledDailyReset` ignores `schedulerEnabled`** — scheduler `:40-53` — disabling the scheduler still wipes state at 09:15.
73. **Hardcoded "15:00 IST" log; `executeHardExit(nowTime)` ignores its argument** — `:1557` + `TelegramBotCommandListener:235` passes `LocalTime.now()` in system zone (not IST).
74. **Duplicate quote fetch on cache miss** — `:721-723, 743-744` — `computeIfAbsent` returns null unstored, then `checkSpotTriggerBreach` refetches → 2 API calls per failed quote per tick.
75. **`Thread.sleep(115ms)` inside the scheduled cycle** — `:1986-1993` — interrupt handling correct, but an interrupted run proceeds to rank sectors with a **partial** quote set.

---

## ✅ Verified correct (checked, no bug)

- SL-before-target ordering in live (:1151→1274) and replay (:2310→2323) paths; all LONG/SHORT `>`/`<`/`>=`/`<=` comparisons for SL, target, trigger, slippage (:818-836), VWAP (:862-866).
- Telegram P&L direction: LONG `exit−entry`, SHORT `entry−exit` for futures; options always `exit−entry` (:1236, 1338, 242, 207).
- Circuit-breaker condition `todayPnl <= -maxDailyLoss` (:2242) and IST exit-date filtering (:2228-2237); `<` gate comparisons for `maxConcurrentTrades`/`maxAttemptsPerSymbol`.
- `exitQty` computed *before* `close()` in SL/full-target/EMA/EOD paths (only partial-closed path :1387 is wrong).
- `Thread.sleep` interrupt flag restored (:1989-1991); `compareTo`-based BigDecimal (no `equals` misuse), guarded ATM division (:1779), P&L scaled `HALF_UP`.
- Spring 6-field cron correctness: `20 */5 9-15` = seconds=20, minutes 0-55 → cycles 09:25–15:00:20; hard exit double-guaranteed (dedicated 15:00:00 job + `runCycle` check :201); 13:00–15:00 coverage complete (no NSE lunch break).
