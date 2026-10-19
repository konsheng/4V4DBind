package com.vvvvdddd.bind.plugin;

import com.vvvvdddd.bind.core.BindingException;
import com.vvvvdddd.bind.core.ErrorCode;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SessionDirectoryTest {
    @Test
    void joiningTheSamePlayerIsIdempotentAndPreservesTheGate() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer player = new FakePlayer(UUID.randomUUID(), "Player");
        var first = directory.join(player.player, 5);
        assertTrue(first.gate().acquire(5, 1, 1, System.nanoTime()));
        var repeated = directory.join(player.player, 5);
        assertSame(first, repeated);
        assertEquals(first.token(), repeated.token());
        assertSame(first.gate(), repeated.gate());
        assertFalse(repeated.gate().acquire(5, 1, 1, System.nanoTime()));
        first.gate().release();
    }

    @Test
    void stalePlayerCannotReplaceANewerSession() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer oldPlayer = new FakePlayer(UUID.randomUUID(), "Old");
        directory.join(oldPlayer.player, 5);
        FakePlayer newPlayer = new FakePlayer(oldPlayer.id, "New");
        var replacement = directory.join(newPlayer.player, 5);
        BindingException failure = assertThrows(BindingException.class,
                () -> directory.current(oldPlayer.player, 5));
        assertEquals(ErrorCode.UNAVAILABLE, failure.code());
        assertSame(replacement, directory.current(newPlayer.player, 5));
        assertEquals(List.of("New"), new ArrayList<>(directory.onlineNames()));
    }

    @Test
    void currentPreservesSessionUntilThePlayerReconnects() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer first = new FakePlayer(UUID.randomUUID(), "First");
        var original = directory.join(first.player, 5);
        assertSame(original, directory.current(first.player, 5));
        FakePlayer reconnected = new FakePlayer(first.id, "Renamed");
        var replacement = directory.join(reconnected.player, 5);
        assertNotEquals(original.token(), replacement.token());
        assertNotSame(original.gate(), replacement.gate());
        directory.quit(first.player);
        assertSame(replacement, directory.current(reconnected.player, 5));
        assertEquals(List.of("Renamed"), new ArrayList<>(directory.onlineNames()));
    }

    @Test
    void oldQueuedReplyCannotReachAReconnectedSession() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer first = new FakePlayer(UUID.randomUUID(), "Player");
        var original = directory.join(first.player, 5);
        List<CommandSender> delivered = new ArrayList<>();
        directory.dispatch(original, "bind.view", delivered::add);
        assertEquals(1, first.callbacks.size());
        first.online.set(false);
        directory.quit(first.player);
        FakePlayer reconnected = new FakePlayer(first.id, "Player");
        var replacement = directory.join(reconnected.player, 5);
        first.runCallbacks();
        directory.dispatch(original, "bind.view", delivered::add);
        assertTrue(delivered.isEmpty());
        assertTrue(reconnected.callbacks.isEmpty());
        directory.dispatch(replacement, "bind.view", delivered::add);
        reconnected.runCallbacks();
        assertEquals(List.of(reconnected.player), delivered);
    }

    @Test
    void permissionIsCheckedWhenQueuedReplyActuallyRuns() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer player = new FakePlayer(UUID.randomUUID(), "Player");
        var client = directory.join(player.player, 5);
        List<CommandSender> delivered = new ArrayList<>();
        directory.dispatch(client, "bind.view", delivered::add);
        player.permission.set(false);
        player.runCallbacks();
        assertTrue(delivered.isEmpty());
        directory.dispatch(client, "", delivered::add);
        player.runCallbacks();
        assertEquals(List.of(player.player), delivered);
    }

    @Test
    void quittingClearingAndOfflineStateDropPendingReplies() {
        for (int mode = 0; mode < 3; mode++) {
            SessionDirectory directory = new SessionDirectory(plugin());
            FakePlayer player = new FakePlayer(UUID.randomUUID(), "Player");
            var client = directory.join(player.player, 5);
            List<CommandSender> delivered = new ArrayList<>();
            directory.dispatch(client, "bind.view", delivered::add);
            if (mode == 0) directory.quit(player.player);
            else if (mode == 1) directory.clear();
            else player.online.set(false);
            player.runCallbacks();
            assertTrue(delivered.isEmpty());
        }
    }

    @Test
    void retiredSchedulerDoesNotPropagateAnException() {
        SessionDirectory directory = new SessionDirectory(plugin());
        FakePlayer player = new FakePlayer(UUID.randomUUID(), "Player");
        var client = directory.join(player.player, 5);
        player.retired.set(true);
        assertDoesNotThrow(() -> directory.dispatch(client, "bind.view", sender -> fail("Unexpected reply")));
        assertTrue(player.callbacks.isEmpty());
    }

    private static Plugin plugin() {
        return proxy(Plugin.class, (target, method, args) -> switch (method.getName()) {
            case "isEnabled" -> true;
            case "getName", "toString" -> "TestPlugin";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class FakePlayer {
        private final UUID id;
        private final AtomicBoolean online = new AtomicBoolean(true);
        private final AtomicBoolean permission = new AtomicBoolean(true);
        private final AtomicBoolean retired = new AtomicBoolean();
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        private final Player player;

        private FakePlayer(UUID id, String name) {
            this.id = id;
            EntityScheduler scheduler = proxy(EntityScheduler.class, (target, method, args) -> {
                if (method.getName().equals("run")) {
                    if (retired.get()) throw new IllegalStateException("Entity retired");
                    @SuppressWarnings("unchecked") Consumer<ScheduledTask> callback = (Consumer<ScheduledTask>) args[1];
                    callbacks.add(() -> callback.accept(null));
                    return null;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(target);
                if (method.getName().equals("equals")) return target == args[0];
                if (method.getName().equals("toString")) return "TestScheduler";
                throw new UnsupportedOperationException(method.getName());
            });
            player = proxy(Player.class, (target, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "toString" -> name;
                case "getScheduler" -> scheduler;
                case "isOnline" -> online.get();
                case "hasPermission" -> permission.get();
                case "hashCode" -> System.identityHashCode(target);
                case "equals" -> target == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private void runCallbacks() {
            while (!callbacks.isEmpty()) callbacks.removeFirst().run();
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
