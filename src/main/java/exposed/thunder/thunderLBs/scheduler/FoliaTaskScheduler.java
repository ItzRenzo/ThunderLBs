package exposed.thunder.thunderLBs.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.Consumer;

final class FoliaTaskScheduler implements RegionTaskScheduler {
    private final Plugin plugin;

    FoliaTaskScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(Location location, Runnable action) {
        Location anchor = anchorOrNull(location);
        if (anchor == null) {
            // The world is not loaded, so no region owns this location. Fall back to the global
            // region so cleanup and shutdown paths still run instead of throwing.
            runGlobal(action);
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(anchor)) {
            action.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, anchor, action);
    }

    @Override
    public TaskHandle run(Location location, Consumer<TaskHandle> action) {
        Handle handle = new Handle();
        handle.attach(Bukkit.getRegionScheduler().run(plugin, requireWorld(location),
                task -> action.accept(handle)));
        return handle;
    }

    @Override
    public TaskHandle runDelayed(Location location, Consumer<TaskHandle> action, long delayTicks) {
        Location anchor = requireWorld(location);
        Handle handle = new Handle();
        if (delayTicks <= 0L) {
            handle.attach(Bukkit.getRegionScheduler().run(plugin, anchor, task -> action.accept(handle)));
        } else {
            handle.attach(Bukkit.getRegionScheduler().runDelayed(plugin, anchor,
                    task -> action.accept(handle), delayTicks));
        }
        return handle;
    }

    @Override
    public TaskHandle runAtFixedRate(Location location, Consumer<TaskHandle> action,
                                     long initialDelayTicks, long periodTicks) {
        Handle handle = new Handle();
        handle.attach(Bukkit.getRegionScheduler().runAtFixedRate(
                plugin,
                requireWorld(location),
                task -> action.accept(handle),
                Math.max(1L, initialDelayTicks),
                Math.max(1L, periodTicks)));
        return handle;
    }

    @Override
    public void execute(World world, int chunkX, int chunkZ, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            action.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, action);
    }

    @Override
    public void execute(Entity entity, Runnable action, Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        entity.getScheduler().execute(plugin, action, retired, 1L);
    }

    @Override
    public void executeDelayed(Entity entity, Runnable action, Runnable retired, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        entity.getScheduler().execute(plugin, action, retired, Math.max(1L, delayTicks));
    }

    @Override
    public void runGlobal(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run());
    }

    @Override
    public TaskHandle runGlobalAtFixedRate(Runnable action, long initialDelayTicks, long periodTicks) {
        Handle handle = new Handle();
        handle.attach(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> action.run(),
                Math.max(1L, initialDelayTicks),
                Math.max(1L, periodTicks)));
        return handle;
    }

    private static Location requireWorld(Location location) {
        Objects.requireNonNull(location, "location");
        return Objects.requireNonNull(anchorOrNull(location), "location world");
    }

    private static Location anchorOrNull(Location location) {
        if (location == null) {
            return null;
        }
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        // Re-resolve by UID: a Location can outlive a world manager's unload/reload cycle, and
        // dispatching against its stale World instance would target a region that no longer
        // exists. An unloaded world falls through to the global fallback like a null world does.
        World current = Bukkit.getWorld(world.getUID());
        if (current == null) {
            return null;
        }
        return new Location(current, location.getX(), location.getY(), location.getZ());
    }

    private static final class Handle implements TaskHandle {
        private ScheduledTask task;
        private boolean cancelled;

        private synchronized void attach(ScheduledTask task) {
            this.task = task;
            if (cancelled) {
                task.cancel();
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }
    }
}
