package com.tradingbot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ShoonyaAuthenticatorTest {

    @Test
    void testDisabledAuthenticatorReturnsMockToken() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        config.setUserId("USER123");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        String token = auth.getOrAuthenticateToken();

        assertThat(token).isEqualTo("mock_shoonya_token_USER123");
    }

    @Test
    void testExplicitAccessTokenUsedDirectly() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setAccessToken("explicit_access_token_abc");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        String token = auth.getOrAuthenticateToken();

        assertThat(token).isEqualTo("explicit_access_token_abc");
    }

    @Test
    void testInvalidateSessionClearsToken() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        config.setUserId("USER123");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        auth.getOrAuthenticateToken();
        auth.invalidateSession();

        // Should return a fresh mock token
        String token = auth.getOrAuthenticateToken();
        assertThat(token).isEqualTo("mock_shoonya_token_USER123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidateSessionTokenReturnsTrueOnOkResponse() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> okResp = mock(HttpResponse.class);
        when(okResp.statusCode()).thenReturn(200);
        when(okResp.body()).thenReturn("{\"stat\":\"Ok\",\"actid\":\"USER123\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(okResp);

        ShoonyaAuthenticator auth =
                new ShoonyaAuthenticator(config, new ObjectMapper(), mockClient);
        boolean isValid = auth.validateSessionToken("test_valid_token");

        assertThat(isValid).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidateSessionTokenReturnsFalseOn401() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> errResp = mock(HttpResponse.class);
        when(errResp.statusCode()).thenReturn(401);
        when(errResp.body()).thenReturn("{\"stat\":\"Not_Ok\",\"emsg\":\"Session Expired\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(errResp);

        ShoonyaAuthenticator auth =
                new ShoonyaAuthenticator(config, new ObjectMapper(), mockClient);
        boolean isValid = auth.validateSessionToken("test_expired_token");

        assertThat(isValid).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHeadlessLoginReportsHttp502HtmlInsteadOfJsonParseError() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> resp502 =
                response(
                        502,
                        "<html><head><title>502 Bad Gateway</title></head>"
                                + "<body><center><h1>502 Bad Gateway</h1></center></body></html>");
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp502);

        ShoonyaAuthenticator auth =
                new ShoonyaAuthenticator(oauthConfig(), new ObjectMapper(), mockClient);

        assertThatThrownBy(auth::executeHeadlessLogin)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("QuickAuth")
                .hasMessageContaining("HTTP 502")
                .hasMessageNotContaining("Unexpected character");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHeadlessLoginReportsGenAcsTokHtmlResponse() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> quickAuthOk =
                response(200, "{\"stat\":\"Ok\",\"code\":\"AUTHCODE123\"}");
        HttpResponse<String> genAcsHtml = response(200, "<html><body>maintenance</body></html>");
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(quickAuthOk, genAcsHtml);

        ShoonyaAuthenticator auth =
                new ShoonyaAuthenticator(oauthConfig(), new ObjectMapper(), mockClient);

        assertThatThrownBy(auth::executeHeadlessLogin)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("GenAcsTok")
                .hasMessageContaining("HTTP 200")
                .hasMessageContaining("<html>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidateSessionTokenReturnsFalseOnHtmlErrorPage() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> resp502 =
                response(502, "<html><head><title>502 Bad Gateway</title></head></html>");
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp502);

        ShoonyaAuthenticator auth =
                new ShoonyaAuthenticator(oauthConfig(), new ObjectMapper(), mockClient);

        assertThat(auth.validateSessionToken("some_token")).isFalse();
    }

    private static ShoonyaConfig oauthConfig() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");
        config.setPassword("secret");
        config.setTotpSecret("");
        config.setClientId("client123");
        config.setSecretKey("secret123");
        config.setVendorCode("NOREN_API");
        config.setBaseUrl("https://api.shoonya.com");
        config.setPublicIp("1.2.3.4");
        return config;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
