package com.changeguard.change.change;

/** Common receipt identity keeps replay checks consistent across supported lifecycle types. */
public interface ReceiptEvent {
    String eventId();
    String organizationId();
    String fingerprint();
}
