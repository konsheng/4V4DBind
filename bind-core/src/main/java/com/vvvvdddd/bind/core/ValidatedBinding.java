package com.vvvvdddd.bind.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ValidatedBinding(BindingType type, Map<String, String> values, String dedupeKey) {
    public ValidatedBinding {
        CoreChecks.require(type != null && values != null && dedupeKey != null, ErrorCode.INVALID_INPUT);
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public String toString() {
        return "ValidatedBinding[type=" + type.id() + ", fields=" + values.size() + "]";
    }
}
