package com.tradingbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthFilterTest {

    private static final String TOKEN = "s3cret-token";

    private ApiAuthFilter filterWith(String token) {
        ApiAuthFilter filter = new ApiAuthFilter();
        filter.setAuthToken(token);
        return filter;
    }

    private MockHttpServletResponse run(ApiAuthFilter filter, String method, String path)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() != 401) {
            assertNotNull(chain.getRequest(), "request must pass through the filter chain");
        }
        return response;
    }

    @Test
    @DisplayName("GET passes without a token even when auth is configured")
    void testGetAlwaysAllowed() throws Exception {
        MockHttpServletResponse response =
                run(filterWith(TOKEN), "GET", "/api/strategy/lowest-volume/status");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Mutating request passes when no token is configured (H6 WARN mode)")
    void testUnauthenticatedWhenDisabled() throws Exception {
        MockHttpServletResponse response =
                run(filterWith(""), "POST", "/api/strategy/lowest-volume/reset");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("POST without the bearer header is rejected 401 when auth is configured")
    void testPostRejectedWithoutToken() throws Exception {
        MockHttpServletResponse response =
                run(filterWith(TOKEN), "POST", "/api/strategy/lowest-volume/reset");
        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentAsString());
    }

    @Test
    @DisplayName("POST with a wrong bearer token is rejected 401")
    void testPostRejectedWithWrongToken() throws Exception {
        ApiAuthFilter filter = filterWith(TOKEN);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/strategy/lowest-volume/toggle");
        request.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("POST with Authorization: Bearer <token> passes")
    void testPostAcceptedWithBearerToken() throws Exception {
        ApiAuthFilter filter = filterWith(TOKEN);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/strategy/lowest-volume/toggle");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("POST with X-Api-Token: <token> passes")
    void testPostAcceptedWithApiTokenHeader() throws Exception {
        ApiAuthFilter filter = filterWith(TOKEN);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/strategy/lowest-volume/scan");
        request.addHeader("X-Api-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("Non-/api paths are never blocked")
    void testNonApiPathPasses() throws Exception {
        MockHttpServletResponse response = run(filterWith(TOKEN), "POST", "/actuator/health");
        assertEquals(200, response.getStatus());
    }
}
