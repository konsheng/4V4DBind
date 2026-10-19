package com.vvvvdddd.bind.core;

public record FieldDefinition(String id, String name, String validator, boolean required, int maxLength, Mask mask) {
    public FieldDefinition {
        CoreChecks.identifier(id);
        CoreChecks.displayName(name, false);
        CoreChecks.identifier(validator);
        CoreChecks.require(maxLength >= 1 && maxLength <= InputLimits.MAX_FIELD_CODE_POINTS && mask != null,
                ErrorCode.INCOMPATIBLE_SCHEMA);
    }
}
