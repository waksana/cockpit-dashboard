package io.github.waksana.cockpitdashboard;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicit reading mode; draft send/cancel and hold-to-record remain owned by the activity. */
final class MarkdownNavigation {
    private static final class Control {
        final View view;
        final ClickableSpan link;
        Control(View view, ClickableSpan link) { this.view = view; this.link = link; }
    }

    private final List<Control> controls = new ArrayList<>();
    private int selected = -1;
    private Drawable priorForeground;
    private ViewGroup root;
    private BackgroundColorSpan selectedLink;

    boolean active() { return selected >= 0; }

    boolean confirm(ViewGroup conversation) {
        if (active()) {
            Control control = controls.get(selected);
            if (!belongsTo(control.view, conversation)) { clear(); return false; }
            if (control.link != null) control.link.onClick(control.view);
            return true;
        }
        controls.clear();
        root = conversation;
        collect(conversation);
        for (int i = 0; i < controls.size(); i++) {
            if (visible(controls.get(i))) {
                select(i);
                return true;
            }
        }
        controls.clear();
        return false;
    }

    boolean move(int direction) {
        if (!active()) return false;
        if (!belongsTo(controls.get(selected).view, root)) { clear(); return false; }
        int next = Math.max(0, Math.min(controls.size() - 1, selected + direction));
        if (next != selected) select(next);
        return true;
    }

    boolean horizontal(int direction) {
        if (!active()) return false;
        if (!belongsTo(controls.get(selected).view, root)) { clear(); return false; }
        View view = controls.get(selected).view;
        while (!(view instanceof HorizontalScrollView)) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return false;
            view = (View) parent;
        }
        HorizontalScrollView table = (HorizontalScrollView) view;
        table.scrollBy(direction * Math.max(1, table.getWidth() * 2 / 3), 0);
        return true;
    }

    void clear() {
        unpaint();
        selected = -1;
        controls.clear();
        root = null;
    }

    private void select(int index) {
        unpaint();
        selected = index;
        Control control = controls.get(index);
        priorForeground = control.view.getForeground();
        if (control.link != null) {
            TextView text = (TextView) control.view;
            if (text.getText() instanceof Spannable) {
                Spannable value = (Spannable) text.getText();
                selectedLink = new BackgroundColorSpan(Color.rgb(80, 69, 35));
                value.setSpan(selectedLink, value.getSpanStart(control.link), value.getSpanEnd(control.link),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setStroke(Math.max(2, Math.round(2 * control.view.getResources().getDisplayMetrics().density)),
                    Color.rgb(254, 214, 123));
            control.view.setForeground(outline);
        }
        control.view.requestRectangleOnScreen(bounds(control), true);
    }

    private void unpaint() {
        if (!active()) return;
        View view = controls.get(selected).view;
        view.setForeground(priorForeground);
        if (selectedLink != null && view instanceof TextView && ((TextView) view).getText() instanceof Spannable)
            ((Spannable) ((TextView) view).getText()).removeSpan(selectedLink);
        selectedLink = null;
        priorForeground = null;
    }

    private static Rect bounds(Control control) {
        Rect bounds = new Rect();
        control.view.getDrawingRect(bounds);
        if (control.link == null) return bounds;
        TextView view = (TextView) control.view;
        Layout layout = view.getLayout();
        if (layout == null) return bounds;
        Spanned text = (Spanned) view.getText();
        int start = text.getSpanStart(control.link), end = text.getSpanEnd(control.link);
        int line = layout.getLineForOffset(start);
        Path selection = new Path();
        layout.getSelectionPath(start, Math.min(end, layout.getLineEnd(line)), selection);
        RectF span = new RectF();
        selection.computeBounds(span, true);
        int left = view.getTotalPaddingLeft() - view.getScrollX();
        int top = view.getTotalPaddingTop() - view.getScrollY();
        bounds.set(left + (int) Math.floor(span.left), top + layout.getLineTop(line),
                left + Math.max((int) Math.floor(span.left) + 1, (int) Math.ceil(span.right)),
                top + layout.getLineBottom(line));
        return bounds;
    }

    private static boolean visible(Control control) {
        Rect clipped = new Rect();
        return control.view.getLocalVisibleRect(clipped) && Rect.intersects(bounds(control), clipped);
    }

    private void collect(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof HorizontalScrollView) controls.add(new Control(view, null));
        if (view instanceof TextView && ((TextView) view).getText() instanceof Spanned) {
            Spanned text = (Spanned) ((TextView) view).getText();
            ClickableSpan[] links = text.getSpans(0, text.length(), ClickableSpan.class);
            Arrays.sort(links, (left, right) -> Integer.compare(text.getSpanStart(left), text.getSpanStart(right)));
            for (ClickableSpan link : links)
                controls.add(new Control(view, link));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i));
        }
    }

    private static boolean belongsTo(View view, ViewGroup root) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent == root) return true;
        return false;
    }
}
