package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderQueriesTest {

    private static PluginConfig.Provider provider() {
        return new PluginConfig.Provider(
                "top_%holder%_%interval%_name_%position%",
                "top_%holder%_%interval%_value_%position%",
                "top_%holder%_%interval%_rank",
                "top_%holder%_%interval%_score",
                "group_%holder%_%interval%_name_%position%",
                "group_%holder%_%interval%_value_%position%",
                "group_%holder%_%interval%_rank",
                "group_%holder%_%interval%_score",
                "group_%holder%_%interval%_team");
    }

    private static ProviderQueries queries(LeaderboardType type, String interval, boolean nativeProvider) {
        return ProviderQueries.create("demo", provider(), type, "kills", interval, nativeProvider);
    }

    @Test
    void holderAndIntervalBindOnceWhilePositionVariesPerRow() {
        ProviderQueries queries = queries(LeaderboardType.PLAYER, "weekly", false);

        assertEquals("top_kills_weekly_name_1", queries.topNamePlaceholder(1));
        assertEquals("top_kills_weekly_name_7", queries.topNamePlaceholder(7));
        assertEquals("top_kills_weekly_value_1", queries.topValuePlaceholder(1));
        assertEquals("top_kills_weekly_rank", queries.viewerRankPlaceholder());
        assertEquals("top_kills_weekly_score", queries.viewerValuePlaceholder());
    }

    @Test
    void groupBoardsUseTheGroupPatterns() {
        ProviderQueries queries = queries(LeaderboardType.GROUP, "weekly", false);

        assertEquals("group_kills_weekly_name_1", queries.topNamePlaceholder(1));
        assertEquals("group_kills_weekly_rank", queries.viewerRankPlaceholder());
        assertEquals("group_kills_weekly_team", queries.teamPlaceholder());
    }

    @Test
    void playerBoardsHaveNoTeamPlaceholder() {
        assertEquals("", queries(LeaderboardType.PLAYER, "weekly", false).teamPlaceholder());
    }

    @Test
    void nativeProviderSourcesDescribeTheQueryRatherThanAPlaceholder() {
        ProviderQueries queries = ProviderQueries.create("topper", provider(),
                LeaderboardType.PLAYER, "kills", "alltime", true);

        assertEquals("topper:kills;top_name;1", queries.topNameSource(1));
        assertEquals("topper:kills;top_value_raw;3", queries.topValueSource(3));
        assertEquals("topper:kills;top_rank", queries.viewerRankSource());
        assertEquals("topper:kills;value_raw", queries.viewerValueSource());
    }

    @Test
    void placeholderProviderSourcesAreThePlaceholderItself() {
        ProviderQueries queries = queries(LeaderboardType.PLAYER, "weekly", false);

        assertEquals(queries.topNamePlaceholder(2), queries.topNameSource(2));
        assertEquals(queries.topValuePlaceholder(2), queries.topValueSource(2));
        assertEquals(queries.viewerRankPlaceholder(), queries.viewerRankSource());
        assertEquals(queries.viewerValuePlaceholder(), queries.viewerValueSource());
    }

    @Test
    void intervalRewritesAlltimeWhenThePatternHasNoIntervalToken() {
        PluginConfig.Provider legacy = new PluginConfig.Provider(
                "top_%holder%_alltime_name_%position%", "", "", "", "", "", "", "", "");
        ProviderQueries queries = ProviderQueries.create("demo", legacy,
                LeaderboardType.PLAYER, "kills", "monthly", false);

        assertEquals("top_kills_monthly_name_1", queries.topNamePlaceholder(1));
    }

    @Test
    void emptyPatternsResolveToEmptyStringsRatherThanFailing() {
        ProviderQueries queries = ProviderQueries.create("demo", PluginConfig.Provider.empty(),
                LeaderboardType.PLAYER, "kills", "alltime", false);

        assertEquals("", queries.topNamePlaceholder(1));
        assertEquals("", queries.viewerRankPlaceholder());
        assertEquals("", queries.teamPlaceholder());
    }
}
