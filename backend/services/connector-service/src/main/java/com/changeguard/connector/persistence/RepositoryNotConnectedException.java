package com.changeguard.connector.persistence;

/** Raised when an installation or selected repository is no longer active. */
public class RepositoryNotConnectedException extends IllegalArgumentException {
    public RepositoryNotConnectedException() {
        super("Active organization-owned GitHub repository is required");
    }
}
