package com.changeguard.change.exception;

/** A contradictory replay must roll back rather than silently overwrite a provider fact. */
public class EventProcessingException extends RuntimeException {
    public EventProcessingException(String message) { super(message); }
    public EventProcessingException(String message, Throwable cause) { super(message, cause); }
}
