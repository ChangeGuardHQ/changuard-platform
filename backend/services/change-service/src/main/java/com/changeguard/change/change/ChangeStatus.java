package com.changeguard.change.change;

/** Only observed states are represented; a late opening never reverses MERGED. */
public enum ChangeStatus {
    OPEN, MERGED, COMMITTED
}
