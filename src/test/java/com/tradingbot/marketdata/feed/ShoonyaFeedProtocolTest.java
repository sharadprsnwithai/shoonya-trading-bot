package com.tradingbot.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.marketdata.feed.ShoonyaFeedProtocol.MessageKind;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure frame-building and classification layer. */
class ShoonyaFeedProtocolTest {

    @Test
    @DisplayName("auth frame carries the REST session token and the user as both uid and actid")
    void testAuthFrame() {
        String frame = ShoonyaFeedProtocol.authFrame("AB1234", "AB1234", "API", "sess-token");

        JsonNode node = ShoonyaFeedProtocol.parse(frame);
        assertThat(node.path("t").asText()).isEqualTo("a");
        assertThat(node.path("uid").asText()).isEqualTo("AB1234");
        assertThat(node.path("actid").asText()).isEqualTo("AB1234");
        assertThat(node.path("source").asText()).isEqualTo("API");
        assertThat(node.path("accesstoken").asText()).isEqualTo("sess-token");
    }

    @Test
    @DisplayName("auth frame degrades to empty strings rather than emitting JSON nulls")
    void testAuthFrameNullSafety() {
        JsonNode node =
                ShoonyaFeedProtocol.parse(ShoonyaFeedProtocol.authFrame(null, null, null, null));

        assertThat(node.path("uid").asText()).isEmpty();
        assertThat(node.path("accesstoken").asText()).isEmpty();
        assertThat(node.path("source").asText()).isEqualTo("API");
    }

    @Test
    @DisplayName("heartbeat frame carries no fields and classifies as carrying no quote state")
    void testHeartBeatFrame() {
        JsonNode node = ShoonyaFeedProtocol.parse(ShoonyaFeedProtocol.heartBeatFrame());

        assertThat(node.path("t").asText()).isEqualTo("hc");
        assertThat(node.size()).isEqualTo(1);

        // Must never be mistaken for a snapshot or a diff, or a heartbeat would blank the tick.
        assertThat(ShoonyaFeedProtocol.classify(node)).isEqualTo(MessageKind.OTHER);
    }

    @Test
    @DisplayName("subscribe frame packs keys into one # delimited list")
    void testSubscribeFrameSingleFrame() {
        List<String> frames =
                ShoonyaFeedProtocol.subscribeFrames(List.of("NSE|22", "NSE|26000"), 200);

        assertThat(frames).hasSize(1);
        JsonNode node = ShoonyaFeedProtocol.parse(frames.get(0));
        assertThat(node.path("t").asText()).isEqualTo("t");
        assertThat(node.path("k").asText()).isEqualTo("NSE|22#NSE|26000");
    }

    @Test
    @DisplayName("subscribe frames are chunked at the configured maximum")
    void testSubscribeFrameChunking() {
        List<String> frames =
                ShoonyaFeedProtocol.subscribeFrames(
                        List.of("NSE|1", "NSE|2", "NSE|3", "NSE|4", "NSE|5"), 2);

        assertThat(frames).hasSize(3);
        assertThat(splitKeyCount(frames.get(0))).isEqualTo(2);
        assertThat(splitKeyCount(frames.get(1))).isEqualTo(2);
        assertThat(splitKeyCount(frames.get(2))).isEqualTo(1);
    }

    @Test
    @DisplayName("a zero or negative chunk size still yields usable frames")
    void testSubscribeFrameDegenerateChunkSize() {
        List<String> frames = ShoonyaFeedProtocol.subscribeFrames(List.of("NSE|1", "NSE|2"), 0);

        assertThat(frames).hasSize(2);
    }

    @Test
    @DisplayName("blank and null keys are skipped instead of producing empty subscriptions")
    void testSubscribeFrameSkipsBlankKeys() {
        List<String> frames =
                ShoonyaFeedProtocol.subscribeFrames(Arrays.asList("NSE|1", null, "", "  "), 10);

        assertThat(frames).hasSize(1);
        assertThat(splitKeyCount(frames.get(0))).isEqualTo(1);
        assertThat(ShoonyaFeedProtocol.parse(frames.get(0)).path("k").asText()).isEqualTo("NSE|1");
    }

