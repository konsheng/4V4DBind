package com.vvvvdddd.bind.plugin.config;

import com.vvvvdddd.bind.core.BindingType;
import com.vvvvdddd.bind.core.BuiltinTypes;
import com.vvvvdddd.bind.core.TypeRegistry;
import com.vvvvdddd.bind.core.ValidatorRegistry;
import com.vvvvdddd.bind.plugin.message.MessageCatalog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    private static final int MAX_FILE_BYTES = 262144;
    private static final Set<String> CONFIG_KEYS = Set.of(
            "page-size", "queue-capacity", "busy-timeout-millis", "rate-burst",
            "rate-per-second", "max-in-flight", "zone"
    );
    private final Path dataDirectory;
    private final ValidatorRegistry validators;

    public ConfigLoader(Path dataDirectory) {
        this(dataDirectory, new ValidatorRegistry());
    }

    public ConfigLoader(Path dataDirectory, ValidatorRegistry validators) {
        if (dataDirectory == null || validators == null) {
            throw new IllegalArgumentException("dataDirectory and validators are required");
        }
        this.dataDirectory = dataDirectory;
        this.validators = validators;
    }

    public PluginSettings load() {
        YamlConfiguration config = read("config.yml");
        Map<String, Object> requiredSettings = new LinkedHashMap<>(config.getValues(false));
        requiredSettings.remove("binding-limits");
        requireKeys(requiredSettings, CONFIG_KEYS, "config.yml");
        int pageSize = integer(config.get("page-size"), 1, 50, "config.yml");
        int queueCapacity = integer(config.get("queue-capacity"), 1, 4096, "config.yml");
        int busyTimeout = integer(config.get("busy-timeout-millis"), 0, 10000, "config.yml");
        int rateBurst = integer(config.get("rate-burst"), 1, 100, "config.yml");
        double rate = decimal(config.get("rate-per-second"), 0.1, 20, "config.yml");
        int maxInFlight = integer(config.get("max-in-flight"), 1, 32, "config.yml");
        ZoneId zone;
        try {
            zone = ZoneId.of(text(config.get("zone"), 64, "config.yml"));
        } catch (DateTimeException ignored) {
            throw invalid("config.yml");
        }
        TypeRegistry types = builtinTypes(config);
        MessageCatalog messages = readMessages();
        return new PluginSettings(types, messages, pageSize, queueCapacity, busyTimeout,
                rateBurst, rate, maxInFlight, zone);
    }

    private TypeRegistry builtinTypes(YamlConfiguration config) {
        Map<?, ?> limits = config.getKeys(false).contains("binding-limits")
                ? map(config.get("binding-limits"), "config.yml") : Map.of();
        try {
            List<BindingType> defaults = BuiltinTypes.defaults();
            Set<String> typeIds = new HashSet<>();
            defaults.forEach(type -> typeIds.add(type.id()));
            requireOnlyKeys(limits, typeIds, "config.yml");
            List<BindingType> types = new ArrayList<>();
            for (BindingType builtin : defaults) {
                int limit = limits.containsKey(builtin.id())
                        ? integer(limits.get(builtin.id()), -1, 1000000, "config.yml") : builtin.limit();
                if (limit == 0) {
                    throw invalid("config.yml");
                }
                types.add(new BindingType(builtin.id(), builtin.name(), builtin.enabled(),
                        limit, builtin.fields(), builtin.dedupeFields()));
            }
            TypeRegistry registry = new TypeRegistry(types);
            validators.validateTypes(registry);
            return registry;
        } catch (RuntimeException ignored) {
            throw invalid("config.yml");
        }
    }

    private MessageCatalog readMessages() {
        YamlConfiguration yaml = read("messages.yml");
        Map<String, String> messages = new LinkedHashMap<>();
        collectMessages(yaml, "", messages, 0);
        return new MessageCatalog(messages);
    }

    private void collectMessages(ConfigurationSection section, String prefix, Map<String, String> result, int depth) {
        if (depth > 8) {
            throw invalid("messages.yml");
        }
        for (String key : section.getKeys(false)) {
            if (!key.matches("[a-z][a-z0-9-]*")) {
                throw invalid("messages.yml");
            }
            String fullKey = prefix + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                collectMessages(child, fullKey + ".", result, depth + 1);
            } else if (value instanceof String message) {
                result.put(fullKey, message);
            } else {
                throw invalid("messages.yml");
            }
            if (result.size() > 128) {
                throw invalid("messages.yml");
            }
        }
    }

    private YamlConfiguration read(String fileName) {
        Path path = dataDirectory.resolve(fileName);
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                throw invalid(fileName);
            }
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw invalid(fileName);
            }
            String contents = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            // 先以安全构造器检查结构, 避免Bukkit的序列化对象和无限别名展开
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(16);
            options.setCodePointLimit(MAX_FILE_BYTES);
            Object parsed = new Yaml(new SafeConstructor(options)).load(contents);
            if (!(parsed instanceof Map<?, ?>)) {
                throw invalid(fileName);
            }
            inspectYaml(parsed, 0, new int[]{0}, fileName);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().codePointLimit(MAX_FILE_BYTES);
            yaml.loadFromString(contents);
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException ignored) {
            throw invalid(fileName);
        }
    }

    private void inspectYaml(Object value, int depth, int[] nodes, String fileName) {
        if (value == null || depth > 16 || ++nodes[0] > 4096) {
            throw invalid(fileName);
        }
        if (value instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.equals("==") || key.indexOf('.') >= 0) {
                    throw invalid(fileName);
                }
                inspectYaml(entry.getValue(), depth + 1, nodes, fileName);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                inspectYaml(item, depth + 1, nodes, fileName);
            }
        } else if (value != null && !(value instanceof String) && !(value instanceof Number)
                && !(value instanceof Boolean)) {
            throw invalid(fileName);
        }
    }

    private static Map<?, ?> map(Object value, String fileName) {
        if (value instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        if (!(value instanceof Map<?, ?> result)) {
            throw invalid(fileName);
        }
        return result;
    }

    private static void requireKeys(Map<?, ?> value, Set<String> expected, String fileName) {
        if (!new HashSet<>(value.keySet()).equals(expected)) {
            throw invalid(fileName);
        }
    }

    private static void requireOnlyKeys(Map<?, ?> value, Set<String> allowed, String fileName) {
        if (!allowed.containsAll(value.keySet())) {
            throw invalid(fileName);
        }
    }

    private static String text(Object value, int maxCodePoints, String fileName) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.strip())
                || result.codePointCount(0, result.length()) > maxCodePoints
                || result.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.SURROGATE)) {
            throw invalid(fileName);
        }
        return result;
    }

    private static int integer(Object value, int min, int max, String fileName) {
        if (!(value instanceof Integer result) || result < min || result > max) {
            throw invalid(fileName);
        }
        return result;
    }

    private static double decimal(Object value, double min, double max, String fileName) {
        if (!(value instanceof Number number)) {
            throw invalid(fileName);
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) {
            throw invalid(fileName);
        }
        return result;
    }

    private static ConfigException invalid(String fileName) {
        return new ConfigException(fileName + " is missing or invalid");
    }
}
