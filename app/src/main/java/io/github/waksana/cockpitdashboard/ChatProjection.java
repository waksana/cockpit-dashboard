package io.github.waksana.cockpitdashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ChatProjection {
    enum AskStatus { UNCONFIRMED, ANSWERED, FAILED, CANCELLED, DISMISSED, EXPIRED, UNKNOWN }

    static final class Message {
        final String id;
        final String speaker;
        String text;
        boolean complete;
        // Historical metadata only. Never a live request ID or an actionable decision.
        String question;
        List<String> choices = Collections.emptyList();
        Boolean allowFreeform;
        AskStatus askStatus;
        boolean askReply;

        Message(String id, String speaker, String text, boolean complete) {
            this.id = id; this.speaker = speaker; this.text = text; this.complete = complete;
        }
    }

    private static final int MAX_EVENTS = 20000;
    private static final int MAX_MESSAGES = 500;
    private static final int MESSAGE = 0, DELTA = 1, ASK = 2, RESULT = 3;
    private static final Pattern ANSWER = Pattern.compile("^User (?:responded|selected):\\s*([\\s\\S]*)$");
    private static final String NATIVE_SPACE = "[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
    private static final Pattern NATIVE_TRIM = Pattern.compile("^" + NATIVE_SPACE + "+|" + NATIVE_SPACE + "+$");

    // Sanitized replay records repair pagination boundaries without retaining native tool payloads.
    private static final class Record {
        final int kind;
        final String id;
        String speaker;
        String text;
        List<String> choices;
        Boolean allowFreeform;
        AskStatus status;
        Record(int kind, String id) { this.kind = kind; this.id = id; }
    }

    private static final class Ask {
        String question;
        List<String> choices = Collections.emptyList();
        Boolean allowFreeform;
        Record result;
    }

    private List<Message> messages = new ArrayList<>();
    private List<Record> records = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    List<Message> items() { return new ArrayList<>(messages); }
    void clear() { messages.clear(); records.clear(); seen.clear(); }

    void accept(JSONArray events, boolean older) throws JSONException {
        Set<String> incomingIds = new HashSet<>();
        List<Record> incoming = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            String eventId = event.getString("id");
            if (seen.contains(eventId) || !incomingIds.add(eventId)) continue;
            if (seen.size() + incomingIds.size() > MAX_EVENTS) throw exhausted();
            JSONObject data = event.optJSONObject("data");
            if (data == null || owned(event) || owned(data)) continue;
            normalize(eventId, event.getString("type"), data, incoming);
            if (records.size() + incoming.size() > MAX_EVENTS) throw exhausted();
        }
        if (incomingIds.isEmpty()) return;
        List<Record> next = new ArrayList<>(records.size() + incoming.size());
        if (older) {
            next.addAll(incoming); next.addAll(records);
        } else {
            next.addAll(records); next.addAll(incoming);
        }
        List<Message> projected = project(next);
        // Exhaustion is explicit and atomic: never evict identities and then replay a duplicate.
        if (projected.size() > MAX_MESSAGES) throw exhausted();
        records = next;
        messages = projected;
        seen.addAll(incomingIds);
    }

    private static void normalize(String eventId, String type, JSONObject data, List<Record> target)
            throws JSONException {
        String toolId = string(data, "toolCallId");
        if (type.equals("tool.execution_start")) {
            if ("ask_user".equals(string(data, "toolName")) && present(toolId))
                target.add(askRecord("tool:" + toolId, data.optJSONObject("arguments")));
        } else if (type.equals("user_input.requested")) {
            // requestId belongs to a separate lifecycle, not the durable tool identity.
            target.add(askRecord(present(toolId) ? "tool:" + toolId : "event:" + eventId, data));
        } else if (type.equals("tool.execution_complete") && present(toolId)) {
            Record record = new Record(RESULT, "tool:" + toolId);
            record.text = answer(data);
            record.status = resultStatus(data, record.text);
            target.add(record);
        } else if (type.equals("assistant.message_delta")) {
            String messageId = string(data, "messageId");
            String text = string(data, "deltaContent");
            if (present(messageId) && text != null) {
                Record record = new Record(DELTA, "Copilot:" + messageId);
                record.speaker = "Copilot"; record.text = text;
                target.add(record);
            }
        } else if (type.equals("assistant.message") || type.equals("user.message") || type.equals("session.error")) {
            String speaker = type.equals("user.message") ? "你" : type.equals("session.error") ? "错误" : "Copilot";
            String messageId = string(data, "messageId");
            Record record = new Record(MESSAGE, speaker + ":" + (present(messageId) ? messageId : eventId));
            record.speaker = speaker;
            String text = string(data, "content");
            if (text == null) text = string(data, "message");
            record.text = text == null ? "" : text;
            JSONArray attachments = data.optJSONArray("attachments");
            if (attachments != null && attachments.length() > 0)
                record.text += "\n[附件 " + attachments.length() + " 个，请在 Cockpit 查看]";
            target.add(record);
            JSONArray requests = type.equals("assistant.message") ? data.optJSONArray("toolRequests") : null;
            if (requests != null) for (int i = 0; i < requests.length(); i++) {
                JSONObject request = requests.optJSONObject(i);
                if (request == null || owned(request) || !"ask_user".equals(string(request, "name"))) continue;
                String id = string(request, "toolCallId");
                if (present(id)) target.add(askRecord("tool:" + id, request.optJSONObject("arguments")));
                if (target.size() > MAX_EVENTS) throw exhausted();
            }
        }
    }

    private static Record askRecord(String id, JSONObject args) {
        Record record = new Record(ASK, id);
        if (args == null) return record;
        record.text = string(args, "question");
        JSONArray choices = args.optJSONArray("choices");
        if (choices != null) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < choices.length(); i++) {
                Object value = choices.opt(i);
                if (value instanceof String) values.add((String) value);
            }
            record.choices = Collections.unmodifiableList(values);
        }
        Object freeform = args.opt("allowFreeform");
        if (freeform instanceof Boolean) record.allowFreeform = (Boolean) freeform;
        return record;
    }

    private static List<Message> project(List<Record> records) {
        Map<String, Ask> asks = new LinkedHashMap<>();
        for (Record record : records) if (record.kind == ASK) {
            Ask ask = asks.get(record.id);
            if (ask == null) { ask = new Ask(); asks.put(record.id, ask); }
            if (record.text != null) ask.question = record.text;
            if (record.choices != null) ask.choices = record.choices;
            if (record.allowFreeform != null) ask.allowFreeform = record.allowFreeform;
        }
        for (Record record : records) if (record.kind == RESULT) {
            Ask ask = asks.get(record.id);
            if (ask != null) ask.result = record;
        }
        LinkedHashMap<String, Message> result = new LinkedHashMap<>();
        for (Record record : records) {
            if (record.kind == ASK) {
                Ask ask = asks.get(record.id);
                String id = "ask:" + record.id;
                if (present(ask.question) && !result.containsKey(id)) {
                    Message message = new Message(id, "Copilot", ask.question, true);
                    message.question = ask.question;
                    message.choices = ask.choices;
                    message.allowFreeform = ask.allowFreeform;
                    message.askStatus = ask.result == null ? AskStatus.UNCONFIRMED : ask.result.status;
                    result.put(id, message);
                }
            } else if (record.kind == RESULT) {
                Ask ask = asks.get(record.id);
                if (ask != null && ask.result == record && present(record.text)) {
                    String id = "ask-answer:" + record.id;
                    Message reply = new Message(id, "你", record.text, true);
                    reply.askReply = true;
                    reply.question = ask.question;
                    result.put(id, reply);
                }
            } else {
                Message message = result.get(record.id);
                if (message == null) {
                    message = new Message(record.id, record.speaker, "", false);
                    result.put(record.id, message);
                }
                if (record.kind == MESSAGE) { message.text = record.text; message.complete = true; }
                else if (!message.complete) message.text += record.text;
            }
        }
        List<Message> visible = new ArrayList<>();
        for (Message message : result.values()) if (!message.text.isEmpty()) visible.add(message);
        return visible;
    }

    // Mirrors askAnswerOf in cockpit/protocol at 627270a248f634e977dd96fd8ee2d8c70d1288fc.
    private static String answer(JSONObject data) {
        String outcome = outcome(data);
        if (Boolean.FALSE.equals(data.opt("success")) || hasError(data) || Boolean.TRUE.equals(data.opt("dismissed"))
                || (outcome != null && !outcome.equals("answered"))) return "";
        JSONObject result = data.optJSONObject("result");
        if (result == null || Boolean.TRUE.equals(result.opt("dismissed")) || hasError(result)
                || Boolean.TRUE.equals(result.opt("isError")) || Boolean.FALSE.equals(result.opt("success"))) return "";
        for (String key : new String[]{"content", "detailedContent"}) {
            String raw = string(result, key);
            if (raw == null) continue;
            Matcher match = ANSWER.matcher(NATIVE_TRIM.matcher(raw).replaceAll(""));
            if (match.matches()) return NATIVE_TRIM.matcher(match.group(1)).replaceAll("");
        }
        return "";
    }

    private static AskStatus resultStatus(JSONObject data, String answer) {
        if (present(answer)) return AskStatus.ANSWERED;
        JSONObject result = data.optJSONObject("result");
        String outcome = outcome(data);
        if (Boolean.TRUE.equals(data.opt("dismissed"))
                || (result != null && Boolean.TRUE.equals(result.opt("dismissed")))) return AskStatus.DISMISSED;
        if ("cancelled".equals(outcome) || "canceled".equals(outcome)) return AskStatus.CANCELLED;
        if ("dismissed".equals(outcome)) return AskStatus.DISMISSED;
        if ("expired".equals(outcome)) return AskStatus.EXPIRED;
        if ("failed".equals(outcome) || Boolean.FALSE.equals(data.opt("success")) || hasError(data)
                || (result != null && (hasError(result) || Boolean.TRUE.equals(result.opt("isError"))
                || Boolean.FALSE.equals(result.opt("success"))))) return AskStatus.FAILED;
        return AskStatus.UNKNOWN;
    }

    private static String outcome(JSONObject data) {
        JSONObject telemetry = data.optJSONObject("toolTelemetry");
        JSONObject properties = telemetry == null ? null : telemetry.optJSONObject("properties");
        return properties == null ? null : string(properties, "outcome");
    }

    private static boolean hasError(JSONObject object) {
        return object.has("error") && !object.isNull("error");
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static boolean present(String value) { return value != null && !value.isEmpty(); }

    private static JSONException exhausted() {
        return new JSONException("本地历史窗口已达上限，请重新连接");
    }

    private static boolean owned(JSONObject value) {
        return present(string(value, "parentToolCallId")) || present(string(value, "agentId"));
    }
}
