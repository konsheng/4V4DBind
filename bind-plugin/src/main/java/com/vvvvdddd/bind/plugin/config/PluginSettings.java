package com.vvvvdddd.bind.plugin.config;

import com.vvvvdddd.bind.core.TypeRegistry;
import com.vvvvdddd.bind.plugin.message.MessageCatalog;

import java.time.ZoneId;
import java.util.Objects;

public record PluginSettings(
        TypeRegistry types,
        MessageCatalog messages,
        int pageSize,
        int queueCapacity,
        int busyTimeoutMillis,
        int rateBurst,
        double ratePerSecond,
        int maxInFlight,
        ZoneId zone
) {
    public PluginSettings {
        Objects.requireNonNull(types);
        Objects.requireNonNull(messages);
        Objects.requireNonNull(zone);
    }
}
