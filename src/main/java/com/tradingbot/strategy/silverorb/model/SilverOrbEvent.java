package com.tradingbot.strategy.silverorb.model;

/**
 * Immutable signal emitted by the Silver ORB engine during a cycle.
 *
 * @param type ENTRY or EXIT
 * @param trade the affected trade (open on ENTRY, settled on EXIT)
 */
public record SilverOrbEvent(SilverOrbEventType type, SilverOrbTrade trade) {}
