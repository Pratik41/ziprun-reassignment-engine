package com.ziprun.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Single place where exceptions become HTTP responses.
 *
 * Controllers and services throw domain exceptions; this class maps them to
 * status codes and one consistent error body:
 *   { "status": 409, "error": "Conflict", "message": "...", "path": "/suggestions/X", "timestamp": "..." }
 *
 * 400 - malformed input (validation, unknown enum value, unreadable body)
 * 404 - resource does not exist
 * 409 - valid request that conflicts with current state (illegal transition)
 * 500 - anything unexpected (message is generic; details stay in the log)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(int status, String error, String message, String path,
                           LocalDateTime timestamp, List<String> details) {
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ApiError> conflict(InvalidStateException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, e.getMessage(), req, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", req, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body: " + rootMessage(e), req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", req, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req, List<String> details) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message,
            req.getRequestURI(), LocalDateTime.now(), details);
        return ResponseEntity.status(status).body(body);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        // Jackson messages include source locations after a newline; keep the first line
        return msg == null ? "unreadable" : msg.split("\n")[0];
    }
}
