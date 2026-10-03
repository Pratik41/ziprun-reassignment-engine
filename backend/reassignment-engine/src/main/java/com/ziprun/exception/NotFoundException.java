package com.ziprun.exception;

/**
 * Thrown when a requested resource (order, agent, suggestion) does not exist.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String resource, String id) {
        super(resource + " not found: " + id);
    }
}
