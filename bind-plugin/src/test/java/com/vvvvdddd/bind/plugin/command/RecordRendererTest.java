package com.vvvvdddd.bind.plugin.command;

import com.vvvvdddd.bind.core.Mask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordRendererTest {
    @Test
    void hiddenMaskNeverExposesCharacters() {
        for (String value : new String[]{"", "a", "123456789012345678", "😀秘密😀"}) {
            assertEquals("****", RecordRenderer.mask(value, Mask.HIDDEN));
        }
    }

    @Test
    void shortPartialValuesAreCompletelyHidden() {
        for (String value : new String[]{"", "a", "abcd", "😀😀😀😀"}) {
            assertEquals("****", RecordRenderer.mask(value, Mask.PARTIAL));
        }
    }

    @Test
    void partialMaskPreservesWholeUnicodeCodePoints() {
        assertEquals("12***89", RecordRenderer.mask("123456789", Mask.PARTIAL));
        assertEquals("😀甲***乙😁", RecordRenderer.mask("😀甲秘密乙😁", Mask.PARTIAL));
    }

    @Test
    void plainMaskTruncatesLongValuesAtCodePointBoundary() {
        String maximum = "😀".repeat(48);
        assertEquals(maximum, RecordRenderer.mask(maximum, Mask.PLAIN));
        String output = RecordRenderer.mask(maximum + "尾", Mask.PLAIN);
        assertEquals(maximum + "…", output);
        assertEquals(49, output.codePointCount(0, output.length()));
        assertEquals("", RecordRenderer.mask("", Mask.PLAIN));
    }
}
