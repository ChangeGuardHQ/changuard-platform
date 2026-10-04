/**
 * Thrown when a GitHub webhook request contains an invalid signature.
 */
package com.changeguard.connector.github;

public class InvalidGitHubSignatureException extends RuntimeException {

    /**
     * Creates a new exception with a descriptive message.
     *
     * @param message the reason the signature validation failed
     */
    public InvalidGitHubSignatureException(String message) {
        super(message);
    }
}
