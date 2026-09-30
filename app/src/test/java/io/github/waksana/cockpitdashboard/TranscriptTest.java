package io.github.waksana.cockpitdashboard;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TranscriptTest {
    @Test public void initialCommitAllowsMissingOrNullPreviousIdButNotOtherTypes() throws Exception {
        for (boolean omitted : new boolean[]{true, false}) {
            Transcript transcript = new Transcript();
            JSONObject event = commit("root", null);
            if (omitted) event.remove("previous_item_id");
            transcript.committed(event);
            transcript.text(text("root", "first", true), true);
            transcript.barrier();
            assertEquals("first", transcript.result());
        }
        for (Object invalid : new Object[]{42, true, new JSONObject(), ""}) {
            Transcript transcript = new Transcript();
            assertThrows(JSONException.class, () -> transcript.committed(
                    commit("root", null).put("previous_item_id", invalid)));
        }
    }

    @Test public void previousIdsOrderFinalsAndCompletionRequiresBarrier() throws Exception {
        Transcript transcript = new Transcript();
        transcript.text(text("two", "final two", true), true);
        transcript.committed(commit("two", "one"));
        transcript.text(text("one", "wrong partial", false), false);
        transcript.committed(commit("one", null));
        transcript.text(text("one", "final one", true), true);
        assertNull(transcript.result());
        transcript.barrier();
        assertEquals("final one\nfinal two", transcript.result());
    }

    @Test public void barrierWaitsForEveryCommittedFinalAndLateDeltaCannotCorruptFinal() throws Exception {
        Transcript transcript = new Transcript();
        transcript.committed(commit("one", null));
        transcript.committed(commit("two", "one"));
        transcript.text(text("two", "B", true), true);
        transcript.barrier();
        assertNull(transcript.result());
        transcript.text(text("one", "A", true), true);
        transcript.text(text("one", "ignored", false), false);
        assertEquals("A\nB", transcript.result());
    }

    @Test public void brokenChainForkAndCyclesAreRejected() throws Exception {
        for (String previous : new String[]{"missing", "two"}) {
            Transcript transcript = new Transcript();
            transcript.committed(commit("one", null));
            transcript.committed(commit("two", previous));
            assertThrows(JSONException.class, transcript::barrier);
        }
        Transcript fork = new Transcript();
        fork.committed(commit("a", null));
        fork.committed(commit("b", "a"));
        fork.committed(commit("c", "a"));
        assertThrows(JSONException.class, fork::barrier);
    }

    @Test public void emptyDuplicateAndUncommittedFinalsAreRejected() throws Exception {
        Transcript transcript = new Transcript();
        transcript.committed(commit("one", null));
        assertThrows(JSONException.class, () -> transcript.committed(commit("one", null)));
        transcript.text(text("one", " ", true), true);
        transcript.barrier();
        assertThrows(JSONException.class, transcript::result);
        assertThrows(JSONException.class, () -> transcript.committed(commit("two", "one")));
        Transcript orphan = new Transcript();
        orphan.text(text("orphan", "hello", true), true);
        assertThrows(JSONException.class, orphan::barrier);
    }

    @Test public void invalidIndexAndOversizedTextAreRejected() throws Exception {
        Transcript transcript = new Transcript();
        assertThrows(JSONException.class, () ->
                transcript.text(text("one", "hello", true).put("content_index", "0"), true));
        assertThrows(JSONException.class, () ->
                transcript.text(text("one", "x".repeat(Transcript.MAX_TEXT + 1), true), true));
    }

    static JSONObject commit(String item, String previous) throws Exception {
        return new JSONObject().put("type", "input_audio_buffer.committed")
                .put("item_id", item).put("previous_item_id", previous == null ? JSONObject.NULL : previous);
    }

    static JSONObject text(String item, String text, boolean complete) throws Exception {
        return new JSONObject().put("type", "conversation.item.input_audio_transcription."
                + (complete ? "completed" : "delta"))
                .put("item_id", item).put("content_index", 0)
                .put(complete ? "transcript" : "delta", text);
    }
}
