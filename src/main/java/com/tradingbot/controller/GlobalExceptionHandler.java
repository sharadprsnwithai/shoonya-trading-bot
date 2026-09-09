package com.tradingbot.controller;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global REST exception handler ensuring structured error responses across all API endpoints.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[REST] Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", HttpStatus.BAD_REQUEST.value(),
                                "error", "Bad Request",
                                "message", ex.getMessage() != null ? ex.getMessage() : "Invalid argument"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParams(
            MissingServletRequestParameterException ex) {
        log.warn("[REST] Missing parameter: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", HttpStatus.BAD_REQUEST.value(),
                                "error", "Missing Parameter",
                                "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[REST] Conflict / invalid state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", HttpStatus.CONFLICT.value(),
                                "error", "Conflict",
                                "message", ex.getMessage() != null ? ex.getMessage() : "Invalid state"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) {
        log.error("[REST] Unhandled internal server error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                "error", "Internal Server Error",
                                "message",
                                ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred"));
    }
}
