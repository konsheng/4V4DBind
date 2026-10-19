package com.vvvvdddd.bind.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorRegistryTest {
    private final ValidatorRegistry registry = new ValidatorRegistry(
            Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void normalizesEmailDomainWithoutChangingLocalPart() {
        ValidatedBinding result = registry.normalizeAndValidate(single("email", 254), List.of("  User+tag@EXAMPLE.COM  "));
        assertEquals("User+tag@example.com", result.values().get("value"));
        assertEquals("[\"User+tag@example.com\"]", result.dedupeKey());
    }

    @Test
    void handlesInternationalizedDomainsWithoutNetworkLookups() {
        assertEquals("User@xn--fsqu00a.xn--0zwm56d",
                registry.normalizeAndValidate(single("email", 254), List.of("User@例子.测试")).values().get("value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@@example.com", ".a@example.com", "a..b@example.com", "a.@example.com",
            "a@-example.com", "a@example-.com", "a@example..com", "a@example.com.", "a@example", "a b@example.com",
            "a@", "@example.com", "用户@example.com"})
    void rejectsMalformedEmail(String input) {
        assertInvalid(() -> registry.normalizeAndValidate(single("email", 254), List.of(input)));
    }

    @Test
    void treatsChineseIdentityAsOneRecordAndDeduplicatesById() {
        BindingType type = identityType();
        ValidatedBinding first = registry.normalizeAndValidate(type, List.of("张三", "11010519491231002x"));
        ValidatedBinding second = registry.normalizeAndValidate(type, List.of("李四", "11010519491231002X"));
        assertEquals("11010519491231002X", first.values().get("id_number"));
        assertEquals(first.dedupeKey(), second.dedupeKey());
        assertNotEquals(first.values(), second.values());
    }

    @Test
    void checksRealDatesAndFutureDatesEvenWhenChecksumsAreValid() {
        assertInvalid(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010520260230002"))));
        assertInvalid(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010520261020002"))));
        assertInvalid(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010517991231002"))));
        assertDoesNotThrow(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010520261019002"))));
        assertDoesNotThrow(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010520000229002"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"110105194912310020", "11010519491231002", "11010519491231002XX", "１１０１０５１９４９１２３１００２X",
            "00000019491231002X", "11010520001301002X"})
    void rejectsInvalidIdentityStructures(String input) {
        assertInvalid(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(input)));
    }

    @Test
    void rejectsIdentityWithZeroSequenceEvenWhenChecksumIsCorrect() {
        assertInvalid(() -> registry.normalizeAndValidate(single("cn_id", 18), List.of(withChecksum("11010519491231000"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "1234567890123", "01234", "１２３４５", "1234a", "+12345", "12 345"})
    void rejectsInvalidQq(String input) {
        assertInvalid(() -> registry.normalizeAndValidate(single("qq", 12), List.of(input)));
    }

    @Test
    void preservesDigitsAsText() {
        assertEquals("00123", registry.normalizeAndValidate(single("digits", 12), List.of("00123")).values().get("value"));
        assertEquals("123456789012", registry.normalizeAndValidate(single("qq", 12), List.of("123456789012")).values().get("value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"13000000000", "14000000000", "15000000000", "16000000000", "17000000000",
            "18000000000", "19999999999"})
    void acceptsOnlyTheSpecifiedMainlandPhoneShape(String input) {
        BindingType phone = new TypeRegistry(BuiltinTypes.defaults()).findId("phone").orElseThrow();
        ValidatedBinding result = registry.normalizeAndValidate(phone, List.of(input));
        assertEquals(input, result.values().get("value"));
        assertEquals("[\"" + input + "\"]", result.dedupeKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1380000000", "138000000000", "10000000000", "11000000000", "12000000000",
            "23800000000", "03800000000", "+8613800000000", "8613800000000", "138-0000-0000",
            "138 0000 0000", "１３８００００００００", "1380000000a"})
    void rejectsPhonePrefixesFormattingUnicodeDigitsAndWrongLengths(String input) {
        BindingType phone = new TypeRegistry(BuiltinTypes.defaults()).findId("phone").orElseThrow();
        assertInvalid(() -> registry.normalizeAndValidate(phone, List.of(input)));
    }

    @Test
    void normalizesPhoneWhitespaceIntoTheSameDedupeKey() {
        BindingType phone = new TypeRegistry(BuiltinTypes.defaults()).findId("phone").orElseThrow();
        ValidatedBinding plain = registry.normalizeAndValidate(phone, List.of("13800000000"));
        assertEquals(plain, registry.normalizeAndValidate(phone, List.of(" 13800000000 ")));
        assertEquals(plain, registry.normalizeAndValidate(phone, List.of("\u00a013800000000\u202f")));
        assertInvalid(() -> registry.normalizeAndValidate(phone, List.of("138\u200b00000000")));
    }

    @Test
    void supportsUncommonAndForeignNamesWithinBoundaries() {
        for (String name : List.of("𠮷野", "阿卜杜拉·买买提", "O'Connor", "Jean-Luc", "张 三", "José")) {
            assertEquals(name, registry.normalizeAndValidate(single("name", 64), List.of(name)).values().get("value"));
        }
        assertEquals("é", registry.normalizeAndValidate(single("name", 64), List.of("e\u0301")).values().get("value"));
        assertInvalid(() -> registry.normalizeAndValidate(single("name", 128), List.of("张".repeat(65))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'张三", "张三-", "张123", "😀", "\u0301张", "张<red>三", "张_三"})
    void rejectsMalformedNames(String input) {
        assertInvalid(() -> registry.normalizeAndValidate(single("name", 64), List.of(input)));
    }

    @Test
    void checksUnicodeCodePointsInsteadOfUtf16Characters() {
        String supplementary = "😀".repeat(1024);
        assertEquals(supplementary, registry.normalizeAndValidate(single("text", 1024), List.of(supplementary)).values().get("value"));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of(supplementary + "😀")));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 2), List.of("😀😀😀")));
    }

    @Test
    void checksAggregateBoundsAcrossFieldsAndAfterCustomNormalization() {
        BindingType type = multiple(3, 1024);
        assertDoesNotThrow(() -> registry.normalizeAndValidate(type, List.of("😀".repeat(1024), "😀".repeat(1023), "😀")));
        assertInvalid(() -> registry.normalizeAndValidate(type, List.of("😀".repeat(1024), "😀".repeat(1024), "😀")));
        registry.register("expand", value -> "a".repeat(1024));
        BindingType expanding = new BindingType("expanding", "扩展", true, 10,
                List.of(field("one", "expand", true, 1024), field("two", "expand", true, 1024), field("three", "expand", true, 1024)),
                List.of("one", "two", "three"));
        assertInvalid(() -> registry.normalizeAndValidate(expanding, List.of("x", "y", "z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\tb", "\u0000", "a\u202eb", "a\u2066b", "\ufeffabc", "\ud800", "\udc00", "a\u2028b"})
    void rejectsControlsBidiAndMalformedSurrogates(String input) {
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of(input)));
    }

    @Test
    void rejectsUnexpectedMissingAndNullArguments() {
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of()));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of("one", "two")));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), Arrays.asList((String) null)));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of("   ")));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), List.of("\u00a0\u2007\u202f")));
        assertInvalid(() -> registry.normalizeAndValidate(single("text", 1024), null));
    }

    @Test
    void trimsUnicodeSpaceSeparatorsWithoutIgnoringControls() {
        assertEquals("12345", registry.normalizeAndValidate(single("qq", 12),
                List.of("\u00a0\u200712345\u202f")).values().get("value"));
        assertInvalid(() -> registry.normalizeAndValidate(single("qq", 12), List.of("\t12345")));
    }

    @Test
    void dedupeJsonDoesNotCollideAcrossBoundariesAndEscapesStrings() {
        BindingType type = multiple(2, 128);
        ValidatedBinding first = registry.normalizeAndValidate(type, List.of("a|b", "c"));
        ValidatedBinding second = registry.normalizeAndValidate(type, List.of("a", "b|c"));
        assertNotEquals(first.dedupeKey(), second.dedupeKey());
        assertEquals("[\"a\\\"\\\\b\",\"c\"]", registry.normalizeAndValidate(type, List.of("a\"\\b", "c")).dedupeKey());
    }

    @Test
    void preservesOptionalFieldPositionsAndDistinguishesAbsence() {
        BindingType type = new BindingType("optional", "选填", true, 10,
                List.of(field("first", "text", true, 128), field("second", "text", false, 128)), List.of("first", "second"));
        ValidatedBinding result = registry.normalizeAndValidate(type, List.of("required"));
        assertEquals("[\"required\",null]", result.dedupeKey());
        assertEquals(1, result.values().size());
        assertEquals(result, registry.normalizeAndValidate(type, List.of("required", "  ")));
    }

    @Test
    void acceptsLiteralSqlAndMiniMessageOnlyAsText() {
        String literal = "<click:run_command:'/op test'>'; DROP TABLE bindings; --";
        assertEquals(literal, registry.normalizeAndValidate(single("text", 1024), List.of(literal)).values().get("value"));
    }

    @Test
    void sanitizesCustomValidatorFailuresAndRejectsReplacementOfBuiltins() {
        String secret = "sensitive-personal-data";
        registry.register("broken", value -> { throw new IllegalArgumentException(value); });
        BindingException exception = assertThrows(BindingException.class,
                () -> registry.normalizeAndValidate(single("broken", 128), List.of(secret)));
        assertEquals(ErrorCode.INVALID_INPUT, exception.code());
        assertFalse(exception.toString().contains(secret));
        assertNull(exception.getCause());
        assertEquals(ErrorCode.INVALID_DEFINITION,
                assertThrows(BindingException.class, () -> registry.register("qq", value -> value)).code());
    }

    @Test
    void rejectsDisabledOrUnknownValidatorsBeforeStorage() {
        BindingType disabled = new BindingType("disabled", "禁用", false, 10,
                List.of(field("value", "text", true, 128)), List.of("value"));
        assertEquals(ErrorCode.DISABLED_TYPE,
                assertThrows(BindingException.class, () -> registry.normalizeAndValidate(disabled, List.of("test"))).code());
        TypeRegistry definitions = new TypeRegistry(List.of(single("unknown", 128)));
        assertEquals(ErrorCode.INVALID_DEFINITION,
                assertThrows(BindingException.class, () -> registry.validateTypes(definitions)).code());
        assertTrue(registry.supports("qq"));
        assertFalse(registry.supports("unknown"));
    }

    static BindingType single(String validator, int maxLength) {
        return new BindingType("test", "测试", true, 10,
                List.of(field("value", validator, true, maxLength)), List.of("value"));
    }

    static FieldDefinition field(String id, String validator, boolean required, int maxLength) {
        return new FieldDefinition(id, id, validator, required, maxLength, Mask.PARTIAL);
    }

    private static BindingType multiple(int count, int maxLength) {
        List<FieldDefinition> fields = new ArrayList<>();
        List<String> dedupe = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String id = "field" + index;
            fields.add(field(id, "text", true, maxLength));
            dedupe.add(id);
        }
        return new BindingType("multiple", "多字段", true, 10, fields, dedupe);
    }

    private static BindingType identityType() {
        return new BindingType("identity", "户籍", true, 10,
                List.of(field("name", "name", true, 64), field("id_number", "cn_id", true, 18)), List.of("id_number"));
    }

    private static String withChecksum(String firstSeventeen) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            sum += (firstSeventeen.charAt(index) - '0') * weights[index];
        }
        return firstSeventeen + "10X98765432".charAt(sum % 11);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BindingException.class, executable).code());
    }
}
