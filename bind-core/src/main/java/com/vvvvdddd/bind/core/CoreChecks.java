package com.vvvvdddd.bind.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class CoreChecks {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private CoreChecks() {
    }

    static void require(boolean condition, ErrorCode code) {
        if (!condition) {
            throw new BindingException(code);
        }
    }

    static void identifier(String value) {
        require(value != null && IDENTIFIER.matcher(value).matches(), ErrorCode.INCOMPATIBLE_SCHEMA);
    }

    static void text(String value, int maxCodePoints, int maxBytes, ErrorCode code) {
        require(value != null && value.length() <= maxCodePoints * 2, code);
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            char character = value.charAt(offset);
            require(!Character.isLowSurrogate(character), code);
            if (Character.isHighSurrogate(character)) {
                require(offset + 1 < value.length() && Character.isLowSurrogate(value.charAt(offset + 1)), code);
            }
            int codePoint = value.codePointAt(offset);
            int category = Character.getType(codePoint);
            require(category != Character.CONTROL && category != Character.FORMAT
                    && category != Character.LINE_SEPARATOR && category != Character.PARAGRAPH_SEPARATOR
                    && category != Character.PRIVATE_USE && category != Character.UNASSIGNED, code);
            count++;
            require(count <= maxCodePoints, code);
            offset += Character.charCount(codePoint);
        }
        require(value.getBytes(StandardCharsets.UTF_8).length <= maxBytes, code);
    }

    static void displayName(String value, boolean token) {
        text(value, 48, 192, ErrorCode.INCOMPATIBLE_SCHEMA);
        require(!stripWhitespace(value).isEmpty() && value.equals(stripWhitespace(value)),
                ErrorCode.INCOMPATIBLE_SCHEMA);
        if (token) {
            require(value.codePoints().noneMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)),
                    ErrorCode.INCOMPATIBLE_SCHEMA);
            require(!value.chars().allMatch(character -> character >= '0' && character <= '9'),
                    ErrorCode.INCOMPATIBLE_SCHEMA);
        }
    }

    static String stripWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && whitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
