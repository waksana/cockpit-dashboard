package io.github.waksana.cockpitdashboard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only displays the authoritative active ask; the owner handles draft and key semantics. */
public final class ActiveQuestionView extends LinearLayout {
    public interface Listener {
        void onChoicePreview(String requestId, String choice);
    }

    private static final int BACKGROUND = Color.rgb(15, 20, 29);
    private static final int HIGHLIGHT = Color.rgb(254, 214, 123);
    private final Listener listener;
    private final ChatMarkdown markdown;
    private final List<String> choices = new ArrayList<>();
    private final List<TextView> rows = new ArrayList<>();
    private String requestId = "";
    private String questionText = "";
    private boolean allowFreeform;
    private int selected = -1;
    private long generation;

    public ActiveQuestionView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        markdown = new ChatMarkdown(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BACKGROUND);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setVisibility(GONE);
    }

    public void update(JSONObject ask) {
        Object rawId = ask == null ? null : ask.opt("requestId");
        final String id = rawId instanceof String ? (String) rawId : "";
        List<String> next = new ArrayList<>();
        JSONArray array = ask == null ? null : ask.optJSONArray("choices");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object choice = array.opt(i);
                if (choice instanceof String) next.add((String) choice);
            }
        }
        String question = ask == null ? "" : ask.optString("question", "");
        boolean freeform = ask != null && ask.optBoolean("allowFreeform", false);
        if (id.equals(requestId) && next.equals(choices) && question.equals(questionText)
                && freeform == allowFreeform) return;
        final long version = ++generation;
        if (!id.equals(requestId) || !next.equals(choices)) selected = -1;
        requestId = id;
        questionText = question;
        choices.clear();
        choices.addAll(next);
        allowFreeform = freeform;
        rows.clear();
        removeAllViews();
        if (id.isEmpty()) {
            selected = -1;
            choices.clear();
            allowFreeform = false;
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        View body = markdown.render(questionText, HIGHLIGHT);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        addView(body);
        for (int i = 0; i < choices.size(); i++) {
            final int index = i;
            final String choice = choices.get(i);
            TextView row = text(choice, Color.WHITE);
            row.setMinHeight(dp(56));
            row.setContentDescription("选项 " + (i + 1) + " / " + choices.size() + "：" + choice);
            row.setOnClickListener(view -> {
                // Detached rows and callbacks from a replaced snapshot cannot preview.
                if (version != generation || !id.equals(requestId)) return;
                select(index);
                if (listener != null) listener.onChoicePreview(id, choice);
            });
            rows.add(row);
            addView(row);
        }
        paintSelection();
    }

    /** Direction is negative for up, positive for down. False means no movement. */
    public boolean move(int direction) {
        if (choices.isEmpty() || direction == 0) return false;
        int next = selected < 0 ? (direction > 0 ? 0 : choices.size() - 1)
                : Math.max(0, Math.min(choices.size() - 1, selected + (direction > 0 ? 1 : -1)));
        if (next == selected) return false;
        select(next);
        return true;
    }

    public String selectedChoice() {
        return selected < 0 ? null : choices.get(selected);
    }

    public String requestId() { return requestId; }
    public boolean allowFreeform() { return allowFreeform; }
    public View selectedView() { return selected < 0 ? null : rows.get(selected); }

    public void clearSelection() {
        selected = -1;
        paintSelection();
    }

    private void select(int index) {
        selected = index;
        paintSelection();
        View row = selectedView();
        Rect bounds = new Rect();
        row.getDrawingRect(bounds);
        row.requestRectangleOnScreen(bounds, true);
    }

    private void paintSelection() {
        for (int i = 0; i < rows.size(); i++) {
            TextView row = rows.get(i);
            boolean active = i == selected;
            row.setSelected(active);
            GradientDrawable background = new GradientDrawable();
            background.setColor(active ? Color.rgb(49, 55, 67) : BACKGROUND);
            background.setCornerRadius(dp(8));
            background.setStroke(dp(2), active ? HIGHLIGHT : BACKGROUND);
            row.setBackground(background);
        }
    }

    private TextView text(String value, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(25);
        view.setTextColor(color);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
