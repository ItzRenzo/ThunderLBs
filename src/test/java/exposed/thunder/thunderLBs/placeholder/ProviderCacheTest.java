package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCacheTest {

    private static final long TTL = 100L;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final List<Runnable> queued = new ArrayList<>();

    private ProviderCache cache(Function<ProviderCache.Key, String> resolver) {
        return new ProviderCache(resolver, clock::get, key -> queued::add);
    }

    private void drainRefreshes() {
        List<Runnable> pending = new ArrayList<>(queued);
        queued.clear();
        pending.forEach(Runnable::run);
    }

    private static ProviderCache.Key key(String holder) {
        return new ProviderCache.Key("topper", LeaderboardType.PLAYER, holder, "alltime", 1,
                PlaceholderBridge.ProviderValue.TOP_NAME, null, "%topper_" + holder + "_top_name_1%");
    }

    private static ProviderCache.Key viewerKey(String holder, java.util.UUID viewer) {
        return new ProviderCache.Key("topper", LeaderboardType.PLAYER, holder, "alltime", 0,
                PlaceholderBridge.ProviderValue.VIEWER_RANK, viewer, "%topper_" + holder + "_rank%");
    }

    @Test
    void coldMissReturnsNothingAndLoadsInTheBackground() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache cache = cache(key -> "value-" + calls.incrementAndGet());

        assertNull(cache.read(key("kills"), TTL), "a cold miss must not resolve on the calling thread");
        assertEquals(0, calls.get());
        assertEquals(1, queued.size());

        drainRefreshes();
        assertEquals("value-1", cache.read(key("kills"), TTL));
        assertEquals(1, calls.get());
    }

    @Test
    void pendingReadsQueueOnlyOneLoad() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache cache = cache(key -> "value-" + calls.incrementAndGet());

        cache.read(key("kills"), TTL);
        cache.read(key("kills"), TTL);
        cache.read(key("kills"), TTL);

        assertEquals(1, queued.size(), "one in-flight load per key");
        assertEquals(0, calls.get());
    }

    @Test
    void freshReadsServeTheCachedValueWithoutResolvingAgain() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache cache = cache(key -> "value-" + calls.incrementAndGet());

        cache.read(key("kills"), TTL);
        drainRefreshes();
        clock.addAndGet(TTL - 1);

        assertEquals("value-1", cache.read(key("kills"), TTL));
        assertEquals(1, calls.get());
        assertTrue(queued.isEmpty());
    }

    @Test
    void boardsAskingForTheSameHolderShareOneEntry() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache cache = cache(key -> "value-" + calls.incrementAndGet());

        // Two leaderboards showing the same holder/interval/position produce equal keys.
        cache.read(key("kills"), TTL);
        drainRefreshes();
        assertEquals("value-1", cache.read(key("kills"), TTL));

        assertEquals(1, calls.get());
        assertEquals(1, cache.size());
    }

    @Test
    void staleReadServesTheOldValueAndRefreshesInTheBackground() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache cache = cache(key -> "value-" + calls.incrementAndGet());

        cache.read(key("kills"), TTL);
        drainRefreshes();
        clock.addAndGet(TTL + 1);

        assertEquals("value-1", cache.read(key("kills"), TTL), "a stale read must not block on the provider");
        assertEquals(1, queued.size());

        drainRefreshes();
        assertEquals("value-2", cache.read(key("kills"), TTL));
    }

    @Test
    void repeatedStaleReadsQueueOnlyOneRefresh() {
        ProviderCache cache = cache(key -> "value");

        cache.read(key("kills"), TTL);
        drainRefreshes();
        clock.addAndGet(TTL + 1);

        cache.read(key("kills"), TTL);
        cache.read(key("kills"), TTL);
        cache.read(key("kills"), TTL);

        assertEquals(1, queued.size(), "one in-flight refresh per key");
    }

    @Test
    void aFailingRefreshKeepsTheLastGoodValueAndRetriesLater() {
        AtomicBoolean failing = new AtomicBoolean();
        ProviderCache cache = cache(key -> {
            if (failing.get()) {
                throw new IllegalStateException("provider is down");
            }
            return "good";
        });

        cache.read(key("kills"), TTL);
        drainRefreshes();
        assertEquals("good", cache.read(key("kills"), TTL));

        failing.set(true);
        clock.addAndGet(TTL + 1);
        cache.read(key("kills"), TTL);
        assertDoesNotThrow(this::drainRefreshes);

        // Value survives the outage, and the entry is not wedged: it queues another attempt.
        assertEquals("good", cache.read(key("kills"), TTL));
        assertEquals(1, queued.size(), "the in-flight flag must be cleared after a failure");

        failing.set(false);
        drainRefreshes();
        assertEquals("good", cache.read(key("kills"), TTL));
    }

    @Test
    void aFailedFirstLoadLeavesTheEntryPendingAndRetries() {
        AtomicBoolean failing = new AtomicBoolean(true);
        ProviderCache cache = cache(key -> {
            if (failing.get()) {
                throw new IllegalStateException("provider is down");
            }
            return "good";
        });

        assertNull(cache.read(key("kills"), TTL));
        assertDoesNotThrow(this::drainRefreshes);

        assertNull(cache.read(key("kills"), TTL), "a failed first load must not cache a value");
        assertEquals(1, queued.size(), "a pending entry must retry on the next read");

        failing.set(false);
        drainRefreshes();
        assertEquals("good", cache.read(key("kills"), TTL));
    }

    @Test
    void aRejectedLoadLeavesTheEntryRetryable() {
        AtomicInteger submissions = new AtomicInteger();
        Executor saturated = runnable -> {
            submissions.incrementAndGet();
            throw new RejectedExecutionException("queue is full");
        };
        ProviderCache cache = new ProviderCache(key -> "value", clock::get, key -> saturated);

        assertDoesNotThrow(() -> cache.read(key("kills"), TTL), "a full queue must not fail the read");
        assertEquals(1, submissions.get());

        cache.read(key("kills"), TTL);
        assertEquals(2, submissions.get(), "a rejected load must not leave the key stuck");
    }

    @Test
    void sweepDropsIdleViewerEntriesAndKeepsRecentlyReadOnes() {
        ProviderCache cache = cache(key -> "value");
        java.util.UUID idle = java.util.UUID.randomUUID();
        java.util.UUID fresh = java.util.UUID.randomUUID();

        cache.read(viewerKey("kills", idle), TTL);
        cache.read(viewerKey("kills", fresh), TTL);
        drainRefreshes();
        assertEquals(2, cache.size());

        clock.addAndGet(10_000L);
        cache.read(viewerKey("kills", fresh), TTL);

        cache.sweep(5_000L);

        assertEquals(1, cache.size(), "only the viewer entry nothing has read recently should go");
        assertEquals("value", cache.read(viewerKey("kills", fresh), TTL));
    }

    @Test
    void sweepNeverDropsBoardScopedEntries() {
        ProviderCache cache = cache(key -> "value");

        // A board whose page rotation is longer than the idle window reads its keys rarely; the
        // sweep must not turn every show into a cold miss.
        cache.read(key("kills"), TTL);
        drainRefreshes();

        clock.addAndGet(100_000L);
        cache.sweep(5_000L);

        assertEquals(1, cache.size(), "board-scoped keys are bounded by config and must survive");
        assertEquals("value", cache.read(key("kills"), TTL));
    }
}
