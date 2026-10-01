package io.github.waksana.cockpitdashboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class DevicePollTest {
    @Test public void firstPollWaitsAndResponsesStartAFullInterval() {
        DevicePoll poll = new DevicePoll(1000, 600, 5);
        assertEquals(5000, poll.delay(1000));
        assertEquals(1, poll.delay(5999));
        assertEquals(0, poll.delay(6000));
        poll.pending(17000);
        assertEquals(5000, poll.delay(17000));
        assertEquals(1, poll.delay(21999));
        assertEquals(0, poll.delay(22000));
    }

    @Test public void slowDownPermanentlyAddsFiveSeconds() {
        DevicePoll poll = new DevicePoll(0, 600, 5);
        poll.slowDown(6000);
        assertEquals(10000, poll.delay(6000));
        poll.pending(17000);
        assertEquals(10000, poll.delay(17000));
        poll.slowDown(28000);
        assertEquals(15000, poll.delay(28000));
        poll.pending(44000);
        assertEquals(15000, poll.delay(44000));
    }

    @Test public void timeoutDoublesIntervalWithoutBurstsOrResettingSlowDown() {
        DevicePoll poll = new DevicePoll(0, 600, 5);
        poll.timeout(10000);
        assertEquals(10000, poll.delay(10000));
        poll.timeout(25000);
        assertEquals(20000, poll.delay(25000));
        poll.slowDown(50000);
        assertEquals(25000, poll.delay(50000));
        poll.pending(80000);
        assertEquals(25000, poll.delay(80000));
    }

    @Test public void expiryWinsOverDuePollAndCannotBeExtendedByResponses() {
        DevicePoll poll = new DevicePoll(1000, 10, 5);
        assertEquals(10, poll.remainingSeconds(1000));
        assertEquals(1, poll.remainingSeconds(10999));
        assertFalse(poll.expired(10999));
        assertTrue(poll.expired(11000));
        assertEquals(Long.MAX_VALUE, poll.delay(11000));
        poll.pending(11000);
        poll.timeout(11000);
        poll.slowDown(11000);
        assertEquals(Long.MAX_VALUE, poll.delay(11001));
        assertEquals(0, poll.remainingSeconds(11001));
    }

    @Test public void stoppingIsPermanent() {
        DevicePoll poll = new DevicePoll(0, 600, 5);
        poll.stop();
        poll.pending(10000);
        poll.slowDown(10000);
        poll.timeout(10000);
        assertTrue(poll.expired(10000));
        assertEquals(Long.MAX_VALUE, poll.delay(10000));
        assertEquals(0, poll.remainingSeconds(10000));
    }

    @Test public void largeDurationsAndBackoffSaturateWithoutOverflow() {
        DevicePoll poll = new DevicePoll(1000, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE - 1000, poll.delay(1000));
        for (int i = 0; i < 100; i++) {
            poll.timeout(1000);
            poll.slowDown(1000);
        }
        assertEquals(Long.MAX_VALUE - 1000, poll.delay(1000));
        assertTrue(poll.remainingSeconds(1000) > 0);
        assertTrue(poll.expired(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, poll.delay(Long.MAX_VALUE));
        DevicePoll nearEnd = new DevicePoll(Long.MAX_VALUE - 1, 1, 1);
        assertEquals(1, nearEnd.delay(Long.MAX_VALUE - 1));
    }

    @Test public void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DevicePoll(-1, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> new DevicePoll(0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new DevicePoll(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new DevicePoll(0, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> new DevicePoll(0, 10, -1));
    }
}
