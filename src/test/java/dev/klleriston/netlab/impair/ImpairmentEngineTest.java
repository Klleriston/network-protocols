package dev.klleriston.netlab.impair;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ImpairmentEngineTest {
    private static final int N = 10_000;

    private static ImpairmentEngine engine() {
        return new ImpairmentEngine(new Random(42));
    }

    @Test void zeroLossNeverDrops() {
        var e = engine();
        for (int i = 0; i < N; i++) {
            Decision d = e.decide(ImpairmentProfile.NONE);
            assertFalse(d.drop());
            assertEquals(0, d.delayMillis());
        }
    }

    @Test void fullLossAlwaysDrops() {
        var e = engine();
        var p = new ImpairmentProfile(100, 0, 0, 0);
        for (int i = 0; i < N; i++) assertTrue(e.decide(p).drop());
    }

    @Test void tenPercentLossStaysWithin8And12Percent() {
        var e = engine();
        var p = new ImpairmentProfile(10, 0, 0, 0);
        int drops = 0;
        for (int i = 0; i < N; i++) if (e.decide(p).drop()) drops++;
        assertTrue(drops >= 800 && drops <= 1200, "drops=" + drops);
    }

    @Test void delayStaysWithinDelayPlusMinusJitter() {
        var e = engine();
        var p = new ImpairmentProfile(0, 30, 20, 0);
        for (int i = 0; i < N; i++) {
            long d = e.decide(p).delayMillis();
            assertTrue(d >= 10 && d <= 50, "delay=" + d);
        }
    }

    @Test void delayNeverNegative() {
        var e = engine();
        var p = new ImpairmentProfile(0, 5, 20, 0);
        for (int i = 0; i < N; i++) assertTrue(e.decide(p).delayMillis() >= 0);
    }

    @Test void reorderAddsHoldBeyondMaxNormalDelay() {
        var e = engine();
        var p = new ImpairmentProfile(0, 30, 20, 100);
        for (int i = 0; i < N; i++) assertTrue(e.decide(p).delayMillis() > 50);
    }
}
