package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class ProviderCache {

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Function<Key, String> resolver;
    private final LongSupplier clock;
    private final Function<Key, Executor> executorFor;

    public ProviderCache(Function<Key, String> resolver,
            LongSupplier clock,
            Function<Key, Executor> executorFor) {
        this.resolver = resolver;
        this.clock = clock;
        this.executorFor = executorFor;
    }

    public record Key(String provider,
                      LeaderboardType type,
                      String holder,
                      String interval,
                      int position,
                      PlaceholderBridge.ProviderValue value,
                      UUID viewer,
                      String placeholder) {
    }

    public String read(Key key, long ttlMillis) {
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = entries.computeIfAbsent(key, ignored -> new Entry(clock.getAsLong()));
        }
        long now = clock.getAsLong();
        entry.lastReadAt = now;
        if (!entry.loaded || now - entry.resolvedAt >= ttlMillis) {
            submitRefresh(key, entry);
        }
        return entry.value;
    }

    public void sweep(long maxIdleMillis) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> mapEntry = iterator.next();
            if (mapEntry.getKey().viewer() == null) {
                continue;
            }
            if (now - mapEntry.getValue().lastReadAt > maxIdleMillis) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private void submitRefresh(Key key, Entry entry) {
        if (!entry.refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            executorFor.apply(key).execute(() -> refresh(key, entry));
        } catch (RuntimeException rejected) {
            entry.refreshing.set(false);
        }
    }

    private void refresh(Key key, Entry entry) {
        try {
            String value = resolver.apply(key);
            entry.value = value;
            entry.resolvedAt = clock.getAsLong();
            entry.loaded = true;
        } catch (RuntimeException exception) {
        } finally {
            entry.refreshing.set(false);
        }
    }

    private static final class Entry {
        private volatile String value;
        private volatile boolean loaded;
        private volatile long resolvedAt;
        private volatile long lastReadAt;
        private final AtomicBoolean refreshing = new AtomicBoolean();

        private Entry(long now) {
            this.lastReadAt = now;
        }
    }
}
