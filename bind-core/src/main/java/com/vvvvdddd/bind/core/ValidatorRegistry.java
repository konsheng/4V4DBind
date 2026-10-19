package com.vvvvdddd.bind.core;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class ValidatorRegistry {
    private static final String EMAIL_LOCAL = "!#$%&'*+-/=?^_`{|}~.";
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECKS = "10X98765432";
    private final Map<String, UnaryOperator<String>> validators = new ConcurrentHashMap<>();
    private final Clock clock;

    public ValidatorRegistry() {
        this(Clock.systemUTC());
    }

    public ValidatorRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        register("text", value -> value);
        register("digits", ValidatorRegistry::digits);
        register("qq", ValidatorRegistry::qq);
        register("phone", ValidatorRegistry::phone);
        register("email", ValidatorRegistry::email);
        register("name", ValidatorRegistry::name);
        register("cn_id", this::identity);
    }

    public void register(String id, UnaryOperator<String> validator) {
        CoreChecks.identifier(id);
        CoreChecks.require(validator != null && validators.putIfAbsent(id, validator) == null,
                ErrorCode.INVALID_DEFINITION);
    }

    public boolean supports(String id) {
        return id != null && validators.containsKey(id);
    }

    public void validateTypes(TypeRegistry registry) {
        CoreChecks.require(registry != null, ErrorCode.INVALID_DEFINITION);
        for (BindingType type : registry.all()) {
            for (FieldDefinition field : type.fields()) {
                CoreChecks.require(supports(field.validator()), ErrorCode.INVALID_DEFINITION);
            }
        }
    }

    public ValidatedBinding normalizeAndValidate(BindingType type, List<String> arguments) {
        CoreChecks.require(type != null, ErrorCode.UNKNOWN_TYPE);
        CoreChecks.require(type.enabled(), ErrorCode.DISABLED_TYPE);
        CoreChecks.require(arguments != null && arguments.size() <= type.fields().size(), ErrorCode.INVALID_INPUT);
        Map<String, String> values = new LinkedHashMap<>();
        int rawCodePoints = 0;
        int rawBytes = 0;
        int normalizedCodePoints = 0;
        int normalizedBytes = 0;
        for (int index = 0; index < type.fields().size(); index++) {
            FieldDefinition field = type.fields().get(index);
            UnaryOperator<String> validator = validators.get(field.validator());
            CoreChecks.require(validator != null, ErrorCode.INVALID_DEFINITION);
            if (index >= arguments.size()) {
                CoreChecks.require(!field.required(), ErrorCode.INVALID_INPUT);
                continue;
            }
            String raw = arguments.get(index);
            CoreChecks.text(raw, InputLimits.MAX_FIELD_CODE_POINTS, InputLimits.MAX_FIELD_BYTES,
                    ErrorCode.INVALID_INPUT);
            rawCodePoints += raw.codePointCount(0, raw.length());
            rawBytes += raw.getBytes(StandardCharsets.UTF_8).length;
            checkTotals(rawCodePoints, rawBytes);
            String value = CoreChecks.stripWhitespace(raw);
            if (value.isEmpty()) {
                CoreChecks.require(!field.required(), ErrorCode.INVALID_INPUT);
                continue;
            }
            CoreChecks.text(value, field.maxLength(), InputLimits.MAX_FIELD_BYTES, ErrorCode.INVALID_INPUT);
            try {
                value = validator.apply(value);
            } catch (RuntimeException exception) {
                // 不传播扩展校验器可能包含原始资料的异常
                throw new BindingException(ErrorCode.INVALID_INPUT);
            }
            CoreChecks.text(value, field.maxLength(), InputLimits.MAX_FIELD_BYTES, ErrorCode.INVALID_INPUT);
            CoreChecks.require(!CoreChecks.stripWhitespace(value).isEmpty(), ErrorCode.INVALID_INPUT);
            normalizedCodePoints += value.codePointCount(0, value.length());
            normalizedBytes += value.getBytes(StandardCharsets.UTF_8).length;
            checkTotals(normalizedCodePoints, normalizedBytes);
            values.put(field.id(), value);
        }
        CoreChecks.require(!values.isEmpty(), ErrorCode.INVALID_INPUT);
        return new ValidatedBinding(type, values, dedupeKey(type, values));
    }

    private static void checkTotals(int codePoints, int bytes) {
        CoreChecks.require(codePoints <= InputLimits.MAX_TOTAL_CODE_POINTS && bytes <= InputLimits.MAX_TOTAL_BYTES,
                ErrorCode.INVALID_INPUT);
    }

    private static String digits(String value) {
        CoreChecks.require(!value.isEmpty() && value.chars().allMatch(character -> character >= '0' && character <= '9'),
                ErrorCode.INVALID_INPUT);
        return value;
    }

    private static String qq(String value) {
        digits(value);
        CoreChecks.require(value.length() >= 5 && value.length() <= 12 && value.charAt(0) != '0',
                ErrorCode.INVALID_INPUT);
        return value;
    }

    private static String phone(String value) {
        digits(value);
        CoreChecks.require(value.length() == 11 && value.charAt(0) == '1'
                && value.charAt(1) >= '3' && value.charAt(1) <= '9', ErrorCode.INVALID_INPUT);
        return value;
    }

    private static String email(String value) {
        CoreChecks.require(value.length() <= 254, ErrorCode.INVALID_INPUT);
        int separator = value.indexOf('@');
        CoreChecks.require(separator > 0 && separator == value.lastIndexOf('@') && separator <= 64,
                ErrorCode.INVALID_INPUT);
        String local = value.substring(0, separator);
        CoreChecks.require(!local.startsWith(".") && !local.endsWith(".") && !local.contains(".."),
                ErrorCode.INVALID_INPUT);
        CoreChecks.require(local.chars().allMatch(character -> character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9'
                || EMAIL_LOCAL.indexOf(character) >= 0), ErrorCode.INVALID_INPUT);
        String domain = IDN.toASCII(value.substring(separator + 1), IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
        String[] labels = domain.split("\\.", -1);
        CoreChecks.require(domain.length() <= 253 && labels.length >= 2, ErrorCode.INVALID_INPUT);
        for (String label : labels) {
            CoreChecks.require(!label.isEmpty() && label.length() <= 63 && !label.startsWith("-")
                    && !label.endsWith("-") && label.chars().allMatch(character -> character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9' || character == '-'), ErrorCode.INVALID_INPUT);
        }
        String normalized = local + "@" + domain;
        CoreChecks.require(normalized.length() <= 254, ErrorCode.INVALID_INPUT);
        return normalized;
    }

    private static String name(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        CoreChecks.require(normalized.codePointCount(0, normalized.length()) <= 64, ErrorCode.INVALID_INPUT);
        boolean letterSeen = false;
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            int category = Character.getType(codePoint);
            boolean letter = Character.isLetter(codePoint);
            boolean mark = category == Character.NON_SPACING_MARK || category == Character.COMBINING_SPACING_MARK;
            boolean separator = codePoint == ' ' || codePoint == '\'' || codePoint == '-'
                    || codePoint == '\u00b7' || codePoint == '\u30fb';
            CoreChecks.require(letter || mark && letterSeen || separator && index > 0,
                    ErrorCode.INVALID_INPUT);
            letterSeen |= letter;
            if (separator) {
                CoreChecks.require(index + Character.charCount(codePoint) < normalized.length(), ErrorCode.INVALID_INPUT);
            }
            index += Character.charCount(codePoint);
        }
        CoreChecks.require(letterSeen, ErrorCode.INVALID_INPUT);
        return normalized;
    }

    private String identity(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        CoreChecks.require(normalized.length() == 18 && normalized.charAt(0) >= '1' && normalized.charAt(0) <= '9',
                ErrorCode.INVALID_INPUT);
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            char digit = normalized.charAt(index);
            CoreChecks.require(digit >= '0' && digit <= '9', ErrorCode.INVALID_INPUT);
            sum += (digit - '0') * ID_WEIGHTS[index];
        }
        CoreChecks.require(normalized.charAt(17) == ID_CHECKS.charAt(sum % 11)
                && !normalized.substring(14, 17).equals("000"), ErrorCode.INVALID_INPUT);
        int year = Integer.parseInt(normalized.substring(6, 10));
        int month = Integer.parseInt(normalized.substring(10, 12));
        int day = Integer.parseInt(normalized.substring(12, 14));
        LocalDate birthday = LocalDate.of(year, month, day);
        CoreChecks.require(year >= 1800 && !birthday.isAfter(LocalDate.now(clock)), ErrorCode.INVALID_INPUT);
        return normalized;
    }

    private static String dedupeKey(BindingType type, Map<String, String> values) {
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < type.dedupeFields().size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            String value = values.get(type.dedupeFields().get(index));
            if (value == null) {
                output.append("null");
            } else {
                output.append('"');
                for (int offset = 0; offset < value.length(); offset++) {
                    char character = value.charAt(offset);
                    if (character == '"' || character == '\\') {
                        output.append('\\');
                    }
                    output.append(character);
                }
                output.append('"');
            }
        }
        return output.append(']').toString();
    }
}
