package com.vvvvdddd.bind.core;

import java.util.UUID;

public record PlayerIdentity(UUID uuid, String name) {
    public PlayerIdentity {
        CoreChecks.require(uuid != null, ErrorCode.INVALID_INPUT);
        CoreChecks.text(name, 64, 256, ErrorCode.INVALID_INPUT);
        CoreChecks.require(!name.isEmpty() && name.codePoints().noneMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)), ErrorCode.INVALID_INPUT);
    }
}
