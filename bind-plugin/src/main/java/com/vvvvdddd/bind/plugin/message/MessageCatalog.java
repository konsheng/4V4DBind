package com.vvvvdddd.bind.plugin.message;

import com.vvvvdddd.bind.plugin.config.ConfigException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MessageCatalog {
    private static final int MAX_TEMPLATE_LENGTH = 4096;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder().strict(true).build();
    private static final TagResolver STANDARD_TAGS = TagResolver.standard();
    private static final Set<String> PLACEHOLDERS = Set.of(
            "id", "player", "target", "type", "fields", "time", "actor", "page", "pages",
            "total", "value", "field", "limit", "usage", "count", "reason"
    );
    private static final Component FALLBACK = Component.text("消息暂时不可用", NamedTextColor.RED);
    private final Map<String, String> templates;

    public MessageCatalog(Map<String, String> templates) {
        if (templates == null || templates.isEmpty() || templates.size() > 128) {
            throw invalidTemplate();
        }
        Map<String, String> checked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*")
                    || key.length() > 64 || value == null || value.length() > MAX_TEMPLATE_LENGTH) {
                throw invalidTemplate();
            }
            validateTemplate(value);
            checked.put(key, value);
        }
        this.templates = Map.copyOf(checked);
    }

    public Set<String> keys() {
        return templates.keySet();
    }

    public Component render(String key, Map<String, String> values) {
        String template = templates.get(key);
        if (template == null || values == null) {
            return FALLBACK;
        }
        TagResolver.Builder resolver = TagResolver.builder();
        for (String placeholder : PLACEHOLDERS) {
            String value = values.getOrDefault(placeholder, "");
            if (value == null || value.length() > 16384) {
                return FALLBACK;
            }
            resolver.resolver(Placeholder.unparsed(placeholder, value));
        }
        try {
            return MINI_MESSAGE.deserialize(template, resolver.build());
        } catch (RuntimeException ignored) {
            return FALLBACK;
        }
    }

    private static void validateTemplate(String template) {
        try {
            List<String> tags = new ArrayList<>();
            StringBuilder validationInput = new StringBuilder(template.length());
            for (int index = 0; index < template.length();) {
                char character = template.charAt(index);
                if (character == '\\' && index + 1 < template.length()) {
                    // 预检时移除转义标签的尖括号, 防止误判合法的纯文本
                    validationInput.append('x');
                    index += 2;
                } else if (character == '<') {
                    int end = tagEnd(template, index + 1);
                    String token = template.substring(index, end + 1);
                    String inner = token.substring(1, token.length() - 1);
                    String name = inner.startsWith("/") ? inner.substring(1) : inner;
                    int colon = name.indexOf(':');
                    if (colon >= 0) {
                        name = name.substring(0, colon);
                    }
                    name = name.toLowerCase(Locale.ROOT);
                    boolean placeholder = PLACEHOLDERS.contains(name);
                    if (name.isEmpty() || (!placeholder && !STANDARD_TAGS.has(name))
                            || (placeholder && (inner.startsWith("/") || colon >= 0))) {
                        throw invalidTemplate();
                    }
                    tags.add(token);
                    if (tags.size() > 128) {
                        throw invalidTemplate();
                    }
                    validationInput.append(token);
                    index = end + 1;
                } else {
                    if (Character.isISOControl(character) && character != '\n' && character != '\t') {
                        throw invalidTemplate();
                    }
                    validationInput.append(character);
                    index++;
                }
            }
            TagResolver.Builder placeholders = TagResolver.builder();
            PLACEHOLDERS.forEach(name -> placeholders.resolver(Placeholder.unparsed(name, "")));
            Component result = MINI_MESSAGE.deserialize(validationInput.toString(), placeholders.build());
            String plain = plainText(result);
            // MiniMessage严格模式仍会把部分错误参数当作原文, 因此额外拒绝残留标签
            if (tags.stream().anyMatch(plain::contains)) {
                throw invalidTemplate();
            }
        } catch (RuntimeException ignored) {
            throw invalidTemplate();
        }
    }

    private static int tagEnd(String input, int start) {
        char quote = 0;
        for (int index = start; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '\\') {
                index++;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '>') {
                return index;
            } else if (character == '<' || character == '\n' || character == '\r') {
                throw invalidTemplate();
            }
        }
        throw invalidTemplate();
    }

    private static String plainText(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof TextComponent text) {
            result.append(text.content());
        }
        for (Component child : component.children()) {
            result.append(plainText(child));
        }
        return result.toString();
    }

    private static ConfigException invalidTemplate() {
        return new ConfigException("messages.yml contains an invalid message template");
    }
}
