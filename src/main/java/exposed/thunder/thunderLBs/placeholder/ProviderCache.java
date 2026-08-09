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

/**
 * Caches resolved provider values so the thread asking for one never waits on a provider.
 *
 * <p>Reads are fully asynchronous: a fresh value is returned as is, a stale one is returned
 * immediately <em>and</em> a refresh is submitted to run elsewhere, and a cold miss returns
 * {@code null} while the first load runs in the background. Callers already render their missing
 * text for {@code null}, so a board shows that for at most one refresh cycle after the first ask
 * and fills in once the load lands.
 *
 * <p>Deliberately free of Bukkit types: the clock, the resolver and the executor are all injected,
 * so the whole class can be exercised in a plain unit test.
 */
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

    /**
     * Everything that can change a resolved value. A record so equality and hashing come for free.
     *
     * @param viewer the viewer's id for viewer-relative lookups, otherwise {@code null}; holders are
     *               identified by {@code holder} instead, so two boards showing the same holder
     *               share one entry
     */
    public record Key(String provider,
                      LeaderboardType type,
                      String holder,
                      String interval,
                      int position,
                      PlaceholderBridge.ProviderValue value,
                      UUID viewer,
                      String placeholder) {
    }

    /**
     * Returns the cached value, refreshing in the background when it has aged past {@code ttlMillis}.
     * Never resolves on the calling thread: a cold miss returns {@code null} and schedules the first
     * load on the key's executor instead.
     */
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

    /**
     * Drops idle viewer-scoped entries, so per-player keys cannot accumulate. Board-scoped keys
     * (no viewer) are deliberately kept: they are bounded by the configured boards, and a board
     * whose page rotation is longer than the idle window must not lose its values between shows —
     * every show would then be a cold miss and the board would never display real data.
     */
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
        // One in-flight refresh per key: several boards reading the same stale value must not each
        // queue their own provider call.
        if (!entry.refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            executorFor.apply(key).execute(() -> refresh(key, entry));
        } catch (RuntimeException rejected) {
            // A saturated queue just leaves the value stale until the next read asks again.
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
            // Keep serving the last good value; a provider outage must not blank the board. The
            // load state is left untouched so the next read retries — a still-pending entry stays
            // pending. The resolver logs its own failures, so this stays quiet rather than
            // spamming once per refresh.
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
