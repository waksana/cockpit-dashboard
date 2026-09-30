package io.github.waksana.cockpitdashboard;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded, single-worker reducer; final text replaces deltas, never concatenates them. */
final class Transcript {
    static final int MAX_TEXT = 131_072;
    private static final int MAX_ITEMS = 1_024;
    private final Map<String, Item> items = new HashMap<>();
    private int characters;
    private boolean barrier;

    void committed(JSONObject event) throws JSONException {
        if (barrier) throw invalid();
        Item item = item(event);
        Object previous = event.opt("previous_item_id");
        if (previous != null && previous != JSONObject.NULL && (!(previous instanceof String)
                || ((String) previous).isEmpty() || ((String) previous).length() > 256)) throw invalid();
        if (item.committed) throw invalid();
        item.previous = previous == null || previous == JSONObject.NULL ? null : (String) previous;
        item.committed = true;
    }

    void text(JSONObject event, boolean complete) throws JSONException {
        Object index = event.get("content_index");
        if (!(index instanceof Number) || ((Number) index).doubleValue() != 0) throw invalid();
        Item item = item(event);
        Object value = event.get(complete ? "transcript" : "delta");
        if (!(value instanceof String)) throw invalid();
        String text = (String) value;
        if (text.length() > MAX_TEXT) throw invalid();
        if (item.complete) {
            if (complete && !item.text.equals(text)) throw invalid();
            return;
        }
        int next = characters - item.text.length() + (complete ? text.length()
                : item.text.length() + text.length());
        if (next > MAX_TEXT) throw invalid();
        characters = next;
        item.text = complete ? text : item.text + text;
        item.complete = complete;
    }

    int committedCount() {
        int count = 0;
        for (Item item : items.values()) if (item.committed) count++;
        return count;
    }

    void barrier() throws JSONException {
        if (barrier || items.isEmpty()) throw invalid();
        barrier = true;
        ordered(false);
    }

    /** Null means the barrier or a final transcription is still outstanding. */
    String result() throws JSONException {
        if (!barrier) return null;
        for (Item item : items.values()) if (!item.complete) return null;
        String text = ordered(true).trim();
        if (text.isEmpty()) throw new JSONException("empty");
        return text;
    }

    void clear() {
        items.clear();
        characters = 0;
    }

    private String ordered(boolean collect) throws JSONException {
        Map<String, String> successor = new HashMap<>();
        String first = null;
        for (Map.Entry<String, Item> entry : items.entrySet()) {
            Item item = entry.getValue();
            if (!item.committed) throw invalid();
            if (item.previous == null) {
                if (first != null) throw invalid();
                first = entry.getKey();
            } else {
                if (!items.containsKey(item.previous)
                        || successor.put(item.previous, entry.getKey()) != null) throw invalid();
            }
        }
        if (first == null) throw invalid();
        Set<String> seen = new HashSet<>();
        StringBuilder text = new StringBuilder();
        for (String id = first; id != null; id = successor.get(id)) {
            if (!seen.add(id)) throw invalid();
            if (collect && !items.get(id).text.trim().isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(items.get(id).text.trim());
            }
        }
        if (seen.size() != items.size()) throw invalid();
        return text.toString();
    }

    private Item item(JSONObject event) throws JSONException {
        Object raw = event.get("item_id");
        if (!(raw instanceof String)) throw invalid();
        String id = (String) raw;
        if (id.isEmpty() || id.length() > 256) throw invalid();
        Item item = items.get(id);
        if (item == null) {
            if (barrier || items.size() >= MAX_ITEMS) throw invalid();
            item = new Item();
            items.put(id, item);
        }
        return item;
    }

    private static JSONException invalid() { return new JSONException("protocol"); }

    private static final class Item {
        String previous;
        String text = "";
        boolean committed;
        boolean complete;
    }
}
