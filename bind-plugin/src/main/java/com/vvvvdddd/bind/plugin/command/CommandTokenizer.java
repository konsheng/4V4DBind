package com.vvvvdddd.bind.plugin.command;

import com.vvvvdddd.bind.core.BindingException;
import com.vvvvdddd.bind.core.ErrorCode;
import java.util.ArrayList;
import java.util.List;

public final class CommandTokenizer {
    private CommandTokenizer() { }

    public static List<String> parse(String input) {
        if (input == null || input.codePointCount(0, input.length()) > 2048) {
            throw new BindingException(ErrorCode.INVALID_INPUT);
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i))) fail();
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) {
                fail();
            }
        }
        List<String> parts = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            while (cursor < input.length() && input.charAt(cursor) == ' ') cursor++;
            if (cursor == input.length()) break;
            StringBuilder token = new StringBuilder();
            boolean quoted = input.charAt(cursor) == '"';
            if (quoted) cursor++;
            boolean closed = !quoted;
            while (cursor < input.length()) {
                char c = input.charAt(cursor++);
                if (quoted && c == '"') { closed = true; break; }
                if (!quoted && c == ' ') break;
                if (!quoted && c == '"') fail();
                if (quoted && c == '\\') {
                    if (cursor >= input.length()) fail();
                    c = input.charAt(cursor++);
                    if (c != '"' && c != '\\') fail();
                }
                token.append(c);
            }
            if (!closed || (quoted && cursor < input.length() && input.charAt(cursor) != ' ')) fail();
            if (token.isEmpty() || parts.size() >= 16) fail();
            parts.add(token.toString());
        }
        return List.copyOf(parts);
    }

    public static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) fail();
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { throw new BindingException(ErrorCode.INVALID_INPUT); }
    }

    public static int page(String value) {
        long parsed = positiveId(value);
        if (parsed > 1_000_000) fail();
        return (int) parsed;
    }

    private static void fail() { throw new BindingException(ErrorCode.INVALID_INPUT); }
}
