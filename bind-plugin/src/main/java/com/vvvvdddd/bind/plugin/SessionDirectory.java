package com.vvvvdddd.bind.plugin;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SessionDirectory {
    public record Client(UUID uuid, UUID token, SessionGate gate) {
        public boolean console() { return uuid == null; }
    }

    private record Session(Client client, String name, Player player, EntityScheduler scheduler) { }
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Plugin plugin;

    public SessionDirectory(Plugin plugin) { this.plugin = plugin; }

    public Client join(Player player, int burst) {
        return sessions.compute(player.getUniqueId(), (uuid, previous) -> {
            if (previous != null && previous.player() == player) return previous;
            Client client = new Client(uuid, UUID.randomUUID(), new SessionGate(burst, System.nanoTime()));
            return new Session(client, player.getName(), player, player.getScheduler());
        }).client();
    }

    public Client current(Player player, int burst) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return join(player, burst);
        if (session.player() != player) {
            throw new com.vvvvdddd.bind.core.BindingException(com.vvvvdddd.bind.core.ErrorCode.UNAVAILABLE);
        }
        return session.client();
    }

    public void quit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.scheduler() == player.getScheduler()) sessions.remove(player.getUniqueId(), session);
    }
    public void clear() { sessions.clear(); }
    public Collection<String> onlineNames() {
        return sessions.values().stream().map(Session::name).sorted().limit(100).toList();
    }

    public void dispatch(Client client, String permission, Consumer<CommandSender> action) {
        if (!plugin.isEnabled()) return;
        try {
            if (client.console()) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    CommandSender sender = Bukkit.getConsoleSender();
                    if (plugin.isEnabled() && (permission.isEmpty() || sender.hasPermission(permission))) action.accept(sender);
                });
                return;
            }
            Session session = sessions.get(client.uuid());
            if (session == null || !session.client().token().equals(client.token())) return;
            session.scheduler().run(plugin, task -> {
                Session latest = sessions.get(client.uuid());
                if (latest == null || !latest.client().token().equals(client.token())) return;
                Player player = session.player();
                if (player == null || !player.isOnline() || (!permission.isEmpty() && !player.hasPermission(permission))) return;
                action.accept(player);
            }, null);
        } catch (IllegalStateException ignored) {
            // 停服或实体退役后丢弃回执
        }
    }
}
