package com.vvvvdddd.bind.plugin.command;

import com.vvvvdddd.bind.core.BindingException;
import com.vvvvdddd.bind.core.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTokenizerTest {
    @Test
    void parsesQuotedNamesAndSupportedEscapes() {
        assertEquals(List.of("add", "户籍", "Jean Pierre", "123"),
                CommandTokenizer.parse("  add  户籍 \"Jean Pierre\" 123  "));
        assertEquals(List.of("a\"b\\c"), CommandTokenizer.parse("\"a\\\"b\\\\c\""));
        assertEquals(List.of("plain\\value"), CommandTokenizer.parse("plain\\value"));
        assertEquals(List.of(), CommandTokenizer.parse("   "));
    }

    @Test
    void rejectsMalformedQuotingAndEmptyArguments() {
        for (String input : new String[]{"\"unclosed", "prefix\"value", "\"value\"suffix",
                "\"\"", "\"bad\\q\"", "\"trailing\\"}) {
            invalid(() -> CommandTokenizer.parse(input));
        }
    }

    @Test
    void limitsByCodePointsAndRejectsMalformedSurrogates() {
        String emoji = "😀";
        assertEquals(List.of(emoji.repeat(2048)), CommandTokenizer.parse(emoji.repeat(2048)));
        invalid(() -> CommandTokenizer.parse(emoji.repeat(2049)));
        invalid(() -> CommandTokenizer.parse("\ud800"));
        invalid(() -> CommandTokenizer.parse("\udc00"));
        invalid(() -> CommandTokenizer.parse("a\ud800z"));
        invalid(() -> CommandTokenizer.parse(null));
    }

    @Test
    void rejectsControlsAndEveryBidiControlCharacter() {
        for (char character : new char[]{'\n', '\r', '\t', '\0', '\u007f', '\u061c', '\u200e', '\u200f',
                '\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069'}) {
            invalid(() -> CommandTokenizer.parse("safe" + character + "value"));
        }
    }

    @Test
    void boundsArgumentCountWithoutDiscardingInput() {
        assertEquals(16, CommandTokenizer.parse("a ".repeat(16)).size());
        invalid(() -> CommandTokenizer.parse("a ".repeat(17)));
    }

    @Test
    void rejectsNumericOverflowSignsAndNonAsciiDigits() {
        assertEquals(Long.MAX_VALUE, CommandTokenizer.positiveId(Long.toString(Long.MAX_VALUE)));
        assertEquals(1_000_000, CommandTokenizer.page("1000000"));
        for (String value : new String[]{"0", "-1", "+1", "01", " 1", "1 ", "١", "",
                "9223372036854775808", "99999999999999999999"}) {
            invalid(() -> CommandTokenizer.positiveId(value));
        }
        invalid(() -> CommandTokenizer.positiveId(null));
        invalid(() -> CommandTokenizer.page("1000001"));
        invalid(() -> CommandTokenizer.page(Long.toString(Long.MAX_VALUE)));
    }

    private static void invalid(Runnable operation) {
        BindingException failure = assertThrows(BindingException.class, operation::run);
        assertEquals(ErrorCode.INVALID_INPUT, failure.code());
    }
}
