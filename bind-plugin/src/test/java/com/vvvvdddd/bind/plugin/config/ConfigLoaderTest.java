package com.vvvvdddd.bind.plugin.config;

import com.vvvvdddd.bind.core.BuiltinTypes;
import com.vvvvdddd.bind.core.Mask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir
    Path directory;

    @BeforeEach
    void copyDefaults() throws IOException {
        for (String resource : new String[]{"config.yml", "messages.yml"}) {
            copyResource(resource);
        }
    }

    @Test
    void loadsSettingsAndBuiltinTypes() {
        PluginSettings settings = new ConfigLoader(directory).load();
        assertEquals(10, settings.pageSize());
        assertEquals(256, settings.queueCapacity());
        assertEquals(3000, settings.busyTimeoutMillis());
        assertEquals(5, settings.rateBurst());
        assertEquals(1, settings.ratePerSecond());
        assertEquals(2, settings.maxInFlight());
        assertEquals("Asia/Hong_Kong", settings.zone().getId());
        assertEquals(BuiltinTypes.defaults(), settings.types().all());
        var identity = settings.types().findName("户籍").orElseThrow();
        assertEquals(java.util.List.of("id_number"), identity.dedupeFields());
        assertEquals(Mask.HIDDEN, identity.fields().get(1).mask());
        var phone = settings.types().findName("手机号").orElseThrow();
        assertEquals("phone", phone.id());
        assertEquals("phone", phone.fields().getFirst().validator());
        assertEquals(11, phone.fields().getFirst().maxLength());
        settings.messages().keys().forEach(key -> assertNotNull(settings.messages().render(key, Map.of())));
    }

    @Test
    void appliesOnlyQuantityOverridesAndPreservesEveryOtherBuiltinProperty() throws IOException {
        writeLimits("binding-limits:\n  qq: -1\n  email: 1\n  identity: 1000000\n  phone: 25\n");
        var actual = new ConfigLoader(directory).load().types();
        Map<String, Integer> expected = Map.of("qq", -1, "email", 1, "identity", 1000000, "phone", 25);
        for (var builtin : BuiltinTypes.defaults()) {
            var configured = actual.findId(builtin.id()).orElseThrow();
            assertEquals(expected.get(builtin.id()).intValue(), configured.limit());
            assertEquals(builtin.id(), configured.id());
            assertEquals(builtin.name(), configured.name());
            assertEquals(builtin.enabled(), configured.enabled());
            assertEquals(builtin.fields(), configured.fields());
            assertEquals(builtin.dedupeFields(), configured.dedupeFields());
        }
    }

    @Test
    void omittedMapAndOmittedEntriesUseBuiltinLimits() throws IOException {
        for (String overrides : new String[]{"", "binding-limits: {}\n"}) {
            writeLimits(overrides);
            assertEquals(BuiltinTypes.defaults(), new ConfigLoader(directory).load().types().all());
        }
        writeLimits("binding-limits:\n  qq: -1\n");
        var types = new ConfigLoader(directory).load().types();
        assertEquals(-1, types.findId("qq").orElseThrow().limit());
        for (String id : new String[]{"email", "identity", "phone"}) {
            assertEquals(10, types.findId(id).orElseThrow().limit());
        }
    }

    @Test
    void rejectsUnknownIdsAndEveryInvalidLimitValue() throws IOException {
        for (String value : new String[]{"0", "-2", "1000001", "9223372036854775808", "1.5", "'10'",
                "true", "null", ".nan", "{}", "[]"}) {
            writeLimits("binding-limits:\n  qq: " + value + "\n");
            assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load(), value);
        }
        writeLimits("binding-limits:\n  custom: 10\n");
        assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
    }

    @Test
    void rejectsMalformedLimitMapsAndStructuralOverrides() throws IOException {
        for (String overrides : new String[]{"binding-limits: []\n", "binding-limits: null\n",
                "binding-limits: 10\n", "binding-limits:\n  qq: 10\n  qq: 20\n",
                "binding-limits:\n  qq:\n    name: 自定义\n", "binding-limits:\n  enabled: false\n",
                "binding-limits:\n  fields: {}\n", "binding-limits:\n  schema: {}\n"}) {
            writeLimits(overrides);
            assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load(), overrides);
        }
    }

    @Test
    void rejectsUnknownExecutableAndTypeConfigurationKeysInMainConfig() throws IOException {
        for (String extra : new String[]{"script: attack", "class: java.lang.Runtime", "sql: DELETE", "typo: 1",
                "types: {}", "name: 自定义", "enabled: false", "fields: {}", "schema: {}"}) {
            copyResource("config.yml");
            Files.writeString(directory.resolve("config.yml"), "\n" + extra + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load(), extra);
        }
    }

    @Test
    void rejectsUnsafeYamlBeforeBukkitDeserialization() throws IOException {
        String defaults = Files.readString(directory.resolve("config.yml"));
        for (String unsafe : new String[]{defaults + "\npage-size: 20\n",
                "value: {==: java.lang.Runtime}\n", "a: &items [one]\nb: *items\n",
                "value: !!java/object:java.lang.Runtime {}\n", "{[complex, key]: value}"}) {
            Files.writeString(directory.resolve("config.yml"), unsafe, StandardCharsets.UTF_8);
            ConfigException failure = assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
            assertFalse(failure.getMessage().contains("java.lang"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void rejectsOutOfRangeAndIncorrectNumericTypes() throws IOException {
        for (String[] change : new String[][]{{"page-size: 10", "page-size: 0"},
                {"queue-capacity: 256", "queue-capacity: 1000000000"},
                {"rate-per-second: 1.0", "rate-per-second: .nan"},
                {"max-in-flight: 2", "max-in-flight: 2.0"},
                {"busy-timeout-millis: 3000", "busy-timeout-millis: 10001"}}) {
            copyResource("config.yml");
            replace("config.yml", change[0], change[1]);
            assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
        }
    }

    @Test
    void rejectsInvalidTemplatesAndOversizedConfigurationFiles() throws IOException {
        Files.writeString(directory.resolve("messages.yml"), "prefix: '<red>secret-input'\n", StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
        Files.writeString(directory.resolve("messages.yml"), "x".repeat(262145), StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
        copyResource("messages.yml");
        Files.writeString(directory.resolve("config.yml"), "x".repeat(262145), StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
    }

    @Test
    void rejectsMissingRequiredFilesInsteadOfSilentlyCreatingThem() throws IOException {
        for (String resource : new String[]{"config.yml", "messages.yml"}) {
            Files.delete(directory.resolve(resource));
            assertThrows(ConfigException.class, () -> new ConfigLoader(directory).load());
            copyResource(resource);
        }
    }

    private void writeLimits(String limits) throws IOException {
        copyResource("config.yml");
        Path path = directory.resolve("config.yml");
        String original = Files.readString(path);
        int start = original.indexOf("binding-limits:");
        assertTrue(start >= 0);
        Files.writeString(path, original.substring(0, start) + limits, StandardCharsets.UTF_8);
    }

    private void replace(String file, String original, String replacement) throws IOException {
        Path path = directory.resolve(file);
        Files.writeString(path, Files.readString(path).replace(original, replacement), StandardCharsets.UTF_8);
    }

    private void copyResource(String resource) throws IOException {
        try (var input = ConfigLoaderTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(input);
            Files.copy(input, directory.resolve(resource), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
