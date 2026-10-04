package com.changeguard.connector.github;

/**
 * Signals that a GitHub payload is invalid or cannot be processed.
 */
public class InvalidGitHubPayloadException extends RuntimeException {

    /**
     * Constructs an exception for missing or invalid webhook fields.
     *
     * @param message explanation of the validation failure
     */
    public InvalidGitHubPayloadException(String message) {
        super(message);
    }

    /**
     * Constructs an exception for an invalid GitHub payload.
     *
     * @param message explanation of why the payload is invalid
     * @param cause underlying cause of the error
     */
    public InvalidGitHubPayloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
