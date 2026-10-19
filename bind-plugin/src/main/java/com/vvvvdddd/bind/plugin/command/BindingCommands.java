package com.vvvvdddd.bind.plugin.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.vvvvdddd.bind.core.*;
import com.vvvvdddd.bind.plugin.BindPlugin;
import com.vvvvdddd.bind.plugin.BindPlugin.Request;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;

public final class BindingCommands {
    private static final List<String> ADMIN = List.of("list", "view", "add", "remove");
    private final BindPlugin plugin;
    public BindingCommands(BindPlugin plugin) { this.plugin = plugin; }

    public LiteralCommandNode<CommandSourceStack> tree() {
        return Commands.literal("bind").executes(context -> execute(context.getSource().getSender(), ""))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            var offset = builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1);
                            suggestions(context.getSource().getSender(), builder.getRemaining()).forEach(offset::suggest);
                            return offset.buildFuture();
                        })
                        .executes(context -> execute(context.getSource().getSender(), StringArgumentType.getString(context, "arguments"))))
                .build();
    }

    private int execute(CommandSender sender, String raw) {
        Request request = plugin.open(sender);
        if (request == null) return 0;
        boolean asynchronous = false;
        String permission = BindPlugin.USE;
        try {
            List<String> args = CommandTokenizer.parse(raw);
            if (args.isEmpty()) {
                if (!hasAnyPermission(sender)) { deny(request, BindPlugin.USE); return 0; }
                request.reply(helpPermission(sender), reply -> {
                    reply.message("help.header");
                    if (reply.sender().hasPermission(BindPlugin.USE)) {
                        for (BindingType type : reply.settings().types().all()) {
                            if (!type.enabled()) continue;
                            String fields = type.fields().stream().map(field -> field.required()
                                    ? "<" + field.name() + ">" : "[" + field.name() + "]").collect(Collectors.joining(" "));
                            reply.message("help.line", Map.of("usage", "/bind add " + type.name() + " " + fields));
                        }
                        reply.message("help.query");
                    }
                    if (hasAdminPermission(reply.sender())) reply.message("help.admin");
                });
                return 1;
            }
            String action = args.getFirst();
            if ("reload".equals(action)) {
                permission = "4v4dbind.reload";
                if (!sender.hasPermission(permission)) { deny(request, permission); return 0; }
                require(args.size() == 1);
                plugin.reload(request);
                asynchronous = true;
                return 1;
            }
            boolean admin = "admin".equals(action);
            int start = admin ? 3 : 1;
            if (admin) { require(args.size() >= 3); action = args.get(1); require(ADMIN.contains(action)); }
            else require(List.of("add", "list", "view", "remove").contains(action));
            permission = admin ? "4v4dbind.admin." + action : BindPlugin.USE;
            if (!sender.hasPermission(permission)) { deny(request, permission); return 0; }
            if (!admin && request.own() == null) {
                request.reply(permission, reply -> reply.message("errors.player-only"));
                return 0;
            }
            var state = request.snapshot();
            CompletableFuture<PlayerIdentity> target = admin ? state.repository().resolvePlayer(args.get(2))
                    : CompletableFuture.completedFuture(request.own());
            switch (action) {
                case "add" -> {
                    require(args.size() >= start + 2);
                    BindingType type = type(request, args.get(start));
                    List<String> fields = args.subList(start + 1, args.size());
                    CompletableFuture<BindingRecord> operation = target.thenCompose(owner -> state.service().add(owner, request.actor(), type, fields))
                            .thenApply(record -> {
                                plugin.audit("ADD", request.actor(), record.owner(), record.id());
                                return record;
                            });
                    request.submit(permission, operation, type, (reply, record) -> {
                        reply.message("saved", Map.of("type", type.name(), "id", Long.toString(record.id())));
                    });
                }
                case "list" -> {
                    require(args.size() >= start && args.size() <= start + 2);
                    String typeId = null;
                    int page = 1;
                    if (args.size() > start) {
                        String next = args.get(start);
                        if (next.matches("[0-9]+")) { require(args.size() == start + 1); page = CommandTokenizer.page(next); }
                        else {
                            typeId = type(request, next).id();
                            if (args.size() > start + 1) page = CommandTokenizer.page(args.get(start + 1));
                        }
                    }
                    final int requestedPage = page;
                    final String requestedType = typeId;
                    var operation = target.thenCompose(owner -> state.service().list(owner.uuid(), requestedType, requestedPage,
                            state.settings().pageSize()).thenApply(result -> new PageResult(owner, result)));
                    request.submit(permission, operation, null, (reply, result) -> RecordRenderer.list(reply, result.owner(), result.page()));
                }
                case "view" -> {
                    require(args.size() == start + 1);
                    long id = CommandTokenizer.positiveId(args.get(start));
                    var operation = target.thenCompose(owner -> state.service().view(owner.uuid(), id)
                            .thenApply(record -> new ViewResult(owner, record)));
                    request.submit(permission, operation, null, (reply, result) -> {
                        RecordRenderer.details(reply, result.owner(), result.record());
                        plugin.audit("VIEW", request.actor(), result.owner().uuid(), id);
                    });
                }
                case "remove" -> {
                    require(args.size() == start + 1);
                    long id = CommandTokenizer.positiveId(args.get(start));
                    var operation = target.thenCompose(owner -> state.service().remove(owner.uuid(), id)
                            .thenApply(removed -> {
                                if (removed) plugin.audit("REMOVE", request.actor(), owner.uuid(), id);
                                return new RemoveResult(owner, removed);
                            }));
                    request.submit(permission, operation, null, (reply, result) -> {
                        if (!result.removed()) reply.message("errors.not-found");
                        else {
                            reply.message("removed", Map.of("id", Long.toString(id)));
                        }
                    });
                }
                default -> throw new BindingException(ErrorCode.INVALID_INPUT);
            }
            asynchronous = true;
            return 1;
        } catch (RuntimeException failure) {
            request.error(permission, failure, null);
            return 0;
        } finally {
            if (!asynchronous) request.close();
        }
    }

    private List<String> suggestions(CommandSender sender, String raw) {
        if (raw.length() > 2048 || !hasAnyPermission(sender) || plugin.snapshot() == null) return List.of();
        String[] words = raw.split(" ", -1);
        String prefix = words[words.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (words.length == 1) {
            if (sender.hasPermission(BindPlugin.USE)) options.addAll(List.of("add", "list", "view", "remove"));
            if (hasAdminPermission(sender)) options.add("admin");
            if (sender.hasPermission("4v4dbind.reload")) options.add("reload");
        } else if (words[0].equals("admin")) {
            if (words.length == 2) ADMIN.stream().filter(action -> sender.hasPermission("4v4dbind.admin." + action)).forEach(options::add);
            else if (sender.hasPermission("4v4dbind.admin." + words[1])) {
                if (words.length == 3) options.addAll(plugin.sessions().onlineNames());
                if (words.length == 4 && List.of("add", "list").contains(words[1])) {
                    addTypes(options, words[1].equals("add"));
                }
            }
        } else if (words.length == 2 && sender.hasPermission(BindPlugin.USE) && List.of("add", "list").contains(words[0])) {
            addTypes(options, words[0].equals("add"));
        }
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(64).toList();
    }

    private void addTypes(List<String> options, boolean adding) {
        plugin.snapshot().settings().types().all().stream().filter(type -> !adding || type.enabled()).map(BindingType::name).forEach(options::add);
    }
    private BindingType type(Request request, String name) {
        return request.snapshot().settings().types().findName(name).orElseThrow(() -> new BindingException(ErrorCode.UNKNOWN_TYPE));
    }
    private static void require(boolean value) { if (!value) throw new BindingException(ErrorCode.INVALID_INPUT); }
    private static boolean hasAdminPermission(CommandSender sender) {
        return ADMIN.stream().anyMatch(action -> sender.hasPermission("4v4dbind.admin." + action));
    }
    private static boolean hasAnyPermission(CommandSender sender) {
        return sender.hasPermission(BindPlugin.USE) || sender.hasPermission("4v4dbind.reload") || hasAdminPermission(sender);
    }
    private static String helpPermission(CommandSender sender) {
        if (sender.hasPermission(BindPlugin.USE)) return BindPlugin.USE;
        for (String action : ADMIN) if (sender.hasPermission("4v4dbind.admin." + action)) return "4v4dbind.admin." + action;
        return "4v4dbind.reload";
    }
    private static void deny(Request request, String permission) {
        // 无权限提示不包含记录或目标信息
        request.reply("", reply -> reply.message("errors.permission"));
    }
    private record PageResult(PlayerIdentity owner, BindingPage page) { }
    private record ViewResult(PlayerIdentity owner, BindingRecord record) { }
    private record RemoveResult(PlayerIdentity owner, boolean removed) { }
}
