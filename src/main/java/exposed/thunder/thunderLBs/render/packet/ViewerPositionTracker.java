package exposed.thunder.thunderLBs.render.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import exposed.thunder.thunderLBs.ThunderLBs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Keeps an off-main-thread view of where every player is, so leaderboard range checks never have to
 * touch {@code Player#getLocation()} (which reads live entity fields and therefore requires the
 * owning region thread).
 *
 * <p>Positions come from client movement packets on Netty threads. Worlds cannot: movement packets
 * carry coordinates only, and the dimension key in {@code RESPAWN}/{@code JOIN_GAME} does not
 * identify a Bukkit world (under Multiverse many worlds share {@code minecraft:overworld}). So the
 * world is recorded on the main thread from Bukkit events, which are rare, and only the position is
 * updated from packets, which are frequent.
 *
 * <p>Each player's position is published as a single immutable {@link Position}. Packet updates use
 * a compare-and-set against the value they read, so a world change on the main thread always wins a
 * race; the losing packet update is simply dropped and the next movement packet — at most one tick
 * later — corrects it.
 */
final class ViewerPositionTracker implements Listener {

    private final ThunderLBs plugin;
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    private final MovementListener listener = new MovementListener();
    private PacketListenerCommon registeredListener;

    ViewerPositionTracker(ThunderLBs plugin) {
        this.plugin = plugin;
    }

    /**
     * An immutable snapshot of where a player is. Replaced wholesale on every update so readers can
     * never observe a half-written coordinate triple.
     */
    record Position(UUID worldUid, double x, double y, double z) {

        double distanceSquaredTo(Location location) {
            double deltaX = x - location.getX();
            double deltaY = y - location.getY();
            double deltaZ = z - location.getZ();
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }

    /** Must be called on the main thread. */
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registeredListener = PacketEvents.getAPI().getEventManager().registerListener(listener);
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Must be called on the main thread. */
    void stop() {
        HandlerList.unregisterAll(this);
        if (registeredListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            registeredListener = null;
        }
        tracked.clear();
    }

    /**
     * Re-reads a player's position from Bukkit and starts (or restarts) tracking them. Must be
     * called on the thread that owns the player, since it touches {@code Player#getLocation()}.
     *
     * @return the seeded position, or {@code null} if the player has no world
     */
    Position refresh(Player player) {
        return refresh(player, player.getLocation());
    }

    private Position refresh(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Position position = new Position(world.getUID(), location.getX(), location.getY(), location.getZ());
        tracked.put(player.getUniqueId(), new Tracked(player, position));
        return position;
    }

    /** Visits every tracked player with their most recent position. Safe from any thread. */
    void forEachViewer(BiConsumer<Player, Position> action) {
        for (Tracked entry : tracked.values()) {
            Position position = entry.position.get();
            if (position != null) {
                action.accept(entry.player, position);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracked.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // At this point getLocation() is still the death location, so take the respawn target
        // instead; it may well be in a different world.
        refresh(event.getPlayer(), event.getRespawnLocation());
    }

    private void updatePosition(UUID playerId, double x, double y, double z) {
        Tracked entry = tracked.get(playerId);
        if (entry == null) {
            return;
        }
        Position current = entry.position.get();
        if (current == null) {
            return;
        }
        entry.position.compareAndSet(current, new Position(current.worldUid(), x, y, z));
    }

    /**
     * Records where a server-initiated teleport will put the player, without applying it. Applying
     * it now would be wrong: the client may still reject or lag behind the teleport, so the move is
     * only committed once it acknowledges the matching teleport id.
     */
    private void recordPendingTeleport(UUID playerId, WrapperPlayServerPlayerPositionAndLook wrapper) {
        Tracked entry = tracked.get(playerId);
        if (entry == null) {
            return;
        }
        Position current = entry.position.get();
        if (current == null) {
            return;
        }
        Vector3d target = wrapper.getPosition();
        double x = wrapper.isRelativeFlag(RelativeFlag.X) ? current.x() + target.getX() : target.getX();
        double y = wrapper.isRelativeFlag(RelativeFlag.Y) ? current.y() + target.getY() : target.getY();
        double z = wrapper.isRelativeFlag(RelativeFlag.Z) ? current.z() + target.getZ() : target.getZ();
        entry.pendingTeleport = new PendingTeleport(wrapper.getTeleportId(), x, y, z);
    }

    private void confirmTeleport(UUID playerId, int teleportId) {
        Tracked entry = tracked.get(playerId);
        if (entry == null) {
            return;
        }
        PendingTeleport pending = entry.pendingTeleport;
        if (pending == null || pending.id() != teleportId) {
            return;
        }
        entry.pendingTeleport = null;
        updatePosition(playerId, pending.x(), pending.y(), pending.z());
    }

    private record PendingTeleport(int id, double x, double y, double z) {
    }

    private static final class Tracked {
        private final Player player;
        private final AtomicReference<Position> position;
        private volatile PendingTeleport pendingTeleport;

        private Tracked(Player player, Position position) {
            this.player = player;
            this.position = new AtomicReference<>(position);
        }
    }

    private final class MovementListener extends PacketListenerAbstract {

        private MovementListener() {
            super(PacketListenerPriority.MONITOR);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            UUID playerId = event.getUser() == null ? null : event.getUser().getUUID();
            if (playerId == null) {
                return;
            }
            try {
                handleReceive(event, playerId);
            } catch (RuntimeException exception) {
                // Never let a malformed packet break the connection's pipeline.
                plugin.getLogger().log(Level.WARNING, "Failed to read a movement packet", exception);
            }
        }

        private void handleReceive(PacketReceiveEvent event, UUID playerId) {
            PacketTypeCommon type = event.getPacketType();
            if (WrapperPlayClientPlayerFlying.isFlying(type)) {
                WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
                if (!wrapper.hasPositionChanged()) {
                    return;
                }
                com.github.retrooper.packetevents.protocol.world.Location location = wrapper.getLocation();
                updatePosition(playerId, location.getX(), location.getY(), location.getZ());
            } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
                Vector3d position = new WrapperPlayClientVehicleMove(event).getPosition();
                updatePosition(playerId, position.getX(), position.getY(), position.getZ());
            } else if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
                confirmTeleport(playerId, new WrapperPlayClientTeleportConfirm(event).getTeleportId());
            }
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (event.getPacketType() != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                return;
            }
            UUID playerId = event.getUser() == null ? null : event.getUser().getUUID();
            if (playerId == null) {
                return;
            }
            try {
                recordPendingTeleport(playerId, new WrapperPlayServerPlayerPositionAndLook(event));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to read a teleport packet", exception);
            }
        }
    }
}
