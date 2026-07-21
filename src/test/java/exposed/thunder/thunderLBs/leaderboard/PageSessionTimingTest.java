package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.animation.EasingType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSessionTimingTest {
    @Test
    void progressCycleIncludesEntranceBeforeVisiblePageDuration() {
        assertEquals(45L, PageSession.progressCycleTicks(35L, 10));
    }

    @Test
    void interpolationMatchesSpacingBetweenProgressFrames() {
        assertEquals(5, PageSession.progressInterpolationTicks(45L, 10));
    }

    @Test
    void progressInterpolationNeverDropsBelowOneTick() {
        assertEquals(1, PageSession.progressInterpolationTicks(1L, 20));
    }

    @Test
    void barFadeEndsAtPageSwitch() {
        assertEquals(5L, PageSession.barFadeDelayTicks(60L, 45L, 10));
        assertEquals(5L, PageSession.barFadeDelayTicks(100L, 85L, 10));
        assertEquals(5L, PageSession.barFadeDelayTicks(160L, 145L, 10));
    }

    @Test
    void barFadeDelayNeverDropsBelowOneTick() {
        assertEquals(1L, PageSession.barFadeDelayTicks(10L, 10L, 10));
    }

    @Test
    void linearFrameDurationPreservesEndpointTiming() {
        assertEquals(19, PageSession.linearInterpolationTicks(20));
        assertEquals(20L, PageSession.linearCompletionDelayTicks(20));
    }

    @Test
    void linearDurationsNeverDropBelowOneTick() {
        assertEquals(1, PageSession.linearInterpolationTicks(1));
        assertEquals(1L, PageSession.linearCompletionDelayTicks(1));
        assertEquals(1, PageSession.linearCycleInterpolationTicks(1L));
    }

    @Test
    void linearBarFinishesAtTheExistingCycleBoundary() {
        assertEquals(44, PageSession.linearCycleInterpolationTicks(45L));
    }

    @Test
    void onlyEnabledMultiFrameLinearPhasesUseClientInterpolation() {
        assertTrue(PageSession.usesClientInterpolation(true, EasingType.LINEAR, 20));
        assertFalse(PageSession.usesClientInterpolation(false, EasingType.LINEAR, 20));
        assertFalse(PageSession.usesClientInterpolation(true, EasingType.LINEAR, 1));
        assertFalse(PageSession.usesClientInterpolation(true, EasingType.EASE_IN_CUBIC, 20));
    }

    @Test
    void compactClientInterpolationIsLimitedToOneActiveChannel() {
        assertFalse(PageSession.usesClientInterpolation(
                true, EasingType.LINEAR, true, EasingType.LINEAR, 20));
        assertTrue(PageSession.usesClientInterpolation(
                false, EasingType.LINEAR, true, EasingType.LINEAR, 20));
        assertTrue(PageSession.usesClientInterpolation(
                true, EasingType.LINEAR, false, EasingType.LINEAR, 20));
        assertFalse(PageSession.usesClientInterpolation(
                true, EasingType.EASE_OUT_CUBIC, true, EasingType.LINEAR, 20));
        assertFalse(PageSession.usesClientInterpolation(
                true, EasingType.LINEAR, true, EasingType.EASE_IN_SINE, 20));
    }

    @Test
    void fullRangeLinearOpacitySplitsAroundSignedByteBoundary() {
        PageSession.LinearOpacitySplit split = PageSession.linearOpacitySplit(20);

        assertEquals(19, split.totalTicks());
        assertEquals(9, split.firstTicks());
        assertEquals(9, split.secondTicks());
        assertEquals(19, split.firstTicks() + 1 + split.secondTicks());
    }

    @Test
    void veryShortOpacityPhasesUsePerFrameFallback() {
        assertFalse(PageSession.usesClientInterpolation(
                true, EasingType.LINEAR, true, EasingType.LINEAR, 3));
    }

    @Test
    void opacityKeepsAnimatingWhenMotionIsDisabled() {
        assertEquals(20, PageSession.phaseFrameCount(1, true, 20));
        assertEquals(1, PageSession.phaseFrameCount(1, false, 20));
    }

    @Test
    void perFrameLinearOpacityTraversesUnsignedByteRange() {
        assertEquals(0, Byte.toUnsignedInt(
                PageSession.opacityAtFrame(0, 11, true, true, EasingType.LINEAR)));
        assertEquals(128, Byte.toUnsignedInt(
                PageSession.opacityAtFrame(5, 11, true, true, EasingType.LINEAR)));
        assertEquals(255, Byte.toUnsignedInt(
                PageSession.opacityAtFrame(10, 11, true, true, EasingType.LINEAR)));
        assertEquals(255, Byte.toUnsignedInt(
                PageSession.opacityAtFrame(0, 11, true, false, EasingType.LINEAR)));
    }

    @Test
    void boardScaleAppliesToRowOffsetAndSpacing() {
        assertEquals(-2.25D, PageSession.scaledRowOffset(-0.8D, 0.325D, 1, 2.0D));
    }

    @Test
    void typingBroadcastKeepsConfiguredVolumeWithinNaturalRange() {
        assertEquals(0.5F, PageSession.typingBroadcastVolume(0.5F, 16.0D));
        assertEquals(2.0F, PageSession.typingBroadcastVolume(2.0F, 16.0D));
    }

    @Test
    void typingBroadcastRaisesVolumeOnlyToReachLargerRadius() {
        assertEquals(2.0F, PageSession.typingBroadcastVolume(0.5F, 32.0D));
    }
}
