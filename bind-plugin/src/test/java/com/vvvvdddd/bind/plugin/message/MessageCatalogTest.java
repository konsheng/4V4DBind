package com.vvvvdddd.bind.plugin.message;

import com.vvvvdddd.bind.plugin.config.ConfigException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageCatalogTest {
    @Test
    void userValuesRemainLiteralAndCannotCreateEvents() {
        MessageCatalog catalog = new MessageCatalog(Map.of("test", "<gray><value></gray>"));
        String malicious = "<click:run_command:'/op attacker'><red>攻击</red></click>";
        Component output = catalog.render("test", Map.of("value", malicious));
        assertEquals(malicious, plain(output));
        assertNoEvents(output);
    }

    @Test
    void unknownKeyReturnsFixedSafeFallback() {
        MessageCatalog catalog = new MessageCatalog(Map.of("test", "正常"));
        assertEquals("消息暂时不可用", plain(catalog.render("secret-value", Map.of())));
        assertFalse(plain(catalog.render("secret-value", Map.of())).contains("secret-value"));
    }

    @Test
    void rejectsUnbalancedAndUnrecognizedTagsWithoutLeakingTemplate() {
        for (String invalid : new String[]{"<red>private-data", "<unknown>private-data</unknown>",
                "<click:invalid:'/op secret'>private-data</click>", "<green>private-data</red>",
                "<red", "<value:bad>"}) {
            ConfigException failure = assertThrows(ConfigException.class,
                    () -> new MessageCatalog(Map.of("test", invalid)));
            assertFalse(failure.getMessage().contains("private-data"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void acceptsEscapedLiteralSyntaxAndTrustedFormatting() {
        MessageCatalog catalog = new MessageCatalog(Map.of("test",
                "<gradient:blue:aqua>示例</gradient> \\<QQ号> <click:suggest_command:'/bind'>帮助</click>"));
        Component result = catalog.render("test", Map.of());
        assertEquals("示例 <QQ号> 帮助", plain(result));
    }

    @Test
    void rejectsUnboundedTemplatesAndInvalidKeys() {
        assertThrows(ConfigException.class, () -> new MessageCatalog(Map.of("test", "x".repeat(4097))));
        assertThrows(ConfigException.class, () -> new MessageCatalog(Map.of("Bad Key", "x")));
        assertThrows(ConfigException.class,
                () -> new MessageCatalog(Map.of("test", "<red>".repeat(65) + "x" + "</red>".repeat(65))));
    }

    private static void assertNoEvents(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        component.children().forEach(MessageCatalogTest::assertNoEvents);
    }

    private static String plain(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof TextComponent text) {
            result.append(text.content());
        }
        component.children().forEach(child -> result.append(plain(child)));
        return result.toString();
    }
}
