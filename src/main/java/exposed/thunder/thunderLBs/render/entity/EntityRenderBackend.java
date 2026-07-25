package exposed.thunder.thunderLBs.render.entity;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.leaderboard.Leaderboard;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.render.DisplayOptions;
import exposed.thunder.thunderLBs.render.RenderBackend;
import exposed.thunder.thunderLBs.render.TextAlignment;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import exposed.thunder.thunderLBs.util.VersionSupport;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class EntityRenderBackend implements RenderBackend {
    private final ThunderLBs plugin;
    private final RegionTaskScheduler scheduler;

    public EntityRenderBackend(ThunderLBs plugin) {
        this.plugin = plugin;
        this.scheduler = RegionTaskScheduler.create(plugin);
    }

    @Override
    public String name() {
        return "entity";
    }

    @Override
    public void register(Leaderboard board) {
        World world = board.world();
        if (world == null) {
            return;
        }
        if (!world.isChunkLoaded(board.origin().getBlockX() >> 4, board.origin().getBlockZ() >> 4)) {
            return;
        }
        for (org.bukkit.entity.Entity candidate : world.getChunkAt(board.origin()).getEntities()) {
            if (candidate instanceof TextDisplay display && display.getScoreboardTags().contains(board.tag())) {
                display.remove();
            }
        }
    }

    @Override
    public BoardDisplay spawn(Leaderboard board, Location location, DisplayOptions options) {
        World world = location.getWorld();
        TextDisplay entity = world.spawn(location, TextDisplay.class, display -> {
            display.setBillboard(options.billboard());
            display.setPersistent(false);
            display.setShadowed(options.shadowed());
            display.setSeeThrough(options.seeThrough());
            display.setBrightness(new Display.Brightness(15, 15));
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setLineWidth(options.lineWidth());
            display.setAlignment(paperAlignment(options.alignment()));
            display.setViewRange(options.viewRange());
            display.text(options.text());
            display.setTextOpacity(options.opacity());
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(options.interpolationDuration());
            if (VersionSupport.teleportDurationSupported()) {
                display.setTeleportDuration(options.teleportDuration());
            }
            display.setTransformation(new Transformation(
                    new Vector3f(options.translation()),
                    new Quaternionf(),
                    new Vector3f(options.scale()),
                    new Quaternionf()));
            display.addScoreboardTag(board.tag());
            if (plugin.leaderboardDisplayKey() != null) {
                display.getPersistentDataContainer().set(plugin.leaderboardDisplayKey(), PersistentDataType.BYTE,
                        (byte) 1);
            }
            if (options.viewer() != null) {
                display.setVisibleByDefault(false);
            }
        });
        if (options.viewer() != null) {
            scheduler.execute(options.viewer(), () -> options.viewer().showEntity(plugin, entity), null);
        }
        return new EntityBoardDisplay(entity);
    }


    private static TextDisplay.TextAlignment paperAlignment(TextAlignment alignment) {
        return switch (alignment) {
            case LEFT -> TextDisplay.TextAlignment.LEFT;
            case RIGHT -> TextDisplay.TextAlignment.RIGHT;
            default -> TextDisplay.TextAlignment.CENTER;
        };
    }

    @Override
    public void unregister(Leaderboard board) {}

    @Override
    public void shutdown() {}
}
