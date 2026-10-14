package com.tradingbot.strategy.silverorb.model;

/** Type of event emitted by the Silver ORB engine for the service to act on. */
public enum SilverOrbEventType {
    /** A new paper position was opened at a bar close. */
    ENTRY,
    /** The open position was settled (SL / TARGET / SQUAREOFF / FORCE_SQUAREOFF). */
    EXIT
}
