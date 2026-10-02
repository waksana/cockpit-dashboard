package io.github.waksana.cockpitdashboard;

import org.json.JSONArray;
import org.json.JSONObject;

/** An explicit decisions array supersedes legacy singular fields, including when empty. */
final class PendingDecision {
    private PendingDecision() {}

    static JSONObject ask(JSONObject meta) {
        if (meta == null || requiresCockpit(meta)) return null;
        JSONArray decisions = meta.optJSONArray("decisions");
        JSONObject ask = decisions == null ? meta.optJSONObject("ask")
                : decisions.length() == 1 ? decisions.optJSONObject(0).optJSONObject("request") : null;
        return validAsk(ask) ? ask : null;
    }

    static boolean requiresCockpit(JSONObject meta) {
        if (meta == null) return false;
        if (meta.has("decisions") && !meta.isNull("decisions")) {
            JSONArray decisions = meta.optJSONArray("decisions");
            if (decisions == null || decisions.length() > 1) return true;
            if (decisions.length() == 0) return false;
            JSONObject decision = decisions.optJSONObject(0);
            return decision == null || !"ask".equals(decision.optString("kind"))
                    || !validAsk(decision.optJSONObject("request"));
        }
        return meta.optJSONObject("planRequest") != null || meta.optJSONObject("elicitation") != null
                || (meta.has("ask") && !meta.isNull("ask") && !validAsk(meta.optJSONObject("ask")));
    }

    private static boolean validAsk(JSONObject ask) {
        return ask != null && ask.opt("requestId") instanceof String
                && !ask.optString("requestId").isEmpty() && ask.opt("question") instanceof String;
    }
}
