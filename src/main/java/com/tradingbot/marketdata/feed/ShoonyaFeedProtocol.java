package com.tradingbot.marketdata.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wire protocol helpers for the Shoonya (Finvasia NorenAPI) streaming market-data gateway.
 *
 * <p>All methods are pure: they build outbound frames or classify inbound ones, with no socket
 * involved. That keeps the stateful parts of {@link ShoonyaFeedClient} trivially unit-testable.
 *
 * <h2>The {@code tk} collision</h2>
 *
 * <p>The gateway's message type lives in {@code t}. A full snapshot is also type {@code "tk"}, and
 * <em>inside that payload</em> {@code tk} is reused as the instrument token field and {@code e} as
 * the exchange. Reading {@code tk} as if it were both would silently corrupt the subscription key,
 * so identity is always taken from {@code e}/{@code tk} and never from {@code t}.
 *
 * <h2>Snapshot versus diff</h2>
 *
 * <p>Subscribing yields one {@link MessageKind#KEY_SNAPSHOT} per instrument carrying the complete
 * state, followed by {@link MessageKind#KEY_DIFF} frames that carry only the fields that changed. A
 * diff must be merged onto the existing state, never treated as a full quote — doing so would blank
 * out the previous close and therefore distort the advance/decline breadth calculation.
 *
 * @author trading-bot
 * @since 1.0
 */
public final class ShoonyaFeedProtocol {

    /** Gateway message kinds recognised by this client, keyed on the {@code t} field. */
    public enum MessageKind {
        /** Connect acknowledgement: {@code {"t":"ak","s":"Ok"}}. */
        AUTH_ACK,
        /** Full instrument snapshot following a subscribe. */
        KEY_SNAPSHOT,
        /** Incremental diff carrying only changed fields. */
        KEY_DIFF,
        /** Unsubscribe acknowledgement: {@code {"t":"uk"}}. */
        UNSUB_ACK,
        /** Key/subscription rejection, e.g. an unknown or unsubscribable token. */
        KEY_ERROR,
        /** Protocol or server error, e.g. {@code {"t":"te","e":"..."}}, {@code {"t":"me"}}. */
        ERROR,
        /** Heartbeat, key-check or anything else that carries no quote state. */
        OTHER
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShoonyaFeedProtocol() {}

    // ---------------------------------------------------------------------
    // Outbound frames
    // ---------------------------------------------------------------------

    /**
     * Builds the connect frame that authenticates the session against the feed gateway.
     *
     * <p>The access token is the same {@code susertoken} produced by the REST OAuth flow, so the
     * feed reuses the existing {@code ShoonyaAuthenticator} session rather than demanding its own
     * login.
     *
     * @param uid user id
     * @param actid account id, normally the same as the user id
     * @param source application source identifier, {@code API} for REST-style integrations
     * @param accessToken the REST session token
     * @return the JSON connect frame
     */
    public static String authFrame(String uid, String actid, String source, String accessToken) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("t", "a");
        frame.put("uid", uid == null ? "" : uid);
        frame.put("actid", actid == null ? "" : actid);
        frame.put("source", source == null ? "API" : source);
        frame.put("accesstoken", accessToken == null ? "" : accessToken);
        return frame.toString();
    }

    /**
     * Builds the subscribe frames for the given instrument keys.
     *
     * @param keys {@code EXCHANGE|TOKEN} identifiers, e.g. {@code NSE|26000}
     * @param maxPerFrame maximum keys packed into a single frame; values below 1 are treated as 1
     * @return one or more subscribe frames; empty when {@code keys} is empty
     */
    public static List<String> subscribeFrames(Collection<String> keys, int maxPerFrame) {
        return framesFor("t", keys, maxPerFrame);
    }

    /**
     * Builds the unsubscribe frames for the given instrument keys.
     *
     * @param keys {@code EXCHANGE|TOKEN} identifiers
     * @param maxPerFrame maximum keys packed into a single frame; values below 1 are treated as 1
     * @return one or more unsubscribe frames; empty when {@code keys} is empty
     */
    public static List<String> unsubscribeFrames(Collection<String> keys, int maxPerFrame) {
        return framesFor("u", keys, maxPerFrame);
    }

    /**
     * Packs {@code keys} into one or more frames of the given type.
     *
     * @param type the gateway message type, {@code t} to subscribe or {@code u} to unsubscribe
     * @param keys {@code EXCHANGE|TOKEN} identifiers
     * @param maxPerFrame maximum keys per frame
     * @return the frames, in order
     */
    private static List<String> framesFor(String type, Collection<String> keys, int maxPerFrame) {
        List<String> out = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return out;
        }
        int limit = Math.max(1, maxPerFrame);
        List<String> batch = new ArrayList<>(limit);
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            batch.add(key);
            if (batch.size() == limit) {
                out.add(frameFor(type, batch));
                batch = new ArrayList<>(limit);
            }
        }
        if (!batch.isEmpty()) {
            out.add(frameFor(type, batch));
        }
        return out;
    }

    /**
     * Builds a single frame whose {@code k} field is the {@code #}-delimited key list.
     *
     * @param type the gateway message type
     * @param keys the keys to pack
     * @return the JSON frame
     */
    private static String frameFor(String type, Collection<String> keys) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("t", type);
        frame.put("k", String.join("#", keys));
        return frame.toString();
    }

    /**
     * Builds the canonical {@code EXCHANGE|TOKEN} subscription key.
     *
     * @param exchange exchange code, e.g. {@code NSE}
     * @param token instrument token
     * @return the subscription key, or null when either part is blank
     */
    public static String instrumentKey(String exchange, String token) {
        if (exchange == null || exchange.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        return exchange.trim().toUpperCase() + "|" + token.trim();
    }

    // ---------------------------------------------------------------------
    // Inbound classification
    // ---------------------------------------------------------------------

    /**
     * Classifies an inbound frame by its {@code t} field.
     *
     * @param node the parsed frame
     * @return the recognised kind; {@link MessageKind#OTHER} for heartbeats and unknown types
     */
    public static MessageKind classify(JsonNode node) {
        if (node == null) {
            return MessageKind.OTHER;
        }
        String type = node.path("t").asText("");
        return switch (type) {
            case "ak" -> MessageKind.AUTH_ACK;
            case "tk" -> MessageKind.KEY_SNAPSHOT;
            case "tf" -> MessageKind.KEY_DIFF;
            case "uk" -> MessageKind.UNSUB_ACK;
            case "ke" -> MessageKind.KEY_ERROR;
            case "te", "ue", "me" -> MessageKind.ERROR;
            default -> MessageKind.OTHER;
        };
    }

    /**
     * Returns true when an {@link MessageKind#AUTH_ACK} frame reports success.
     *
     * <p>The gateway signals refusal with {@code s} set to something other than {@code Ok}, so a
     * rejected token or an already-open session surfaces here rather than as a protocol error.
     *
     * @param node the parsed frame
     * @return true when the session is authenticated
     */
    public static boolean isAuthenticated(JsonNode node) {
        return node != null && "Ok".equalsIgnoreCase(node.path("s").asText(""));
    }

    /**
     * Extracts the instrument identity from a snapshot or diff frame.
     *
     * @param node the parsed frame
     * @return the {@code EXCHANGE|TOKEN} key, or null when the frame carries no usable identity
     */
    public static String instrumentKeyOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        return instrumentKey(node.path("e").asText(null), node.path("tk").asText(null));
    }

    /**
     * Extracts a human-readable error message from an error or key-error frame.
     *
     * @param node the parsed frame
     * @return the server message, or a placeholder when absent
     */
    public static String errorMessage(JsonNode node) {
        if (node == null) {
            return "unknown feed error";
        }
        String msg = node.path("emsg").asText("");
        if (msg.isBlank()) {
            msg = node.path("e").asText("");
        }
        return msg.isBlank() ? "unknown feed error" : msg;
    }

    /**
     * Parses a frame. Non-JSON payloads (proxies sometimes interleave HTML) yield null rather than
     * throwing, so a single malformed frame cannot tear down the connection.
     *
     * @param text the raw frame
     * @return the parsed node, or null when the frame is not JSON
     */
    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a {@code k} field back into its constituent {@code EXCHANGE|TOKEN} keys.
     *
     * @param joined the {@code #}-delimited key list
     * @return the individual keys, never null
     */
    public static List<String> splitKeys(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : joined.split("#")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Field access
    // ---------------------------------------------------------------------

    /**
     * Reads a numeric field, treating absent, null, empty and non-numeric values as the supplied
     * default. The gateway sends numbers as JSON strings, so an empty string is a real possibility.
     *
     * @param node the frame
     * @param field the field name
     * @param fallback value to use when the field cannot be read
     * @return the parsed value
     */
    public static double doubleField(JsonNode node, String field, double fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.asDouble(fallback);
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a numeric field as a long, treating absent, null, empty and non-numeric values as the
     * supplied default.
     *
     * @param node the frame
     * @param field the field name
     * @param fallback value to use when the field cannot be read
     * @return the parsed value
     */
    public static long longField(JsonNode node, String field, long fallback) {
        double asDouble = doubleField(node, field, fallback);
        return (long) asDouble;
    }

    /**
     * Interprets the gateway's {@code ft} feed-time field.
     *
     * <p>The gateway emits feed time either as epoch seconds or as a {@code HH:mm:ss} wall clock in
     * IST. Both are tolerated; a value that matches neither yields null so the caller can mark the
     * tick as having an unknown feed timestamp rather than inventing one.
     *
     * @param node the frame
     * @return the feed instant, or null when absent or unparseable
     */
    public static Instant feedTime(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get("ft");
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(text));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            LocalTime time = LocalTime.parse(text);
            return time.atDate(java.time.LocalDate.now(IST)).atZone(IST).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a gateway frame into an {@link ArrayNode} if it is an array, otherwise null. Retained
     * for symmetry with {@link #parse(String)}; the streaming feed currently emits single objects.
     *
     * @param text the raw frame
     * @return the array node, or null
     */
    public static ArrayNode parseArray(String text) {
        JsonNode node = parse(text);
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }
}
