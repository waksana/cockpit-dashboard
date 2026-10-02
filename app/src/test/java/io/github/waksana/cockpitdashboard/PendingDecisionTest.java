package io.github.waksana.cockpitdashboard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
public class PendingDecisionTest {
    private JSONObject ask() throws Exception {
        return new JSONObject().put("question", "Choose?").put("requestId", "host-callback-id")
                .put("choices", new JSONArray().put("Yes"));
    }

    @Test public void explicitEmptyDecisionsNeverReviveLegacyAskOrPlan() throws Exception {
        JSONObject meta = new JSONObject().put("ask", ask()).put("planRequest", new JSONObject())
                .put("decisions", new JSONArray());
        assertNull(PendingDecision.ask(meta));
        assertFalse(PendingDecision.requiresCockpit(meta));
    }

    @Test public void currentDecisionRequestWinsOverLegacyIdentity() throws Exception {
        JSONObject request = ask();
        JSONObject meta = new JSONObject().put("ask", ask().put("requestId", "stale"))
                .put("decisions", new JSONArray().put(new JSONObject().put("kind", "ask").put("request", request)));
        assertSame(request, PendingDecision.ask(meta));
        assertFalse(PendingDecision.requiresCockpit(meta));
        meta.remove("decisions");
        assertEquals("stale", PendingDecision.ask(meta).getString("requestId"));
    }

    @Test public void unsupportedMultipleAndMalformedDecisionsCannotBeAnswered() throws Exception {
        JSONObject decision = new JSONObject().put("kind", "ask").put("request", ask());
        for (Object decisions : new Object[]{
                new JSONArray().put(decision).put(decision),
                new JSONArray().put(new JSONObject().put("kind", "plan").put("request", new JSONObject())),
                new JSONArray().put(new JSONObject().put("kind", "ask")),
                new JSONArray().put("invalid"), "invalid"}) {
            JSONObject meta = new JSONObject().put("decisions", decisions).put("ask", ask());
            assertTrue(PendingDecision.requiresCockpit(meta));
            assertNull(PendingDecision.ask(meta));
        }
    }
}
