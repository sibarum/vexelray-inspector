package dev.sibarum.inspector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The words the tape's reach is offered and drawn in. */
class TapeViewTest {

    @Test
    void theUsualReachesAreOfferedAndTheSettingIsPutAmongThem() {
        assertArrayEquals(new int[] {5, 10, 30, 60, 300}, TapeView.choices(10), "10 s is one of them");
        assertArrayEquals(new int[] {2, 5, 10, 30, 60, 300}, TapeView.choices(2));
        assertArrayEquals(new int[] {5, 10, 20, 30, 60, 300}, TapeView.choices(20));
        assertArrayEquals(new int[] {5, 10, 30, 60, 300, 900}, TapeView.choices(900), "and past the last");
    }

    @Test
    void aReachIsSaidInSecondsOrWholeMinutes() {
        assertEquals("5 s", TapeView.seconds(5_000_000_000L));
        assertEquals("2.5 s", TapeView.seconds(2_500_000_000L));
        assertEquals("1 min", TapeView.seconds(60_000_000_000L));
        assertEquals("90 s", TapeView.seconds(90_000_000_000L));
        assertEquals("5 min", TapeView.seconds(300_000_000_000L));
    }
}
