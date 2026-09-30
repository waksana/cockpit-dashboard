package io.github.waksana.cockpitdashboard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatProjectionTest {
    private JSONObject event(String id, String type, String message, String value) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("data", new JSONObject()
                .put("messageId", message).put(type.endsWith("_delta") ? "deltaContent" : "content", value));
    }

    @Test public void overlapsDeduplicateAndFinalReplacesPartial() throws Exception {
        ChatProjection projection = new ChatProjection();
        JSONObject delta = event("d1", "assistant.message_delta", "m1", "partial");
        projection.accept(new JSONArray().put(delta), false);
        projection.accept(new JSONArray().put(delta).put(event("d2", "assistant.message_delta", "m1", " text")), false);
        assertEquals("partial text", projection.items().get(0).text);
        projection.accept(new JSONArray().put(event("f1", "assistant.message", "m1", "complete")), false);
        projection.accept(new JSONArray().put(event("d3", "assistant.message_delta", "m1", "late")), false);
        assertEquals(1, projection.items().size());
        assertEquals("complete", projection.items().get(0).text);
        assertTrue(projection.items().get(0).complete);
    }

    @Test public void olderPagesPrependInNativeOrderAndChildMessagesStayHidden() throws Exception {
        ChatProjection projection = new ChatProjection();
        projection.accept(new JSONArray().put(event("new", "assistant.message", "new", "new")), false);
        projection.accept(new JSONArray()
                .put(event("old1", "user.message", "old1", "old1"))
                .put(event("child", "assistant.message", "child", "hidden").put("parentToolCallId", "tool"))
                .put(new JSONObject().put("id", "nested").put("type", "assistant.message")
                        .put("data", new JSONObject().put("content", "private").put("parentToolCallId", "tool")))
                .put(event("old2", "assistant.message", "old2", "old2")), true);
        assertEquals(3, projection.items().size());
        assertEquals("old1", projection.items().get(0).text);
        assertEquals("old2", projection.items().get(1).text);
        assertEquals("new", projection.items().get(2).text);
    }

    @Test public void nativeQuestionsAndAnswersRemainConversationHistory() throws Exception {
        ChatProjection projection = new ChatProjection();
        projection.accept(new JSONArray()
                .put(new JSONObject().put("id", "q").put("type", "user_input.requested")
                        .put("data", new JSONObject().put("requestId", "r").put("question", "Which?")))
                .put(new JSONObject().put("id", "a").put("type", "user_input.completed")
                        .put("data", new JSONObject().put("requestId", "r").put("answer", "First"))), false);
        assertEquals(2, projection.items().size());
        assertEquals("Which?", projection.items().get(0).text);
        assertEquals("First", projection.items().get(1).text);
        assertEquals("你", projection.items().get(1).speaker);
    }
}
