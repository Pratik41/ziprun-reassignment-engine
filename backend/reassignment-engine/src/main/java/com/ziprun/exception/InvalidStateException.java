package com.ziprun.exception;

/**
 * Thrown when a request is well-formed but conflicts with the current state
 * of a resource (e.g. an illegal state-machine transition, accepting an
 * already-decided suggestion, reassigning to an OFFLINE agent).
 * Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class InvalidStateException extends RuntimeException {
    public InvalidStateException(String message) {
        super(message);
    }
}
