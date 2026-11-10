package dev.klleriston.netlab.impair;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImpairmentProfileTest {
    @Test void presetsMatchSpec() {
        assertEquals(new ImpairmentProfile(5, 30, 20, 3), ImpairmentProfile.preset("wifi-ruim").orElseThrow());
        assertEquals(new ImpairmentProfile(2, 150, 60, 1), ImpairmentProfile.preset("3g").orElseThrow());
    }

    @Test void unknownPresetIsEmpty() {
        assertTrue(ImpairmentProfile.preset("nao-existe").isEmpty());
    }

    @Test void rejectsLossAbove100AndNegativeDelay() {
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentProfile(101, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentProfile(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentProfile(0, 0, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentProfile(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentProfile(0, 0, -1, 0));
    }

    @Test void noneIsNone() {
        assertTrue(ImpairmentProfile.NONE.isNone());
        assertFalse(new ImpairmentProfile(1, 0, 0, 0).isNone());
    }
}
