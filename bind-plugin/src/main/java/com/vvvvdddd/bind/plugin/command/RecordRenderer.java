package com.vvvvdddd.bind.plugin.command;

import com.vvvvdddd.bind.core.*;
import com.vvvvdddd.bind.plugin.BindPlugin.Reply;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

public final class RecordRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private RecordRenderer() { }

    public static void list(Reply reply, PlayerIdentity owner, BindingPage page) {
        reply.message("list.header", Map.of("player", owner.name(), "total", Long.toString(page.total())));
        if (page.records().isEmpty()) reply.message("list.empty");
        for (BindingRecord record : page.records()) {
            BindingType type = reply.settings().types().findId(record.typeId()).orElseThrow();
            String fields = type.fields().stream().filter(field -> record.values().containsKey(field.id()))
                    .map(field -> field.name() + ": " + mask(record.values().get(field.id()), field.mask()))
                    .collect(Collectors.joining(" | "));
            reply.message("list.line", Map.of("id", Long.toString(record.id()), "type", type.name(),
                    "fields", fields, "time", TIME.withZone(reply.settings().zone()).format(record.createdAt())));
        }
        long pages = page.total() == 0 ? 1 : (page.total() - 1) / page.pageSize() + 1;
        reply.message("list.page", Map.of("page", Integer.toString(page.page()), "pages", Long.toString(pages)));
    }

    public static void details(Reply reply, PlayerIdentity owner, BindingRecord record) {
        BindingType type = reply.settings().types().findId(record.typeId()).orElseThrow();
        reply.message("details.header", Map.of("id", Long.toString(record.id())));
        for (FieldDefinition field : type.fields()) {
            if (record.values().containsKey(field.id())) {
                reply.message("details.line", Map.of("field", field.name(), "value", record.values().get(field.id())));
            }
        }
        reply.message("details.metadata", Map.of("type", type.name(), "player", owner.name(),
                "time", TIME.withZone(reply.settings().zone()).format(record.createdAt()),
                "actor", record.actor().uuid() == null ? "CONSOLE" : record.actor().uuid().toString()));
    }

    public static String mask(String value, Mask mask) {
        if (mask == Mask.HIDDEN) return "****";
        int count = value.codePointCount(0, value.length());
        if (mask == Mask.PARTIAL) {
            if (count <= 4) return "****";
            return value.substring(0, value.offsetByCodePoints(0, 2)) + "***"
                    + value.substring(value.offsetByCodePoints(0, count - 2));
        }
        return count <= 48 ? value : value.substring(0, value.offsetByCodePoints(0, 48)) + "…";
    }
}
