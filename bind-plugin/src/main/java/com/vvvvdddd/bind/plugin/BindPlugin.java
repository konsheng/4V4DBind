package com.vvvvdddd.bind.plugin;

import com.vvvvdddd.bind.core.*;
import com.vvvvdddd.bind.plugin.command.BindingCommands;
import com.vvvvdddd.bind.plugin.config.ConfigException;
import com.vvvvdddd.bind.plugin.config.ConfigLoader;
import com.vvvvdddd.bind.plugin.config.PluginSettings;
import com.vvvvdddd.bind.storage.SqliteBindingRepository;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class BindPlugin extends JavaPlugin implements Listener {
    public static final String USE = "4v4dbind.use";
    private enum State { STARTING, READY, RELOADING, FAILED, STOPPING }
    public record RuntimeState(PluginSettings settings, BindingService service, BindingRepository repository) { }
    public record Reply(CommandSender sender, PluginSettings settings) {
        public void message(String key, Map<String, String> values) {
            sender.sendMessage(settings.messages().render("prefix", Map.of())
                    .append(settings.messages().render(key, values)));
        }
        public void message(String key) { message(key, Map.of()); }
    }

    private final Object gate = new Object();
    private final ValidatorRegistry validators = new ValidatorRegistry();
    private final SessionDirectory.Client console = new SessionDirectory.Client(null, UUID.randomUUID(),
            new SessionGate(5, System.nanoTime()));
    private SessionDirectory sessions;
    private SqliteBindingRepository repository;
    private Metrics metrics;
    private volatile RuntimeState runtime;
    private State state = State.STARTING;
    private int active;
    private CompletableFuture<Void> drain;

    @Override public void onEnable() {
        sessions = new SessionDirectory(this);
        try {
            for (String resource : new String[]{"config.yml", "messages.yml"}) {
                if (!getDataFolder().toPath().resolve(resource).toFile().exists()) saveResource(resource, false);
            }
            PluginSettings settings = new ConfigLoader(getDataFolder().toPath(), validators).load();
            validators.validateTypes(settings.types());
            repository = new SqliteBindingRepository(getDataFolder().toPath().resolve("database.db"),
                    settings.queueCapacity(), settings.busyTimeoutMillis());
            runtime = new RuntimeState(settings, new BindingService(repository, validators), repository);
            BindingCommands commands = new BindingCommands(this);
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(commands.tree(), "玩家资料绑定"));
            Bukkit.getPluginManager().registerEvents(this, this);
            repository.initialize(settings.types()).thenCompose(ignored -> repository.diagnostics()).whenComplete((diagnostics, failure) -> {
                synchronized (gate) {
                    if (state != State.STARTING) return;
                    state = failure == null ? State.READY : State.FAILED;
                }
                if (failure != null) report("INITIALIZE", failure);
                else getLogger().info("资料服务已就绪, " + diagnostics + ", 所有绑定内容按配置明文保存");
            });
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(this, task -> joined(player), null);
            }
            try {
                metrics = new Metrics(this, 34819);
            } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("bStats 初始化失败");
            }
        } catch (RuntimeException failure) {
            synchronized (gate) { state = State.FAILED; }
            report("CONFIGURE", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        synchronized (gate) { state = State.STOPPING; }
        if (metrics != null) metrics.shutdown();
        if (sessions != null) sessions.clear();
        if (repository != null) repository.close();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { joined(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { sessions.quit(event.getPlayer()); }

    private void joined(Player player) {
        RuntimeState current = runtime;
        if (current == null || !player.isOnline()) return;
        sessions.join(player, current.settings().rateBurst());
        repository.rememberPlayer(new PlayerIdentity(player.getUniqueId(), player.getName()))
                .exceptionally(failure -> { report("DIRECTORY", failure); return null; });
    }

    public RuntimeState snapshot() { return runtime; }
    public SessionDirectory sessions() { return sessions; }

    public Request open(CommandSender sender) {
        RuntimeState current = runtime;
        if (current == null) { sender.sendMessage(Component.text("4V4DBind 资料服务不可用")); return null; }
        SessionDirectory.Client client;
        PlayerIdentity own;
        Actor actor;
        if (sender instanceof Player player) {
            try { client = sessions.current(player, current.settings().rateBurst()); }
            catch (BindingException failure) {
                new Reply(sender, current.settings()).message("errors.unavailable");
                return null;
            }
            own = new PlayerIdentity(player.getUniqueId(), player.getName());
            actor = Actor.player(player.getUniqueId());
        } else if (sender instanceof ConsoleCommandSender) {
            client = console;
            own = null;
            actor = Actor.console();
        } else {
            new Reply(sender, current.settings()).message("errors.player-only");
            return null;
        }
        synchronized (gate) {
            current = runtime;
            if (state != State.READY) {
                new Reply(sender, current.settings()).message(state == State.RELOADING ? "errors.busy" : "errors.unavailable");
                return null;
            }
            PluginSettings settings = current.settings();
            if (!client.gate().acquire(settings.rateBurst(), settings.ratePerSecond(), settings.maxInFlight(), System.nanoTime())) {
                new Reply(sender, settings).message("errors.rate-limit");
                return null;
            }
            active++;
            return new Request(client, own, actor, current);
        }
    }

    public final class Request implements AutoCloseable {
        private final SessionDirectory.Client client;
        private final PlayerIdentity own;
        private final Actor actor;
        private final RuntimeState snapshot;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Request(SessionDirectory.Client client, PlayerIdentity own, Actor actor, RuntimeState snapshot) {
            this.client = client; this.own = own; this.actor = actor; this.snapshot = snapshot;
        }
        public PlayerIdentity own() { return own; }
        public Actor actor() { return actor; }
        public RuntimeState snapshot() { return snapshot; }
        public void reply(String permission, java.util.function.Consumer<Reply> action) {
            sessions.dispatch(client, permission, sender -> action.accept(new Reply(sender, snapshot.settings())));
        }
        public <T> void submit(String permission, CompletableFuture<T> future, BindingType type,
                               BiConsumer<Reply, T> success) {
            future.whenComplete((value, failure) -> {
                close();
                if (failure != null) error(permission, failure, type);
                else reply(permission, target -> success.accept(target, value));
            });
        }
        public void error(String permission, Throwable failure, BindingType type) {
            ErrorCode code = errorCode(failure);
            String key = switch (code) {
                case INVALID_INPUT -> "errors.invalid-input";
                case UNKNOWN_TYPE, DISABLED_TYPE -> "errors.unknown-type";
                case DUPLICATE -> "errors.duplicate";
                case LIMIT_REACHED -> "errors.limit";
                case NOT_FOUND -> "errors.not-found";
                case AMBIGUOUS_PLAYER -> "errors.ambiguous-player";
                case BUSY -> "errors.busy";
                case UNAVAILABLE -> "errors.unavailable";
                case INVALID_DEFINITION -> "errors.invalid-definition";
                case STORAGE_ERROR -> "errors.storage";
            };
            if (code == ErrorCode.STORAGE_ERROR) report("REQUEST", failure);
            reply(permission, target -> target.message(key, Map.of("type", type == null ? "该类型" : type.name(),
                    "limit", type == null ? "" : Integer.toString(type.limit()))));
        }
        @Override public void close() {
            if (!finished.compareAndSet(false, true)) return;
            client.gate().release();
            synchronized (gate) {
                active--;
                if (active == 0 && drain != null) { drain.complete(null); drain = null; }
            }
        }
    }

    public void reload(Request request) {
        CompletableFuture<Void> wait;
        synchronized (gate) {
            if (state != State.READY) {
                request.reply("4v4dbind.reload", target -> target.message("errors.busy"));
                request.close();
                return;
            }
            state = State.RELOADING;
            request.close();
            wait = active == 0 ? CompletableFuture.completedFuture(null) : (drain = new CompletableFuture<>());
        }
        request.reply("4v4dbind.reload", target -> target.message("reload.started"));
        wait.thenCompose(ignored -> loadSettingsAsync()).thenCompose(proposed -> {
            validators.validateTypes(proposed.types());
            PluginSettings previous = request.snapshot().settings();
            if (proposed.queueCapacity() != previous.queueCapacity()
                    || proposed.busyTimeoutMillis() != previous.busyTimeoutMillis()) {
                throw new ConfigException("数据库执行器设置变更需要重启");
            }
            return repository.synchronizeTypes(proposed.types()).thenApply(unused -> proposed);
        }).whenComplete((proposed, failure) -> {
            synchronized (gate) {
                if (state != State.RELOADING) return;
                if (failure == null) runtime = new RuntimeState(proposed, new BindingService(repository, validators), repository);
                state = State.READY;
            }
            if (failure != null) {
                report("RELOAD", failure);
                request.reply("4v4dbind.reload", target -> target.message("reload.failed"));
            } else {
                sessions.dispatch(request.client, "4v4dbind.reload",
                        sender -> new Reply(sender, proposed).message("reload.success"));
            }
        });
    }

    private CompletableFuture<PluginSettings> loadSettingsAsync() {
        CompletableFuture<PluginSettings> result = new CompletableFuture<>();
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                try { result.complete(new ConfigLoader(getDataFolder().toPath(), validators).load()); }
                catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(new BindingException(ErrorCode.UNAVAILABLE)); }
        return result;
    }

    private void report(String operation, Throwable failure) {
        getLogger().warning("operation=" + operation + " result=" + errorCode(failure)
                + " reference=" + UUID.randomUUID());
    }

    private static ErrorCode errorCode(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof BindingException known) return known.code();
            if (cause instanceof ConfigException) return ErrorCode.INVALID_DEFINITION;
        }
        return ErrorCode.STORAGE_ERROR;
    }

    public void audit(String operation, Actor actor, UUID owner, long id) {
        getLogger().info("operation=" + operation + " actor=" + (actor.uuid() == null ? "CONSOLE" : actor.uuid())
                + " target=" + owner + " record=" + id + " result=SUCCESS");
    }
}
