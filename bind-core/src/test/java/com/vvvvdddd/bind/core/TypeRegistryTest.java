package com.vvvvdddd.bind.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TypeRegistryTest {
    @Test
    void registryHasStableOrderingAndImmutableDefinitionSnapshots() {
        List<FieldDefinition> fields = new ArrayList<>(List.of(field("value", true)));
        List<String> dedupe = new ArrayList<>(List.of("value"));
        BindingType type = new BindingType("custom", "自定义", true, -1, fields, dedupe);
        List<BindingType> input = new ArrayList<>(List.of(type));
        TypeRegistry registry = new TypeRegistry(input);
        fields.clear();
        dedupe.clear();
        input.clear();
        assertEquals(1, type.fields().size());
        assertEquals(List.of("value"), type.dedupeFields());
        assertEquals(List.of(type), registry.all());
        assertSame(type, registry.findId("custom").orElseThrow());
        assertSame(type, registry.findName("自定义").orElseThrow());
        assertTrue(registry.findName(null).isEmpty());
        assertTrue(registry.findId("absent").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> type.fields().clear());
    }

    @Test
    void rejectsDuplicateIdsAndCaseFoldedCommandNames() {
        BindingType first = type("first", "Contact");
        assertDefinition(() -> new TypeRegistry(List.of(first, type("first", "另一项"))));
        assertDefinition(() -> new TypeRegistry(List.of(first, type("second", "contact"))));
    }

    @Test
    void rejectsInvalidRegistryAndDefinitionBounds() {
        assertDefinition(() -> new TypeRegistry(List.of()));
        List<BindingType> excessive = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            excessive.add(type("type" + index, "类型" + index));
        }
        assertDefinition(() -> new TypeRegistry(excessive));
        assertDefinition(() -> type("UPPERCASE", "测试"));
        assertDefinition(() -> type("../path", "测试"));
        assertDefinition(() -> type("valid", "含 空格"));
        assertDefinition(() -> type("valid", "含\u202e控制"));
        assertDefinition(() -> type("valid", "123"));
        assertDefinition(() -> new FieldDefinition("field", "字段", "text", true, 1025, Mask.PLAIN));
        assertDefinition(() -> new BindingType("test", "测试", true, 0, List.of(field("value", true)), List.of("value")));
    }

    @Test
    void rejectsAmbiguousOrIncompleteFieldDefinitions() {
        assertDefinition(() -> new BindingType("test", "测试", true, 10,
                List.of(field("value", true), field("value", true)), List.of("value")));
        assertDefinition(() -> new BindingType("test", "测试", true, 10,
                List.of(field("optional", false), field("required", true)), List.of("required")));
        assertDefinition(() -> new BindingType("test", "测试", true, 10,
                List.of(field("value", true)), List.of("absent")));
        assertDefinition(() -> new BindingType("test", "测试", true, 10,
                List.of(field("value", true)), List.of()));
        List<FieldDefinition> excessive = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            excessive.add(field("field" + index, true));
        }
        assertDefinition(() -> new BindingType("test", "测试", true, 10, excessive, List.of("field0")));
    }

    @Test
    void preservesServerAuthenticatedNamesAndRejectsUnsafeNames() {
        UUID owner = UUID.randomUUID();
        assertEquals(".BedrockPlayer", new PlayerIdentity(owner, ".BedrockPlayer").name());
        assertEquals("代理玩家", new PlayerIdentity(owner, "代理玩家").name());
        assertThrows(BindingException.class, () -> new PlayerIdentity(owner, "space name"));
        assertThrows(BindingException.class, () -> new PlayerIdentity(owner, "line\nname"));
        assertThrows(BindingException.class, () -> new PlayerIdentity(owner, "name\u202e"));
        assertThrows(BindingException.class, () -> new PlayerIdentity(owner, "x".repeat(65)));
        assertThrows(BindingException.class, () -> new PlayerIdentity(owner, "\ud800"));
    }

    @Test
    void actorsCannotConfusePlayerAndConsoleIdentity() {
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, Actor.player(uuid).uuid());
        assertEquals("CONSOLE", Actor.console().kind());
        assertThrows(BindingException.class, () -> new Actor("PLAYER", null));
        assertThrows(BindingException.class, () -> new Actor("CONSOLE", uuid));
        assertThrows(BindingException.class, () -> new Actor("unknown", uuid));
    }

    @Test
    void personalValuesAreImmutableAndExcludedFromRecordToString() {
        String secret = "sensitive-address@example.com";
        Map<String, String> input = new LinkedHashMap<>(Map.of("value", secret));
        BindingType type = type("email", "邮箱");
        BindingRecord record = new BindingRecord(1, UUID.randomUUID(), "email", input, Instant.now(), Actor.console());
        ValidatedBinding validated = new ValidatedBinding(type, input, "[\"" + secret + "\"]");
        input.clear();
        assertEquals(secret, record.values().get("value"));
        assertEquals(secret, validated.values().get("value"));
        assertFalse(record.toString().contains(secret));
        assertFalse(validated.toString().contains(secret));
        assertThrows(UnsupportedOperationException.class, () -> record.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> validated.values().clear());
    }

    private static BindingType type(String id, String name) {
        return new BindingType(id, name, true, 10, List.of(field("value", true)), List.of("value"));
    }

    private static FieldDefinition field(String id, boolean required) {
        return new FieldDefinition(id, "字段", "text", required, 128, Mask.PARTIAL);
    }

    private static void assertDefinition(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(ErrorCode.INVALID_DEFINITION, assertThrows(BindingException.class, executable).code());
    }
}
