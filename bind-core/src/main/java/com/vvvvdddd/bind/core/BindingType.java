package com.vvvvdddd.bind.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BindingType(String id, int version, String name, boolean enabled, int limit,
                          List<FieldDefinition> fields, List<String> dedupeFields) {
    public BindingType {
        CoreChecks.identifier(id);
        CoreChecks.displayName(name, true);
        CoreChecks.require(version >= 1 && (limit == -1 || limit >= 1), ErrorCode.INCOMPATIBLE_SCHEMA);
        CoreChecks.require(fields != null && !fields.isEmpty() && fields.size() <= InputLimits.MAX_FIELDS
                && dedupeFields != null && !dedupeFields.isEmpty() && dedupeFields.size() <= fields.size(),
                ErrorCode.INCOMPATIBLE_SCHEMA);
        Set<String> identifiers = new HashSet<>();
        boolean optionalSeen = false;
        for (FieldDefinition field : fields) {
            CoreChecks.require(field != null && identifiers.add(field.id()), ErrorCode.INCOMPATIBLE_SCHEMA);
            CoreChecks.require(!optionalSeen || !field.required(), ErrorCode.INCOMPATIBLE_SCHEMA);
            optionalSeen |= !field.required();
        }
        Set<String> selected = new HashSet<>();
        for (String fieldId : dedupeFields) {
            CoreChecks.require(fieldId != null && identifiers.contains(fieldId) && selected.add(fieldId),
                    ErrorCode.INCOMPATIBLE_SCHEMA);
        }
        fields = List.copyOf(fields);
        dedupeFields = List.copyOf(dedupeFields);
    }
}
