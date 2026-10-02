package io.github.waksana.cockpitdashboard;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.waksana.cockpitdashboard.RemoteState.Action.*;
import static io.github.waksana.cockpitdashboard.RemoteState.Phase.*;

public class RemoteStateTest {
    @Test public void pressRepeatReleaseNeverSends() {
        for (int key : new int[]{23, 66, 160}) {
            RemoteState state = new RemoteState();
            assertEquals(START, state.down(key, 0));
            assertEquals(NONE, state.down(key, 1));
            assertEquals(NONE, state.down(key, 2));
            assertEquals(NONE, state.up(key == 66 ? 23 : 66));
            assertEquals(STOP, state.up(key));
            assertEquals(TRANSCRIBING, state.phase);
            assertEquals(NONE, state.up(key));
            assertTrue(state.finishSegment("hello"));
            assertEquals(DRAFT, state.phase);
            assertEquals(SEND, state.down(22, 0));
            assertEquals(NONE, state.down(22, 1));
            assertEquals(NONE, state.down(22, 0));
            assertEquals(NONE, state.down(21, 0));
        }
    }

    @Test public void emptyTranscriptionCannotSendAndLeftNeverSends() {
        RemoteState state = new RemoteState();
        state.transcript(" \n ");
        assertEquals(IDLE, state.phase);
        assertEquals(NONE, state.down(22, 0));
        state.transcript("draft");
        assertEquals(DISCARD, state.down(21, 0));
        state.clear();
        assertEquals(IDLE, state.phase);
        assertEquals("", state.draft);
        assertEquals(NONE, state.up(23));
    }

    @Test public void interruptionsReleaseHeldKeyButPreserveCompletedDraft() {
        RemoteState state = new RemoteState();
        state.down(23, 0);
        state.interrupt();
        assertEquals(NONE, state.up(23));
        assertEquals(START, state.down(23, 0));
        state.up(23);
        state.interrupt();
        assertEquals(IDLE, state.phase);
        state.transcript("preserve");
        state.interrupt();
        assertEquals(DRAFT, state.phase);
        assertEquals("preserve", state.draft);
    }

    @Test public void unknownCannotBeResentOrRecordedOver() {
        RemoteState state = new RemoteState();
        state.transcript("uncertain");
        state.phase = UNKNOWN;
        assertEquals(NONE, state.down(22, 0));
        assertEquals(NONE, state.down(23, 0));
        assertEquals(DISCARD, state.down(21, 0));
    }

    @Test public void recreationKeepsDraftAndConvertsInFlightMarkerToUnknown() {
        RemoteState restored = new RemoteState();
        restored.restore("retained", false);
        assertEquals(DRAFT, restored.phase);
        assertEquals("retained", restored.draft);
        restored.restore("possibly sent", true);
        assertEquals(UNKNOWN, restored.phase);
        assertEquals(NONE, restored.down(22, 0));
        assertEquals("possibly sent", restored.draft);
    }

    @Test public void repeatedSegmentsAppendExactlyOnceToEditedDraftWithoutSending() {
        RemoteState state = new RemoteState();
        state.transcript("manually edited");
        for (String text : new String[]{"first", "second"}) {
            assertEquals(START, state.down(23, 0));
            assertEquals(NONE, state.down(22, 0));
            assertEquals(STOP, state.up(23));
            assertTrue(state.finishSegment(text));
            assertFalse(state.finishSegment(text));
            assertEquals(DRAFT, state.phase);
            assertEquals(NONE, state.up(23));
        }
        assertEquals("manually edited\nfirst\nsecond", state.draft);
    }

    @Test public void emptyOrInterruptedSegmentsRetainPriorTextAndIgnoreLateResults() {
        RemoteState state = new RemoteState();
        state.transcript("keep exact text  ");
        state.down(23, 0);
        state.up(23);
        assertTrue(state.finishSegment(" \n"));
        assertEquals("keep exact text  ", state.draft);
        state.down(23, 0);
        assertEquals(DISCARD, state.down(21, 0));
        state.interrupt();
        assertEquals(NONE, state.up(23));
        assertFalse(state.finishSegment("late"));
        assertEquals("keep exact text  ", state.draft);
        state.down(23, 0);
        state.up(23);
        state.interrupt();
        assertEquals("keep exact text  ", state.draft);
        assertEquals(DRAFT, state.phase);
    }
}
