package exposed.thunder.thunderLBs.render.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.render.DisplayOptions;
import exposed.thunder.thunderLBs.render.TextAlignment;
import exposed.thunder.thunderLBs.util.VersionSupport;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PacketBoardDisplay implements BoardDisplay {
    private static final int BRIGHTNESS_FULL = (15 << 4) | (15 << 20);
    private static final int BACKGROUND_TRANSPARENT = 0;

    private static final boolean TELEPORT_DURATION_METADATA = VersionSupport.teleportDurationSupported();
    private static final int SHIFT = TELEPORT_DURATION_METADATA ? 1 : 0;

    private static final int INDEX_INTERPOLATION_DELAY = 8;
    private static final int INDEX_INTERPOLATION_DURATION = 9;
    private static final int INDEX_TELEPORT_DURATION = 10;
    private static final int INDEX_TRANSLATION = 10 + SHIFT;
    private static final int INDEX_SCALE = 11 + SHIFT;
    private static final int INDEX_BILLBOARD = 14 + SHIFT;
    private static final int INDEX_BRIGHTNESS = 15 + SHIFT;
    private static final int INDEX_VIEW_RANGE = 16 + SHIFT;
    private static final int INDEX_TEXT = 22 + SHIFT;
    private static final int INDEX_LINE_WIDTH = 23 + SHIFT;
    private static final int INDEX_BACKGROUND = 24 + SHIFT;
    private static final int INDEX_OPACITY = 25 + SHIFT;
    private static final int INDEX_FLAGS = 26 + SHIFT;

    private final PacketRenderBackend backend;
    private final int entityId;
    private final UUID uuid;
    private final PacketRenderBackend.Channel channel;
    private final Player fixedViewer;
    private final WrapperPlayServerDestroyEntities destroyPacket;

    private Location location;
    private Component text;
    private byte opacity;
    private Vector3f translation;
    private Vector3f scale;
    private int interpolationDelay;
    private int interpolationDuration;
    private final int teleportDuration;
    private final byte billboardValue;
    private final byte flags;
    private final int lineWidth;
    private final float viewRange;
    private boolean removed;

    PacketBoardDisplay(PacketRenderBackend backend,
            int entityId,
            UUID uuid,
            PacketRenderBackend.Channel channel,
            Location location,
            DisplayOptions options) {
        this.backend = backend;
        this.entityId = entityId;
        this.uuid = uuid;
        this.channel = channel;
        this.fixedViewer = options.viewer();
        this.destroyPacket = new WrapperPlayServerDestroyEntities(entityId);
        this.location = location.clone();
        this.text = options.text();
        this.opacity = options.opacity();
        this.translation = new Vector3f(options.translation());
        this.scale = new Vector3f(options.scale());
        this.interpolationDelay = 0;
        this.interpolationDuration = options.interpolationDuration();
        this.teleportDuration = options.teleportDuration();
        this.billboardValue = billboardByte(options.billboard());
        this.flags = packFlags(options.shadowed(), options.seeThrough(), options.alignment());
        this.lineWidth = options.lineWidth();
        this.viewRange = options.viewRange();
    }

    int entityId() {
        return entityId;
    }

    PacketRenderBackend.Channel channel() {
        return channel;
    }

    private static byte billboardByte(Display.Billboard billboard) {
        return switch (billboard) {
            case FIXED -> (byte) 0;
            case VERTICAL -> (byte) 1;
            case HORIZONTAL -> (byte) 2;
            case CENTER -> (byte) 3;
        };
    }

    private static byte packFlags(boolean shadowed, boolean seeThrough, TextAlignment alignment) {
        byte value = 0;
        if (shadowed) {
            value |= 0x01;
        }
        if (seeThrough) {
            value |= 0x02;
        }


        value |= (byte) alignment.flagBits();
        return value;
    }

    private void broadcast(PacketWrapper<?> wrapper) {
        if (fixedViewer != null) {
            if (fixedViewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(fixedViewer, wrapper);
            }
            return;
        }
        if (channel == null) {
            return;
        }
        for (Player player : channel.viewerPlayers()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapper);
        }
    }

    private void sendMetadata(List<EntityData<?>> data) {
        if (removed || data.isEmpty()) {
            return;
        }
        broadcast(new WrapperPlayServerEntityMetadata(entityId, data));
    }

    synchronized void sendFullSpawn(Player player) {
        if (removed || player == null || !player.isOnline()) {
            return;
        }
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                uuid,
                EntityTypes.TEXT_DISPLAY,
                protocolLocation(location),
                location.getYaw(),
                0,
                null);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerEntityMetadata(entityId, fullMetadata()));
    }

    synchronized void sendDestroy(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, destroyPacket);
    }

    private List<EntityData<?>> fullMetadata() {
        List<EntityData<?>> data = new ArrayList<>(13);
        data.add(new EntityData<>(INDEX_INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(INDEX_INTERPOLATION_DURATION, EntityDataTypes.INT, interpolationDuration));
        if (TELEPORT_DURATION_METADATA) {
            data.add(new EntityData<>(INDEX_TELEPORT_DURATION, EntityDataTypes.INT, teleportDuration));
        }
        data.add(new EntityData<>(INDEX_TRANSLATION, EntityDataTypes.VECTOR3F, packetVector(translation)));
        data.add(new EntityData<>(INDEX_SCALE, EntityDataTypes.VECTOR3F, packetVector(scale)));
        data.add(new EntityData<>(INDEX_BILLBOARD, EntityDataTypes.BYTE, billboardValue));
        data.add(new EntityData<>(INDEX_BRIGHTNESS, EntityDataTypes.INT, BRIGHTNESS_FULL));
        data.add(new EntityData<>(INDEX_VIEW_RANGE, EntityDataTypes.FLOAT, viewRange));
        data.add(new EntityData<>(INDEX_TEXT, EntityDataTypes.ADV_COMPONENT, text));
        data.add(new EntityData<>(INDEX_LINE_WIDTH, EntityDataTypes.INT, lineWidth));
        data.add(new EntityData<>(INDEX_BACKGROUND, EntityDataTypes.INT, BACKGROUND_TRANSPARENT));
        data.add(new EntityData<>(INDEX_OPACITY, EntityDataTypes.BYTE, opacity));
        data.add(new EntityData<>(INDEX_FLAGS, EntityDataTypes.BYTE, flags));
        return data;
    }

    private static com.github.retrooper.packetevents.protocol.world.Location protocolLocation(Location location) {
        return new com.github.retrooper.packetevents.protocol.world.Location(
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static com.github.retrooper.packetevents.util.Vector3f packetVector(Vector3f vector) {
        return new com.github.retrooper.packetevents.util.Vector3f(vector.x(), vector.y(), vector.z());
    }

    @Override
    public synchronized void text(Component component) {
        this.text = component;
        sendMetadata(List.of(new EntityData<>(INDEX_TEXT, EntityDataTypes.ADV_COMPONENT, text)));
    }

    @Override
    public synchronized void opacity(byte opacity) {
        this.opacity = opacity;
        List<EntityData<?>> data = new ArrayList<>(3);
        data.add(new EntityData<>(INDEX_INTERPOLATION_DELAY, EntityDataTypes.INT, interpolationDelay));
        data.add(new EntityData<>(INDEX_INTERPOLATION_DURATION, EntityDataTypes.INT, interpolationDuration));
        data.add(new EntityData<>(INDEX_OPACITY, EntityDataTypes.BYTE, opacity));
        sendMetadata(data);
    }

    @Override
    public synchronized void interpolation(int delayTicks, int durationTicks) {
        this.interpolationDelay = delayTicks;
        this.interpolationDuration = durationTicks;
    }

    @Override
    public synchronized void transform(Vector3f translation, Vector3f scale) {
        transform(translation.x(), translation.y(), translation.z(), scale.x(), scale.y(), scale.z());
    }

    @Override
    public synchronized void transform(float translationX, float translationY, float translationZ,
                          float scaleX, float scaleY, float scaleZ) {
        this.translation.set(translationX, translationY, translationZ);
        this.scale.set(scaleX, scaleY, scaleZ);
        List<EntityData<?>> data = new ArrayList<>(4);
        data.add(new EntityData<>(INDEX_INTERPOLATION_DELAY, EntityDataTypes.INT, interpolationDelay));
        data.add(new EntityData<>(INDEX_INTERPOLATION_DURATION, EntityDataTypes.INT, interpolationDuration));
        data.add(new EntityData<>(INDEX_TRANSLATION, EntityDataTypes.VECTOR3F, packetVector(this.translation)));
        data.add(new EntityData<>(INDEX_SCALE, EntityDataTypes.VECTOR3F, packetVector(this.scale)));
        sendMetadata(data);
    }

    @Override
    public synchronized void transformAndOpacity(float translationX, float translationY, float translationZ,
                                    float scaleX, float scaleY, float scaleZ, byte opacity) {
        this.translation.set(translationX, translationY, translationZ);
        this.scale.set(scaleX, scaleY, scaleZ);
        this.opacity = opacity;
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(INDEX_INTERPOLATION_DELAY, EntityDataTypes.INT, interpolationDelay));
        data.add(new EntityData<>(INDEX_INTERPOLATION_DURATION, EntityDataTypes.INT, interpolationDuration));
        data.add(new EntityData<>(INDEX_TRANSLATION, EntityDataTypes.VECTOR3F, packetVector(this.translation)));
        data.add(new EntityData<>(INDEX_SCALE, EntityDataTypes.VECTOR3F, packetVector(this.scale)));
        data.add(new EntityData<>(INDEX_OPACITY, EntityDataTypes.BYTE, opacity));
        sendMetadata(data);
    }

    @Override
    public synchronized Vector3f translation() {
        return new Vector3f(translation);
    }

    @Override
    public synchronized Vector3f scale() {
        return new Vector3f(scale);
    }

    @Override
    public synchronized void teleport(Location location) {
        this.location = location.clone();
        if (removed) {
            return;
        }
        broadcast(new WrapperPlayServerEntityTeleport(entityId, protocolLocation(this.location), false));
    }

    @Override
    public synchronized Location location() {
        return location.clone();
    }

    @Override
    public synchronized boolean isRemoved() {
        return removed;
    }

    @Override
    public synchronized void remove() {
        if (removed) {
            return;
        }
        broadcast(destroyPacket);
        removed = true;
        backend.forget(this);
    }
}
