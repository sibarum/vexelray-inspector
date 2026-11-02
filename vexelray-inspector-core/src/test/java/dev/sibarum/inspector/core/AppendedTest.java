package dev.sibarum.inspector.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The list a fold adds to without copying: whatever is made from a list, the list itself never changes, whether the
 * store it shares is added to in place or copied.
 */
class AppendedTest {

    private static List<Integer> upTo(int from, int to) {
        return IntStream.range(from, to).boxed().toList();
    }

    @Test
    void addingAPastTheTailKeepsEveryElementInOrder() {
        Appended<Integer> a = Appended.of();
        List<Integer> expected = new ArrayList<>();
        for (int batch = 0; batch < 50; batch++) {
            List<Integer> more = upTo(expected.size(), expected.size() + batch);
            a = a.plus(more);
            expected.addAll(more);
            assertEquals(expected, a);
        }
    }

    @Test
    void aListAddedToAfterwardsStillHoldsOnlyItsOwn() {
        Appended<Integer> base = Appended.<Integer>of().plus(upTo(0, 200));
        Appended<Integer> one = base.plus(upTo(200, 300));
        Appended<Integer> other = base.plus(upTo(1000, 1100));
        assertEquals(upTo(0, 200), base, "what it was made from is as it was");
        List<Integer> expected = new ArrayList<>(upTo(0, 200));
        expected.addAll(upTo(1000, 1100));
        assertEquals(expected, other, "the second fork copied rather than reading the first's");
        expected = new ArrayList<>(upTo(0, 300));
        assertEquals(expected, one);
    }

    @Test
    void replacingAnElementChangesNoOtherList() {
        Appended<Integer> a = Appended.<Integer>of().plus(upTo(0, 100));
        Appended<Integer> newest = a.with(99, -1);
        Appended<Integer> oldest = a.with(0, -2);
        assertEquals(upTo(0, 100), a);
        assertEquals(-1, newest.get(99));
        assertEquals(-2, oldest.get(0));
        assertEquals(98, newest.get(98));
        assertEquals(100, newest.plus(upTo(100, 200)).get(100));
        assertEquals(-1, newest.plus(upTo(100, 200)).get(99), "a replaced element pushed into the store stays replaced");
    }

    @Test
    void droppingTheOldestKeepsTheRestAndLetsGoOfTheStore() {
        Appended<Integer> a = Appended.<Integer>of().plus(upTo(0, 5000));
        Appended<Integer> later = a.dropFirst(4000);
        assertEquals(upTo(4000, 5000), later);
        assertEquals(upTo(0, 5000), a);
        Appended<Integer> more = later.plus(upTo(5000, 5100));
        assertEquals(upTo(4000, 5100), more);
        assertEquals(upTo(4990, 5100), more.dropFirst(990));
        assertEquals(List.of(), more.dropFirst(more.size()));
    }

    @Test
    void anyListIsTakenAsItIsOrCopied() {
        Appended<Integer> a = Appended.<Integer>of().plus(upTo(0, 3));
        assertSame(a, Appended.of(a));
        assertEquals(upTo(0, 3), Appended.of(upTo(0, 3)));
    }
}
