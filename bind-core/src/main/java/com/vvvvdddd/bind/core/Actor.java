package com.vvvvdddd.bind.core;

import java.util.UUID;

public record Actor(String kind, UUID uuid) {
    public Actor {
        CoreChecks.require(("PLAYER".equals(kind) && uuid != null) || ("CONSOLE".equals(kind) && uuid == null),
                ErrorCode.INVALID_INPUT);
    }

    public static Actor player(UUID uuid) {
        return new Actor("PLAYER", uuid);
    }

    public static Actor console() {
        return new Actor("CONSOLE", null);
    }
}
