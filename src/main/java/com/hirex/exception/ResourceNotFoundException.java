package com.hirex.exception;

/**
 * Thrown when a requested entity (message, application, user) does not
 * exist. Mapped to HTTP 404 Not Found by GlobalExceptionHandler.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}