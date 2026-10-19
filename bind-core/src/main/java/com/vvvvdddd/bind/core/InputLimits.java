package com.vvvvdddd.bind.core;

public final class InputLimits {
    public static final int MAX_TYPES = 32;
    public static final int MAX_FIELDS = 8;
    public static final int MAX_FIELD_CODE_POINTS = 1024;
    public static final int MAX_FIELD_BYTES = 4096;
    public static final int MAX_TOTAL_CODE_POINTS = 2048;
    public static final int MAX_TOTAL_BYTES = 8192;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1_000_000;

    private InputLimits() {
    }
}
