package io.github.waksana.cockpitdashboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SessionDirectory {
    static final int PAGE_SIZE = 50;
    static final int MAX_SCAN_PAGES = 20;
    final List<Entry> entries;
    final String next;

    static final class Entry {
        final String id;
        final String title;
        final String cwd;
        Entry(JSONObject item) throws JSONException {
            id = item.getString("sessionId");
            if (!id.matches("[A-Za-z0-9_-]{1,200}")) throw new JSONException("Invalid session ID");
            title = item.getString("title");
            cwd = item.getString("cwd");
        }
        String label(String selected) {
            return (id.equals(selected) ? "✓ " : "") + (title.trim().isEmpty() ? "未命名会话" : title)
                    + "\n" + cwd + " · " + id;
        }
    }

    SessionDirectory(JSONObject result) throws JSONException {
        JSONArray sessions = result.getJSONArray("sessions");
        if (sessions.length() > PAGE_SIZE) throw new JSONException("Oversized directory page");
        entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.getJSONObject(i);
            Entry entry = new Entry(item);
            if (!ids.add(entry.id)) throw new JSONException("Duplicate session ID");
            if (hasAssistantRole(item)) entries.add(entry);
        }
        next = result.has("cursor") ? result.getString("cursor") : null;
        if (next != null && (next.isEmpty() || next.length() > 2048)) throw new JSONException("Invalid directory cursor");
    }

    private static boolean hasAssistantRole(JSONObject item) throws JSONException {
        if (!item.has("roles")) return false;
        JSONArray roles = item.getJSONArray("roles");
        boolean found = false;
        for (int i = 0; i < roles.length(); i++) {
            JSONObject role = roles.getJSONObject(i);
            if ("assistant".equals(role.getString("moduleId"))
                    && "coordinator".equals(role.getString("roleId"))) found = true;
        }
        return found;
    }
}
