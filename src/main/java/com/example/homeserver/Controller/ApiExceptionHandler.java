package com.example.homeserver.Controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {LibraryApiController.class, NativeMutationApiController.class})
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException error) {
        String reason = error.getReason() == null ? "REQUEST_FAILED" : error.getReason();
        int separator = reason.indexOf(':');
        String code = separator < 0 ? reason : reason.substring(0, separator);
        String message = separator < 0 ? "処理を完了できませんでした。" : reason.substring(separator + 1);
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", Map.of(
                "code", code,
                "message", message,
                "retryable", false,
                "timestamp", Instant.now().toString())));
    }
}
