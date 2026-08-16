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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;

final class ViewerPositionTracker implements Listener {

    private static final long POSITION_SAMPLE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final ThunderLBs plugin;
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    private final MovementListener listener = new MovementListener();
    private PacketListenerCommon registeredListener;

    ViewerPositionTracker(ThunderLBs plugin) {
        this.plugin = plugin;
    }

    record Position(UUID worldUid, double x, double y, double z) {

        double distanceSquaredTo(Location location) {
            double deltaX = x - location.getX();
            double deltaY = y - location.getY();
            double deltaZ = z - location.getZ();
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registeredListener = PacketEvents.getAPI().getEventManager().registerListener(listener);
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    void stop() {
        HandlerList.unregisterAll(this);
        if (registeredListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            registeredListener = null;
        }
        tracked.clear();
    }

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
        refresh(event.getPlayer(), event.getRespawnLocation());
    }

    private void updatePosition(Tracked entry, double x, double y, double z) {
        Position current = entry.position.get();
        if (current == null) {
            return;
        }
        entry.position.compareAndSet(current, new Position(current.worldUid(), x, y, z));
    }

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
        updatePosition(entry, pending.x(), pending.y(), pending.z());
    }

    private record PendingTeleport(int id, double x, double y, double z) {
    }

    private static final class Tracked {
        private final Player player;
        private final AtomicReference<Position> position;
        private volatile PendingTeleport pendingTeleport;
        private volatile long lastSampleNanos;

        private Tracked(Player player, Position position) {
            this.player = player;
            this.position = new AtomicReference<>(position);
        }

        private boolean shouldSamplePosition(long nowNanos) {
            if (nowNanos - lastSampleNanos < POSITION_SAMPLE_INTERVAL_NANOS) {
                return false;
            }
            lastSampleNanos = nowNanos;
            return true;
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
                plugin.getLogger().log(Level.WARNING, "Failed to read a movement packet", exception);
            }
        }

        private void handleReceive(PacketReceiveEvent event, UUID playerId) {
            PacketTypeCommon type = event.getPacketType();
            if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
                confirmTeleport(playerId, new WrapperPlayClientTeleportConfirm(event).getTeleportId());
                return;
            }
            boolean vehicleMove = type == PacketType.Play.Client.VEHICLE_MOVE;
            if (!vehicleMove
                    && type != PacketType.Play.Client.PLAYER_POSITION
                    && type != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                return;
            }
            Tracked entry = tracked.get(playerId);
            if (entry == null || !entry.shouldSamplePosition(System.nanoTime())) {
                return;
            }
            if (vehicleMove) {
                Vector3d position = new WrapperPlayClientVehicleMove(event).getPosition();
                updatePosition(entry, position.getX(), position.getY(), position.getZ());
            } else {
                com.github.retrooper.packetevents.protocol.world.Location location =
                        new WrapperPlayClientPlayerFlying(event).getLocation();
                updatePosition(entry, location.getX(), location.getY(), location.getZ());
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
