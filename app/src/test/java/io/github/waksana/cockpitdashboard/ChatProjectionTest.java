package io.github.waksana.cockpitdashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ChatProjectionTest {
    // Synthetic native journal shapes from cockpit@627270a248f634e977dd96fd8ee2d8c70d1288fc:
    // packages/protocol/src/chat.ts (askAnswerOf), apps/web/src/net/{displayEvent,historyFold}.ts.
    private JSONObject event(String id, String type, String message, String value) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("data", new JSONObject()
                .put("messageId", message).put(type.endsWith("_delta") ? "deltaContent" : "content", value));
    }

    private JSONObject nativeEvent(String id, String type, JSONObject data) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("data", data);
    }

    private JSONObject arguments() throws Exception {
        return new JSONObject().put("question", "Which route?")
                .put("choices", new JSONArray().put("First").put("Second")).put("allowFreeform", true);
    }

    private JSONObject start(String id, String toolId) throws Exception {
        return nativeEvent(id, "tool.execution_start", new JSONObject()
                .put("toolCallId", toolId).put("toolName", "ask_user").put("arguments", arguments()));
    }

    private JSONObject completion(String id, String toolId, String text) throws Exception {
        return nativeEvent(id, "tool.execution_complete", new JSONObject().put("toolCallId", toolId)
                .put("success", true).put("result", new JSONObject().put("content", text)));
    }

    private JSONObject outcome(JSONObject completion, Object outcome) throws Exception {
        completion.getJSONObject("data").put("toolTelemetry",
                new JSONObject().put("properties", new JSONObject().put("outcome", outcome).put("was_freeform", false)));
        return completion;
    }

    private JSONArray slice(JSONArray array, int from, int to) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = from; i < to; i++) result.put(array.get(i));
        return result;
    }

    private String snapshot(ChatProjection projection) throws Exception {
        JSONArray result = new JSONArray();
        for (ChatProjection.Message message : projection.items()) {
            result.put(new JSONObject().put("id", message.id).put("speaker", message.speaker)
                    .put("text", message.text).put("complete", message.complete)
                    .put("question", message.question == null ? JSONObject.NULL : message.question)
                    .put("choices", new JSONArray(message.choices))
                    .put("allowFreeform", message.allowFreeform == null ? JSONObject.NULL : message.allowFreeform)
                    .put("askReply", message.askReply)
                    .put("askStatus", message.askStatus == null ? JSONObject.NULL : message.askStatus.name()));
        }
        return result.toString();
    }

    private ChatProjection project(JSONObject... events) throws Exception {
        ChatProjection projection = new ChatProjection();
        JSONArray values = new JSONArray();
        for (JSONObject event : events) values.put(event);
        projection.accept(values, false);
        return projection;
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

    @Test public void durableSelectedAnswerIncludesReadOnlyHistoricalMetadata() throws Exception {
        ChatProjection projection = project(start("q", "tool"),
                outcome(completion("a", "tool", " \nUser selected: First \n"), "answered"));
        List<ChatProjection.Message> items = projection.items();
        assertEquals(2, items.size());
        assertEquals("Which route?", items.get(0).question);
        assertEquals("Which route?", items.get(0).text);
        assertEquals("ask:tool:tool", items.get(0).id);
        assertEquals("First", items.get(0).choices.get(0));
        assertEquals("Second", items.get(0).choices.get(1));
        assertEquals(Boolean.TRUE, items.get(0).allowFreeform);
        assertEquals(ChatProjection.AskStatus.ANSWERED, items.get(0).askStatus);
        assertTrue(items.get(0).complete);
        assertEquals("First", items.get(1).text);
        assertEquals("你", items.get(1).speaker);
        assertNull(items.get(1).askStatus);
        assertEquals("Which route?", items.get(1).question);
        assertTrue(items.get(1).askReply);
    }

    @Test public void allInvocationShapesRepairEveryPageBoundaryAndRestartReplay() throws Exception {
        JSONObject embedded = nativeEvent("embedded", "assistant.message", new JSONObject()
                .put("toolRequests", new JSONArray()
                        .put(new JSONObject().put("toolCallId", "tool").put("name", "ask_user").put("arguments", arguments()))
                        .put(new JSONObject().put("toolCallId", "bash").put("name", "bash")
                                .put("arguments", new JSONObject().put("command", "unrelated")))));
        JSONObject requested = nativeEvent("requested", "user_input.requested",
                arguments().put("toolCallId", "tool").put("requestId", "independent-callback-id"));
        for (JSONObject invocation : new JSONObject[]{start("start", "tool"), embedded, requested}) {
            JSONArray journal = new JSONArray()
                    .put(event("u", "user.message", "u", "Choose a route"))
                    .put(event("d1", "assistant.message_delta", "m", "partial"))
                    .put(invocation)
                    .put(outcome(completion("answer", "tool", "User responded: custom\nroute"), "answered"))
                    .put(event("d2", "assistant.message_delta", "m", " extra"))
                    .put(event("final", "assistant.message", "m", "Final response"))
                    .put(event("late", "assistant.message_delta", "m", " ignored"))
                    .put(event("last", "user.message", "last", "Thanks"));
            ChatProjection uninterrupted = new ChatProjection();
            uninterrupted.accept(journal, false);
            String expected = snapshot(uninterrupted);
            for (int first = 0; first <= journal.length(); first++) {
                for (int second = first; second <= journal.length(); second++) {
                    ChatProjection paged = new ChatProjection();
                    paged.accept(slice(journal, second, journal.length()), false);
                    paged.accept(slice(journal, first, second), true);
                    paged.accept(slice(journal, 0, first), true);
                    assertEquals("older pages " + first + "/" + second, expected, snapshot(paged));
                    paged.accept(journal, true);
                    paged.accept(journal, false);
                    assertEquals("overlap", expected, snapshot(paged));

                    ChatProjection live = new ChatProjection();
                    live.accept(slice(journal, 0, first), false);
                    live.accept(slice(journal, first, second), false);
                    live.accept(slice(journal, second, journal.length()), false);
                    assertEquals("live pages " + first + "/" + second, expected, snapshot(live));
                    live.clear();
                    live.accept(new JSONArray(journal.toString()), false);
                    assertEquals("restart", expected, snapshot(live));
                }
            }
            assertEquals(5, uninterrupted.items().size());
            assertEquals("Choose a route", uninterrupted.items().get(0).text);
            assertEquals("Final response", uninterrupted.items().get(1).text);
            assertEquals("Which route?", uninterrupted.items().get(2).question);
            assertEquals("custom\nroute", uninterrupted.items().get(3).text);
        }
    }

    @Test public void resultOnlySuffixIsSilentUntilExactInvocationIsLoaded() throws Exception {
        JSONObject answer = completion("answer", "tool", "User selected: First");
        ChatProjection projection = project(answer, event("after", "assistant.message", "after", "After"));
        assertEquals(1, projection.items().size());
        projection.accept(new JSONArray().put(start("other", "different")), true);
        assertEquals(2, projection.items().size());
        assertEquals(ChatProjection.AskStatus.UNCONFIRMED, projection.items().get(0).askStatus);
        projection.accept(new JSONArray().put(start("q", "tool")), true);
        assertEquals(4, projection.items().size());
        assertEquals("Which route?", projection.items().get(0).text);
        assertEquals("First", projection.items().get(2).text);
        assertEquals("After", projection.items().get(3).text);
    }

    @Test public void embeddedThenStartedThenRequestedMergeOneCardWithoutInventingChoices() throws Exception {
        JSONObject embedded = nativeEvent("embedded", "assistant.message", new JSONObject()
                .put("content", "Before asking")
                .put("toolRequests", new JSONArray().put(new JSONObject().put("toolCallId", "tool").put("name", "ask_user")
                        .put("arguments", new JSONObject().put("question", "Which route?")))));
        ChatProjection projection = project(embedded);
        assertTrue(projection.items().get(1).choices.isEmpty());
        assertNull(projection.items().get(1).allowFreeform);
        projection.accept(new JSONArray().put(start("start", "tool"))
                .put(nativeEvent("requested", "user_input.requested",
                        new JSONObject().put("toolCallId", "tool").put("question", "Which route?")))
                .put(completion("done", "tool", "User selected: First")), false);
        assertEquals(3, projection.items().size());
        assertEquals(2, projection.items().get(1).choices.size());
        assertEquals(ChatProjection.AskStatus.ANSWERED, projection.items().get(1).askStatus);
    }

    @Test public void ephemeralCompletionAndCallbackIdsNeverFabricateDurableAnswers() throws Exception {
        JSONObject requested = nativeEvent("q", "user_input.requested",
                new JSONObject().put("requestId", "tool").put("question", "Only a callback question"));
        JSONObject ephemeral = nativeEvent("ephemeral", "user_input.completed",
                new JSONObject().put("requestId", "tool").put("toolCallId", "tool").put("answer", "fabricated"));
        ChatProjection projection = project(requested, ephemeral,
                completion("done", "tool", "User responded: cannot join by requestId"));
        assertEquals(1, projection.items().size());
        assertEquals(ChatProjection.AskStatus.UNCONFIRMED, projection.items().get(0).askStatus);
        assertTrue(projection.items().get(0).choices.isEmpty());
        assertTrue(project(ephemeral).items().isEmpty());

        ChatProjection nativeAsk = project(start("start", "tool"), ephemeral);
        assertEquals(1, nativeAsk.items().size());
        assertEquals(ChatProjection.AskStatus.UNCONFIRMED, nativeAsk.items().get(0).askStatus);
    }

    @Test public void unrelatedToolsReasoningAndUnknownResultsNeverBecomeConversation() throws Exception {
        JSONObject bash = start("bash", "tool");
        bash.getJSONObject("data").put("toolName", "bash");
        ChatProjection projection = project(bash,
                completion("done", "tool", "User selected: should stay hidden"),
                nativeEvent("thinking", "assistant.reasoning", new JSONObject().put("content", "internal reasoning")),
                nativeEvent("progress", "tool.execution_progress", new JSONObject().put("toolCallId", "tool").put("content", "log")));
        assertTrue(projection.items().isEmpty());
    }

    @Test public void failedCancelledDismissedExpiredAndUnknownStayTruthful() throws Exception {
        String[] outcomes = {"failed", "cancelled", "canceled", "dismissed", "expired", "unrecognized", "answered"};
        ChatProjection.AskStatus[] statuses = {ChatProjection.AskStatus.FAILED, ChatProjection.AskStatus.CANCELLED,
                ChatProjection.AskStatus.CANCELLED, ChatProjection.AskStatus.DISMISSED, ChatProjection.AskStatus.EXPIRED,
                ChatProjection.AskStatus.UNKNOWN, ChatProjection.AskStatus.UNKNOWN};
        for (int i = 0; i < outcomes.length; i++) {
            String text = outcomes[i].equals("answered") ? "Completed successfully" : "User selected: not an answer";
            ChatProjection projection = project(start("q", "tool"), outcome(completion("a", "tool", text), outcomes[i]));
            assertEquals(outcomes[i], 1, projection.items().size());
            assertEquals(outcomes[i], statuses[i], projection.items().get(0).askStatus);
        }
        ChatProjection successOnly = project(start("q", "tool"), completion("a", "tool", "Success"));
        assertEquals(ChatProjection.AskStatus.UNKNOWN, successOnly.items().get(0).askStatus);
    }

    @Test public void everyNativeFailureGateRejectsAnOtherwiseRecognizedAnswer() throws Exception {
        String[] gates = {"data.success", "data.error", "data.dismissed", "result.success", "result.error",
                "result.dismissed", "result.isError"};
        for (String gate : gates) {
            JSONObject done = completion("a", "tool", "User selected: First");
            String[] parts = gate.split("\\.");
            JSONObject object = done.getJSONObject("data");
            if (parts[0].equals("result")) object = object.getJSONObject("result");
            object.put(parts[1], parts[1].equals("success") ? false : parts[1].equals("error") ? "" : true);
            ChatProjection projection = project(start("q", "tool"), done);
            assertEquals(gate, 1, projection.items().size());
            assertEquals(gate, gate.endsWith("dismissed") ? ChatProjection.AskStatus.DISMISSED : ChatProjection.AskStatus.FAILED,
                    projection.items().get(0).askStatus);
        }
    }

    @Test public void durableAnswerParsingMatchesPinnedWebRatherThanGenericSuccess() throws Exception {
        Object[] rejected = {JSONObject.NULL, "User selected: First", new JSONArray().put("User selected: First"),
                new JSONObject().put("content", new JSONArray().put("User selected: First")),
                new JSONObject().put("content", "user selected: First"),
                new JSONObject().put("content", "Tool output: User selected: First"),
                new JSONObject().put("content", "User selected: \n\t"),
                new JSONObject().put("content", "User selected: ").put("detailedContent", "User selected: not a fallback")};
        for (Object value : rejected) {
            JSONObject done = completion("a", "tool", "");
            done.getJSONObject("data").put("result", value);
            ChatProjection projection = project(start("q", "tool"), done);
            assertEquals(value.toString(), 1, projection.items().size());
            assertEquals(ChatProjection.AskStatus.UNKNOWN, projection.items().get(0).askStatus);
        }
        JSONObject fallback = completion("fallback", "tool", "not an answer");
        fallback.getJSONObject("data").remove("success");
        fallback.getJSONObject("data").getJSONObject("result").put("detailedContent", "\nUser responded: first\nsecond  ");
        ChatProjection projection = project(start("q", "tool"), fallback);
        assertEquals("first\nsecond", projection.items().get(1).text);

        JSONObject nullErrors = outcome(completion("nulls", "tool", "User selected: First"), 42);
        nullErrors.getJSONObject("data").put("error", JSONObject.NULL).put("success", JSONObject.NULL);
        nullErrors.getJSONObject("data").getJSONObject("result").put("error", JSONObject.NULL);
        assertEquals("First", project(start("q", "tool"), nullErrors).items().get(1).text);

        JSONObject unicode = completion("unicode", "tool", "\ufeff\u00a0User responded:\u2003First\u3000");
        unicode.getJSONObject("data").getJSONObject("result").put("detailedContent", "User selected: ignored fallback");
        assertEquals("First", project(start("q", "tool"), unicode).items().get(1).text);
    }

    @Test public void missingQuestionAndMalformedChoicesNeverInventContent() throws Exception {
        JSONObject question = start("q", "tool");
        question.getJSONObject("data").getJSONObject("arguments")
                .put("choices", new JSONArray().put("First").put(3).put(JSONObject.NULL).put(new JSONObject()))
                .put("allowFreeform", false);
        ChatProjection projection = project(question);
        assertEquals(1, projection.items().get(0).choices.size());
        assertEquals(Boolean.FALSE, projection.items().get(0).allowFreeform);
        question.getJSONObject("data").put("arguments", new JSONObject());
        projection = project(question, completion("done", "tool", "User selected: First"));
        assertEquals(1, projection.items().size());
        assertEquals("你", projection.items().get(0).speaker);
        assertNull(projection.items().get(0).question);
        assertTrue(project(question).items().isEmpty());
    }

    @Test public void allChildOwnerLocationsStayHiddenDespiteReusedRootToolAndMessageIds() throws Exception {
        for (String marker : new String[]{"agentId", "parentToolCallId"}) {
            for (boolean envelope : new boolean[]{false, true}) {
                JSONObject childStart = start("child-start", "same");
                JSONObject childAnswer = completion("child-answer", "same", "User responded: private child");
                JSONObject childMessage = event("child-message", "assistant.message", "same", "private assistant");
                for (JSONObject child : new JSONObject[]{childStart, childAnswer, childMessage})
                    (envelope ? child : child.getJSONObject("data")).put(marker, "child-owner");
                JSONArray journal = new JSONArray().put(childStart).put(start("root-start", "same"))
                        .put(childAnswer).put(completion("root-answer", "same", "User selected: First"))
                        .put(childMessage).put(event("root-message", "assistant.message", "same", "root assistant"));
                ChatProjection expected = new ChatProjection();
                expected.accept(journal, false);
                assertEquals(3, expected.items().size());
                assertEquals("First", expected.items().get(1).text);
                assertEquals("root assistant", expected.items().get(2).text);
                for (int split = 0; split <= journal.length(); split++) {
                    ChatProjection paged = new ChatProjection();
                    paged.accept(slice(journal, split, journal.length()), false);
                    paged.accept(slice(journal, 0, split), true);
                    assertEquals(snapshot(expected), snapshot(paged));
                }
                assertTrue(project(childStart, completion("root-only", "same", "User selected: not child answer")).items().isEmpty());
                assertEquals(1, project(start("root", "same"), childAnswer).items().size());
            }
        }
    }

    @Test public void parentIdIsChronologicalReferenceNotAnOwner() throws Exception {
        JSONObject question = start("q", "tool").put("parentId", "previous-event");
        question.getJSONObject("data").put("parentId", "another-event");
        JSONObject done = completion("a", "tool", "User selected: First").put("parentId", "q");
        done.getJSONObject("data").put("parentId", "q");
        assertEquals(2, project(question, done).items().size());
    }

    @Test public void overlappingOlderPagesKeepNativeOrderingAndDoNotDuplicateDeltas() throws Exception {
        JSONObject one = event("one", "assistant.message_delta", "m", "one");
        JSONObject two = event("two", "assistant.message_delta", "m", "two");
        JSONObject three = event("three", "assistant.message_delta", "m", "three");
        ChatProjection projection = project(two, three);
        projection.accept(new JSONArray().put(one).put(two), true);
        assertEquals("onetwothree", projection.items().get(0).text);
        projection.accept(new JSONArray().put(three), false);
        assertEquals("onetwothree", projection.items().get(0).text);
        projection.accept(new JSONArray().put(event("final", "assistant.message", "m", "Final")), false);
        projection.accept(new JSONArray().put(event("old", "assistant.message_delta", "m", "zero")), true);
        assertEquals("Final", projection.items().get(0).text);
    }

    @Test public void attachmentPlaceholderAndSessionErrorsRemainButEmptyToolMessagesDoNot() throws Exception {
        JSONObject user = event("u", "user.message", "u", "");
        user.getJSONObject("data").put("attachments", new JSONArray().put(new JSONObject().put("path", "/private")));
        ChatProjection projection = project(user,
                nativeEvent("error", "session.error", new JSONObject().put("message", "Connection failed")),
                nativeEvent("empty", "assistant.message", new JSONObject().put("toolRequests", new JSONArray())));
        assertEquals(2, projection.items().size());
        assertTrue(projection.items().get(0).text.contains("[附件 1 个，请在 Cockpit 查看]"));
        assertFalse(projection.items().get(0).text.contains("/private"));
        assertEquals("错误", projection.items().get(1).speaker);
        assertEquals("Connection failed", projection.items().get(1).text);
    }

    @Test public void acceptedRecordsDoNotRetainRawJsonOrArbitraryToolPayloads() throws Exception {
        JSONObject done = completion("result", "unknown", "very large unrelated tool output");
        done.getJSONObject("data").put("extra", new JSONObject().put("secret", "not retained"));
        done.getJSONObject("data").getJSONObject("result").put("detailedContent", "also unrelated");
        ChatProjection projection = project(done);
        Field records = ChatProjection.class.getDeclaredField("records");
        records.setAccessible(true);
        List<?> values = (List<?>) records.get(projection);
        assertEquals(1, values.size());
        for (Field field : values.get(0).getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(values.get(0));
            assertFalse(value instanceof JSONObject);
            assertFalse(value instanceof JSONArray);
            assertFalse(String.valueOf(value).contains("unrelated"));
            assertFalse(String.valueOf(value).contains("secret"));
        }
        JSONObject question = start("q", "tool");
        projection.accept(new JSONArray().put(question), false);
        question.getJSONObject("data").getJSONObject("arguments").put("question", "mutated")
                .getJSONArray("choices").put("mutated choice");
        projection.accept(new JSONArray().put(event("u", "user.message", "u", "next")), false);
        assertEquals("Which route?", projection.items().get(0).question);
        assertEquals(2, projection.items().get(0).choices.size());
    }

    @Test public void messageLimitFailsAtomicallyWithoutEvictingKnownIdentities() throws Exception {
        ChatProjection projection = new ChatProjection();
        JSONArray window = new JSONArray();
        for (int i = 0; i < 500; i++) window.put(event("event-" + i, "user.message", "message-" + i, "body-" + i));
        projection.accept(window, false);
        String expected = snapshot(projection);
        for (boolean older : new boolean[]{false, true}) {
            try {
                projection.accept(new JSONArray().put(event("overflow", "user.message", "overflow", "overflow")), older);
                fail("Expected explicit exhaustion");
            } catch (JSONException error) {
                assertTrue(error.getMessage().contains("上限"));
            }
            assertEquals(expected, snapshot(projection));
            projection.accept(window, older);
            assertEquals(expected, snapshot(projection));
        }
        projection.clear();
        projection.accept(new JSONArray().put(event("overflow", "user.message", "overflow", "overflow")), false);
        assertEquals(1, projection.items().size());
    }

    @Test public void eventLimitFailsAtomicallyEvenForHiddenEvents() throws Exception {
        ChatProjection projection = new ChatProjection();
        JSONArray window = new JSONArray();
        for (int i = 0; i < 20000; i++)
            window.put(nativeEvent("hidden-" + i, "tool.execution_progress", new JSONObject()));
        projection.accept(window, false);
        try {
            projection.accept(new JSONArray().put(start("overflow", "tool")), false);
            fail("Expected explicit exhaustion");
        } catch (JSONException error) {
            assertTrue(error.getMessage().contains("上限"));
        }
        assertTrue(projection.items().isEmpty());
        projection.accept(window, true);
        projection.clear();
        projection.accept(new JSONArray().put(start("overflow", "tool")), false);
        assertEquals(ChatProjection.AskStatus.UNCONFIRMED, projection.items().get(0).askStatus);
    }
}
