package com.tradingbot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void testHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid strike price");
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("Bad Request");
        assertThat(response.getBody().get("message")).isEqualTo("Invalid strike price");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void testHandleMissingParams() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("orderId", "String");
        ResponseEntity<Map<String, Object>> response = handler.handleMissingParams(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("Missing Parameter");
    }

    @Test
    void testHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Position already closed");
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(409);
        assertThat(response.getBody().get("error")).isEqualTo("Conflict");
        assertThat(response.getBody().get("message")).isEqualTo("Position already closed");
    }

    @Test
    void testHandleGeneralException() {
        RuntimeException ex = new RuntimeException("Unexpected broker network error");
        ResponseEntity<Map<String, Object>> response = handler.handleGeneralException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(500);
        assertThat(response.getBody().get("error")).isEqualTo("Internal Server Error");
        assertThat(response.getBody().get("message")).isEqualTo("Unexpected broker network error");
    }
}
