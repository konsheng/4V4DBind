package com.vvvvdddd.bind.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record BindingRecord(long id, UUID owner, String typeId, int typeVersion, Map<String, String> values,
                            Instant createdAt, Actor actor) {
    public BindingRecord {
        CoreChecks.require(id >= 1 && owner != null && typeId != null && typeVersion >= 1 && values != null
                && createdAt != null && actor != null, ErrorCode.INVALID_INPUT);
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public String toString() {
        return "BindingRecord[id=" + id + ", owner=" + owner + ", type=" + typeId + "]";
    }
}
