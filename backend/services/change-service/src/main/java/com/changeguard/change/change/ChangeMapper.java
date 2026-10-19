package com.changeguard.change.change;

import org.springframework.stereotype.Component;

/** Owns conversion into the immutable, database-owned change projection. */
@Component
public class ChangeMapper {
    public ChangeRecord newRecord(MergedChange merge) {
        return new ChangeRecord(merge);
    }
}
