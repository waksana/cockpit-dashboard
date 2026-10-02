package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.style.MetricAffectingSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
public class ActiveQuestionViewTest {
    private JSONObject ask(String id, String... choices) throws Exception {
        return new JSONObject().put("requestId", id).put("question", "请选择\n一个方案")
                .put("choices", new JSONArray(choices)).put("allowFreeform", true);
    }

    private ActiveQuestionView card() {
        return new ActiveQuestionView(RuntimeEnvironment.getApplication(), null);
    }

    @Test public void movesClearsAndClampsWithoutPreviewing() throws Exception {
        List<String> previews = new ArrayList<>();
        ActiveQuestionView view = new ActiveQuestionView(RuntimeEnvironment.getApplication(),
                (id, choice) -> previews.add(choice));
        view.update(ask("one", "甲", "乙", "丙"));
        assertNull(view.selectedChoice());
        assertFalse(view.move(0));
        assertTrue(view.move(1));
        View first = view.selectedView();
        assertEquals("甲", view.selectedChoice());
        assertTrue(first.isSelected());
        assertFalse(view.move(-1));
        assertTrue(view.move(1));
        assertFalse(first.isSelected());
        assertEquals("乙", view.selectedChoice());
        view.clearSelection();
        assertNull(view.selectedView());
        assertNull(view.selectedChoice());
        assertTrue(view.move(-1));
        assertEquals("丙", view.selectedChoice());
        assertFalse(view.move(1));
        assertTrue(previews.isEmpty());
    }

    @Test public void unchangedSnapshotKeepsSelectionButChangedIdentityOrChoicesClears() throws Exception {
        ActiveQuestionView view = card();
        view.update(ask("one", "甲", "乙"));
        view.move(-1);
        view.update(ask("one", "甲", "乙"));
        assertEquals("乙", view.selectedChoice());
        assertTrue(view.selectedView().isSelected());
        view.update(ask("two", "甲", "乙"));
        assertEquals("two", view.requestId());
        assertNull(view.selectedChoice());
        view.move(-1);
        view.update(ask("two", "甲"));
        assertNull(view.selectedChoice());
        assertTrue(view.move(1));
        assertEquals("甲", view.selectedChoice());
        view.update(ask("two", "replacement"));
        assertNull(view.selectedChoice());
    }

    @Test public void emptyChoicesAllowQuestionAndFreeformWithoutSelection() throws Exception {
        ActiveQuestionView view = card();
        view.update(ask("one"));
        assertEquals(View.VISIBLE, view.getVisibility());
        TextView question = (TextView) ((ViewGroup) view.getChildAt(0)).getChildAt(0);
        assertEquals("请选择\n一个方案", question.getText().toString());
        assertTrue(view.allowFreeform());
        assertFalse(view.move(1));
        assertFalse(view.move(-1));
        assertNull(view.selectedView());
        view.update(ask("one").put("allowFreeform", false));
        assertFalse(view.allowFreeform());
    }

    @Test public void questionUsesSharedMarkdownWhileChoicesRemainExactPlainText() throws Exception {
        ActiveQuestionView view = card();
        view.update(ask("one", "**exact choice**").put("question", "**重要问题**"));
        TextView question = (TextView) ((ViewGroup) view.getChildAt(0)).getChildAt(0);
        assertEquals("重要问题", question.getText().toString());
        assertEquals(Color.rgb(254, 214, 123), question.getCurrentTextColor());
        Spanned body = (Spanned) question.getText();
        assertTrue(body.getSpans(0, body.length(), MetricAffectingSpan.class).length > 0);
        assertEquals("**exact choice**", ((TextView) view.getChildAt(1)).getText().toString());
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, view.getDescendantFocusability());
    }

    @Test public void nullAndEmptyAskHideAndInvalidateOldRows() throws Exception {
        List<String> previews = new ArrayList<>();
        ActiveQuestionView view = new ActiveQuestionView(RuntimeEnvironment.getApplication(),
                (id, choice) -> previews.add(choice));
        view.update(ask("one", "甲"));
        view.move(1);
        View old = view.selectedView();
        view.update(null);
        old.performClick();
        assertTrue(previews.isEmpty());
        assertEquals(View.GONE, view.getVisibility());
        assertEquals("", view.requestId());
        assertNull(view.selectedView());
        assertFalse(view.move(1));
        assertFalse(view.allowFreeform());
        assertEquals(0, view.getChildCount());
        view.update(new JSONObject());
        assertEquals(View.GONE, view.getVisibility());
        view.update(ask("", "甲"));
        assertEquals(View.GONE, view.getVisibility());
    }

    @Test public void touchUsesExactImmutableSnapshotOnceAndRejectsStaleRows() throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> previews = new ArrayList<>();
        ActiveQuestionView view = new ActiveQuestionView(RuntimeEnvironment.getApplication(),
                (id, choice) -> { ids.add(id); previews.add(choice); });
        String exact = "  保留原文\nhttps://example.invalid/<b>不是 HTML</b>  ";
        JSONObject snapshot = ask("one", exact);
        view.update(snapshot);
        TextView row = (TextView) view.getChildAt(1);
        assertEquals(exact, row.getText().toString());
        assertNull(row.getMovementMethod());
        assertTrue(row.isClickable());
        assertFalse(row.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, row.getImportantForAccessibility());
        snapshot.put("requestId", "mutated").put("choices", new JSONArray().put("mutated"));
        row.performClick();
        assertEquals(1, previews.size());
        assertEquals("one", ids.get(0));
        assertEquals(exact, previews.get(0));
        assertEquals(exact, view.selectedChoice());
        view.update(ask("two", "next"));
        row.performClick();
        assertEquals(1, previews.size());
        View next = view.getChildAt(1);
        view.update(ask("two", "replacement"));
        next.performClick();
        assertEquals(1, previews.size());
        View replaced = view.getChildAt(1);
        view.update(ask("two", "replacement"));
        replaced.performClick();
        assertEquals(1, previews.size());
        view.getChildAt(1).performClick();
        assertEquals(2, previews.size());
        assertEquals("two", ids.get(1));
        assertEquals("replacement", previews.get(1));
    }

    @Test public void longChoiceListExposesAndScrollsSelectionWithoutTakingRemoteFocus() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setFocusableInTouchMode(true);
            root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            ScrollView scroll = new ScrollView(activity);
            ActiveQuestionView view = new ActiveQuestionView(activity, null);
            String[] choices = new String[30];
            for (int i = 0; i < choices.length; i++) choices[i] = "选项 " + i;
            view.update(ask("many", choices));
            scroll.addView(view);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
            activity.setContentView(root);
            root.requestFocus();
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 800, 320);
            for (int i = 0; i < choices.length; i++) assertTrue(view.move(1));
            assertEquals("选项 29", view.selectedChoice());
            assertSame(view.getChildAt(30), view.selectedView());
            assertTrue(scroll.getScrollY() > 0);
            Rect bounds = new Rect();
            view.selectedView().getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(view.selectedView(), bounds);
            assertTrue(bounds.top >= scroll.getScrollY());
            assertTrue(bounds.bottom <= scroll.getScrollY() + scroll.getHeight());
            assertTrue(root.hasFocus());
            assertSame(root, root.findFocus());
            assertFalse(view.selectedView().isFocused());
            view.clearSelection();
            assertTrue(view.move(1));
            assertEquals("选项 0", view.selectedChoice());
            assertTrue(scroll.getScrollY() < bounds.top);
        }
    }
}
