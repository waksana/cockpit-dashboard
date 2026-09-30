package io.github.waksana.cockpitdashboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ChatProjection {
    static final class Message {
        final String id;
        final String speaker;
        String text;
        boolean complete;
        Message(String id, String speaker, String text, boolean complete) {
            this.id = id; this.speaker = speaker; this.text = text; this.complete = complete;
        }
    }
    private final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();
    List<Message> items() { return new ArrayList<>(messages.values()); }
    void clear() { messages.clear(); seen.clear(); }

    void accept(JSONArray events, boolean older) throws JSONException {
        LinkedHashMap<String, Message> prefix = new LinkedHashMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            String eventId = event.getString("id");
            if (seen.contains(eventId)) continue;
            JSONObject data = event.getJSONObject("data");
            if (owned(event) || owned(data)) { seen.add(eventId); continue; }
            String type = event.getString("type");
            String speaker = type.equals("user.message") || type.equals("user_input.completed")
                    ? "你" : type.equals("session.error") ? "错误" : "Copilot";
            String id = speaker + ":" + data.optString("messageId", eventId);
            if ((type.equals("user_input.requested") && data.opt("question") instanceof String)
                    || (type.equals("user_input.completed") && data.opt("answer") instanceof String)) {
                id = type + ":" + data.optString("requestId", eventId);
                String text = data.getString(type.equals("user_input.requested") ? "question" : "answer");
                if (!messages.containsKey(id)) prefix.put(id, new Message(id, speaker, text, true));
            } else if (type.equals("assistant.message_delta") && !data.optString("messageId").isEmpty()) {
                Message message = messages.get(id);
                if (message == null) message = prefix.get(id);
                if (message == null) {
                    message = new Message(id, speaker, "", false);
                    prefix.put(id, message);
                }
                if (!message.complete) message.text += data.getString("deltaContent");
            } else if (type.equals("assistant.message") || type.equals("user.message") || type.equals("session.error")) {
                String text = data.optString("content", data.optString("message", ""));
                JSONArray attachments = data.optJSONArray("attachments");
                if (attachments != null && attachments.length() > 0) {
                    text += "\n[附件 " + attachments.length() + " 个，请在 Cockpit 查看]";
                }
                Message message = messages.get(id);
                if (message == null) message = prefix.get(id);
                if (message == null) prefix.put(id, new Message(id, speaker, text, true));
                else if (!older || !message.complete) { message.text = text; message.complete = true; }
            }
            seen.add(eventId);
        }
        if (older) {
            prefix.putAll(messages);
            messages.clear();
            messages.putAll(prefix);
        } else messages.putAll(prefix);
        // Bound the visible window; older history remains on the host.
        while (messages.size() > 500) {
            String key = older ? new ArrayList<>(messages.keySet()).get(messages.size() - 1)
                    : messages.keySet().iterator().next();
            messages.remove(key);
        }
        if (seen.size() > 20000) {
            throw new JSONException("本地历史窗口已达上限，请重新连接");
        }

    }

    private static boolean owned(JSONObject value) {
        return value.opt("parentToolCallId") instanceof String
                || value.opt("agentId") instanceof String;
    }
}
