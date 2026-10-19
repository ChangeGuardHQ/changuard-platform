package com.changeguard.change.exception;

/** A scoped lookup deliberately does not reveal whether another organization owns the ID. */
public class ChangeNotFoundException extends RuntimeException {
    public ChangeNotFoundException() { super("Change not found"); }
}
