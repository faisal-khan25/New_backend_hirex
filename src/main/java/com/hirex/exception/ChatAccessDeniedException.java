package com.hirex.exception;

/**
 * Thrown when a user attempts a chat action they are not authorized to
 * perform — e.g. trying to delete-for-everyone a message they didn't send,
 * or accessing a conversation they are not a participant of.
 *
 * Mapped to HTTP 403 Forbidden by GlobalExceptionHandler, instead of falling
 * through to the generic RuntimeException handler (which previously
 * returned 400 Bad Request for ALL runtime errors, making it impossible
 * for clients to distinguish "bad input" from "not allowed").
 */
public class ChatAccessDeniedException extends RuntimeException {
    public ChatAccessDeniedException(String message) {
        super(message);
    }
}