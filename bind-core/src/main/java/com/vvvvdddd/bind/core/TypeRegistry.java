package com.vvvvdddd.bind.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class TypeRegistry {
    private final List<BindingType> types;
    private final Map<String, BindingType> byId;
    private final Map<String, BindingType> byName;

    public TypeRegistry(Collection<BindingType> types) {
        CoreChecks.require(types != null && !types.isEmpty() && types.size() <= InputLimits.MAX_TYPES,
                ErrorCode.INCOMPATIBLE_SCHEMA);
        Map<String, BindingType> ids = new LinkedHashMap<>();
        Map<String, BindingType> names = new LinkedHashMap<>();
        for (BindingType type : types) {
            CoreChecks.require(type != null, ErrorCode.INCOMPATIBLE_SCHEMA);
            CoreChecks.require(ids.putIfAbsent(type.id(), type) == null
                    && names.putIfAbsent(type.name().toLowerCase(Locale.ROOT), type) == null,
                    ErrorCode.INCOMPATIBLE_SCHEMA);
        }
        this.types = List.copyOf(ids.values());
        this.byId = Map.copyOf(ids);
        this.byName = Map.copyOf(names);
    }

    public List<BindingType> all() {
        return types;
    }

    public Optional<BindingType> findId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<BindingType> findName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }
}
