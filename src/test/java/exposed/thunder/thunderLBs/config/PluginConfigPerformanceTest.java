package exposed.thunder.thunderLBs.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigPerformanceTest {
    @Test
    void relativeRefreshUsesPerformanceOrientedDefault() {
        assertEquals(40L, PluginConfig.Performance.from(null).relativeRefreshTicks());
    }

    @Test
    void relativeRefreshIsClampedToSafeSchedulerValues() {
        assertEquals(1L, parseRefreshTicks(0L));
        assertEquals(72_000L, parseRefreshTicks(Long.MAX_VALUE));
        assertEquals(100L, parseRefreshTicks(100L));
    }

    @Test
    void providerCacheDefaultsToRefreshingInTheBackground() {
        PluginConfig.Performance performance = PluginConfig.Performance.from(null);
        assertEquals(100L, performance.providerCacheTtlTicks());
        assertTrue(performance.asyncProviders());
        assertTrue(performance.asyncPapi());
    }

    @Test
    void providerCacheTtlIsClampedLikeTheOtherTimings() {
        assertEquals(1L, parseLong("provider-cache-ttl-ticks", 0L,
                PluginConfig.Performance::providerCacheTtlTicks));
        assertEquals(72_000L, parseLong("provider-cache-ttl-ticks", Long.MAX_VALUE,
                PluginConfig.Performance::providerCacheTtlTicks));
        assertEquals(60L, parseLong("provider-cache-ttl-ticks", 60L,
                PluginConfig.Performance::providerCacheTtlTicks));
    }

    @Test
    void sharedPacketSerializationIsOnByDefault() {
        assertTrue(PluginConfig.Performance.from(null).sharedPacketSerialization(),
                "encoding once per broadcast is strictly cheaper, and an incompatible "
                        + "packetevents build falls back to per-player sends on its own");

        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("performance");
        section.set("shared-packet-serialization", false);
        assertFalse(PluginConfig.Performance.from(section).sharedPacketSerialization(),
                "an explicit opt-out must still win");
    }

    @Test
    void asyncTogglesCanBeTurnedOffIndependently() {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("performance");
        section.set("async-papi", false);

        PluginConfig.Performance performance = PluginConfig.Performance.from(section);
        assertFalse(performance.asyncPapi(), "an unsafe expansion should be escapable on its own");
        assertTrue(performance.asyncProviders(), "native providers stay async");
    }

    private static long parseRefreshTicks(long value) {
        return parseLong("relative-refresh-ticks", value, PluginConfig.Performance::relativeRefreshTicks);
    }

    private static long parseLong(String path,
            long value,
            java.util.function.ToLongFunction<PluginConfig.Performance> reader) {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("performance");
        section.set(path, value);
        return reader.applyAsLong(PluginConfig.Performance.from(section));
    }
}