    @Test
    @DisplayName("a subscription of only blank keys yields no frames at all")
    void testSubscribeFrameOnlyBlankKeys() {
        assertThat(ShoonyaFeedProtocol.subscribeFrames(Arrays.asList(null, "", "  "), 10))
                .isEmpty();
        assertThat(ShoonyaFeedProtocol.subscribeFrames(List.of(), 10)).isEmpty();
        assertThat(ShoonyaFeedProtocol.subscribeFrames(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("unsubscribe frames use the u message type")
    void testUnsubscribeFrame() {
        List<String> frames = ShoonyaFeedProtocol.unsubscribeFrames(List.of("NSE|22"), 10);

        assertThat(frames).hasSize(1);
        assertThat(ShoonyaFeedProtocol.parse(frames.get(0)).path("t").asText()).isEqualTo("u");
    }

    @Test
    @DisplayName("instrument keys are upper-cased and normalised")
    void testInstrumentKey() {
        assertThat(ShoonyaFeedProtocol.instrumentKey("nse", " 26000 ")).isEqualTo("NSE|26000");
    }

    @Test
    @DisplayName("blank exchange or token yields a null key")
    void testInstrumentKeyBlankParts() {
        assertThat(ShoonyaFeedProtocol.instrumentKey(null, "26000")).isNull();
        assertThat(ShoonyaFeedProtocol.instrumentKey("NSE", " ")).isNull();
        assertThat(ShoonyaFeedProtocol.instrumentKey("", "26000")).isNull();
    }

    @Test
    @DisplayName(
            "the tk message type is classified as a full snapshot, not confused with the tk token field")
    void testClassifyTkDoesNotCollideWithTokenField() {
        // The gateway reuses "tk" as both the message type and the token field inside the payload.
        JsonNode node =
                ShoonyaFeedProtocol.parse(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"26000\",\"lp\":\"22343.00\",\"c\":\"22100.00\"}");

        assertThat(ShoonyaFeedProtocol.classify(node)).isEqualTo(MessageKind.KEY_SNAPSHOT);
        assertThat(ShoonyaFeedProtocol.instrumentKeyOf(node)).isEqualTo("NSE|26000");
    }

    @Test
    @DisplayName("message kinds are recognised across the documented gateway types")
    void testClassifyAllKinds() {
        assertThat(kindOf("{\"t\":\"ak\",\"s\":\"Ok\"}")).isEqualTo(MessageKind.AUTH_ACK);
        assertThat(kindOf("{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"22\"}"))
                .isEqualTo(MessageKind.KEY_DIFF);
        assertThat(kindOf("{\"t\":\"uk\"}")).isEqualTo(MessageKind.UNSUB_ACK);
        assertThat(kindOf("{\"t\":\"ke\",\"e\":\"Invalid token\"}"))
                .isEqualTo(MessageKind.KEY_ERROR);
        assertThat(kindOf("{\"t\":\"te\",\"e\":\"Not Connected\"}")).isEqualTo(MessageKind.ERROR);
        assertThat(kindOf("{\"t\":\"me\"}")).isEqualTo(MessageKind.ERROR);
        assertThat(kindOf("{\"t\":\"ck\"}")).isEqualTo(MessageKind.OTHER);
    }

    @Test
    @DisplayName(
            "auth acknowledgement success is decided by the s field, not by the message type alone")
    void testIsAuthenticated() {
        assertThat(
                        ShoonyaFeedProtocol.isAuthenticated(
                                ShoonyaFeedProtocol.parse("{\"t\":\"ak\",\"s\":\"Ok\"}")))
                .isTrue();
        assertThat(
                        ShoonyaFeedProtocol.isAuthenticated(
                                ShoonyaFeedProtocol.parse(
                                        "{\"t\":\"ak\",\"s\":\"Not_Ok\",\"e\":\"Invalid session\"}")))
                .isFalse();
        assertThat(ShoonyaFeedProtocol.isAuthenticated(null)).isFalse();
    }

    @Test
    @DisplayName("numeric fields tolerate the gateway's string encoding and empty values")
    void testDoubleFieldTolerance() {
        JsonNode node =
                ShoonyaFeedProtocol.parse("{\"lp\":\"3364.50\",\"c\":\"\",\"v\":\"12500\"}");

        assertThat(ShoonyaFeedProtocol.doubleField(node, "lp", -1)).isEqualTo(3364.50);
        assertThat(ShoonyaFeedProtocol.doubleField(node, "c", -1)).isEqualTo(-1.0);
        assertThat(ShoonyaFeedProtocol.doubleField(node, "absent", -1)).isEqualTo(-1.0);
        assertThat(ShoonyaFeedProtocol.longField(node, "v", 0)).isEqualTo(12500L);
        assertThat(ShoonyaFeedProtocol.longField(node, "absent", 42)).isEqualTo(42L);
    }

    @Test
    @DisplayName("feed time accepts both epoch seconds and HH:mm:ss wall clock")
    void testFeedTimeBothFormats() {
        assertThat(
                        ShoonyaFeedProtocol.feedTime(
                                ShoonyaFeedProtocol.parse("{\"ft\":\"1760000000\"}")))
                .isEqualTo(Instant.ofEpochSecond(1760000000L));

        assertThat(ShoonyaFeedProtocol.feedTime(ShoonyaFeedProtocol.parse("{\"ft\":\"14:31:05\"}")))
                .isNotNull();

        assertThat(ShoonyaFeedProtocol.feedTime(ShoonyaFeedProtocol.parse("{\"ft\":\"\"}")))
                .isNull();
        assertThat(ShoonyaFeedProtocol.feedTime(ShoonyaFeedProtocol.parse("{}"))).isNull();
        assertThat(ShoonyaFeedProtocol.feedTime(null)).isNull();
    }

    @Test
    @DisplayName("splitKeys round-trips the # delimited list including empty segments")
    void testSplitKeys() {
        assertThat(ShoonyaFeedProtocol.splitKeys("NSE|22#NSE|26000"))
                .containsExactly("NSE|22", "NSE|26000");
        assertThat(ShoonyaFeedProtocol.splitKeys("NSE|22##NSE|1"))
                .containsExactly("NSE|22", "NSE|1");
        assertThat(ShoonyaFeedProtocol.splitKeys("")).isEmpty();
        assertThat(ShoonyaFeedProtocol.splitKeys(null)).isEmpty();
    }

    @Test
    @DisplayName("non-JSON frames parse to null instead of raising")
    void testParseNonJson() {
        assertThat(ShoonyaFeedProtocol.parse("<html>502 Bad Gateway</html>")).isNull();
        assertThat(ShoonyaFeedProtocol.parse("")).isNull();
        assertThat(ShoonyaFeedProtocol.parse(null)).isNull();
    }

    @Test
    @DisplayName("error messages fall back gracefully when the frame carries none")
    void testErrorMessage() {
        assertThat(
                        ShoonyaFeedProtocol.errorMessage(
                                ShoonyaFeedProtocol.parse(
                                        "{\"t\":\"te\",\"e\":\"Not Connected\"}")))
                .isEqualTo("Not Connected");
        assertThat(
                        ShoonyaFeedProtocol.errorMessage(
                                ShoonyaFeedProtocol.parse(
                                        "{\"t\":\"te\",\"emsg\":\"Rate_Limited\"}")))
                .isEqualTo("Rate_Limited");
        assertThat(ShoonyaFeedProtocol.errorMessage(null)).isEqualTo("unknown feed error");
    }

    /**
     * Classifies a raw frame.
     *
     * @param json the raw frame
     * @return the recognised message kind
     */
    private static MessageKind kindOf(String json) {
        return ShoonyaFeedProtocol.classify(ShoonyaFeedProtocol.parse(json));
    }

    /**
     * Counts the keys inside a frame's {@code k} field.
     *
     * @param frame the raw frame
     * @return the number of keys
     */
    private static int splitKeyCount(String frame) {
        String joined = ShoonyaFeedProtocol.parse(frame).path("k").asText();
        return ShoonyaFeedProtocol.splitKeys(joined).size();
    }
}
