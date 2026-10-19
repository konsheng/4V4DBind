package com.vvvvdddd.bind.core;

import java.util.Objects;

public final class BindingException extends RuntimeException {
    private final ErrorCode code;

    public BindingException(ErrorCode code) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
