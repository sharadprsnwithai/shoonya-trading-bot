package com.tradingbot.util;

import java.util.Locale;

/**
 * The single locale every user-visible message and pattern in the bot is formatted in.
 *
 * <p>This exists because the codebase previously disagreed with itself: forty call sites pinned
 * {@code Locale.US} and sixty took the JVM default, so the same trade could report its stop loss
 * one way and its target another depending on the machine the bot happened to run on. A container
 * defaulting to {@code POSIX} or {@code en_US} and a developer laptop in {@code de_DE} produced
 * different Telegram messages from identical code.
 *
 * <p>{@code en_IN} is the correct choice for an NSE bot. It keeps English month names and a dot
 * decimal separator while adding Indian digit grouping, so a large figure reads the way the market
 * reads it:
 *
 * <pre>
 *   Locale.US   -&gt;  1,234,567.89
 *   en_IN       -&gt;  12,34,567.89
 * </pre>
 *
 * <p>Hard-coding the tag rather than calling {@link Locale#getDefault()} is deliberate. The default
 * varies with the host, and a bot whose alert formatting depends on where it is deployed will
 * eventually produce inconsistent output in front of a user. Formats that are part of the trading
 * logic rather than presentation - timestamps sent to a broker, say - should keep their own
 * explicit locale and not reach for this constant.
 *
 * @author trading-bot
 * @since 1.0
 */
public final class BotLocale {

    /** The locale used for every user-facing message and display pattern. */
    public static final Locale INSTANCE = Locale.forLanguageTag("en-IN");

    private BotLocale() {}
}
