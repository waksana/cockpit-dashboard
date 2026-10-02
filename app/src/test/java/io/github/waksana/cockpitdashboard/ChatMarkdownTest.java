package io.github.waksana.cockpitdashboard;

import android.content.ActivityNotFoundException;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
public class ChatMarkdownTest {
    private static final class BrowserContext extends ContextWrapper {
        Intent opened;
        boolean fail;

        BrowserContext() { super(RuntimeEnvironment.getApplication()); }

        @Override public void startActivity(Intent intent) {
            if (fail) throw new ActivityNotFoundException("test browser unavailable");
            opened = intent;
        }
    }

    @Test public void rendersCommonMarkBlocksAndInlineFormatting() {
        View view = new ChatMarkdown(new BrowserContext()).render(
                "# Heading\n\nfirst\nsecond\n\n**strong** *emphasis* `inline`\n\n"
                + "- bullet\n- other\n\n1. ordered\n2. next\n\n> quote\n\n"
                + "```java\nhello();\n```\n");
        String text = allText(view);
        for (String expected : new String[]{"Heading", "first\nsecond", "strong", "emphasis",
                "inline", "bullet", "ordered", "quote", "hello();"}) {
            assertTrue(expected, text.contains(expected));
        }
        assertFalse(text.contains("```"));
        assertFalse(text.contains("**strong**"));
        assertHasSpan(view, "HeadingSpan");
        assertHasSpan(view, "StrongEmphasisSpan");
        assertHasSpan(view, "EmphasisSpan");
        assertHasSpan(view, "CodeSpan");
        assertHasSpan(view, "CodeBlockSpan");
        assertHasSpan(view, "BlockQuoteSpan");
        assertHasSpan(view, "BulletListItemSpan");
        assertHasSpan(view, "OrderedListItemSpan");
    }

    @Test public void keepsIncompleteStreamingMarkdownReadable() {
        ChatMarkdown markdown = new ChatMarkdown(new BrowserContext());
        for (String input : new String[]{"", "#", "**unfinished", "[link](https://example.com",
                "```java\nincomplete()", "| a | b |\n| --", "![alt", "> quote\n\n- item"}) {
            assertNotNull(markdown.render(input));
        }
        assertTrue(allText(markdown.render("```java\nincomplete()")).contains("incomplete()"));
        assertTrue(allText(markdown.render("**unfinished")).contains("unfinished"));
        assertEquals("", allText(markdown.render(null)));
        String complete = "# Heading\n\n**done**\n\n| A | B |\n| --- | --- |\n| one | two |";
        assertEquals(allText(markdown.render(complete)), allText(markdown.render(complete)));
    }

    @Test public void tableCellsRemainRealAccessibleTextWithInlineFormatting() {
        View view = new ChatMarkdown(new BrowserContext()).render(
                "before\n\n| Name | Value |\n| :--- | ---: |\n| **Alpha** | [open](https://example.com) |\n\nafter");
        LinearLayout body = (LinearLayout) view;
        assertEquals(3, body.getChildCount());
        assertTrue(body.getChildAt(1) instanceof HorizontalScrollView);
        TableLayout table = (TableLayout) ((HorizontalScrollView) body.getChildAt(1)).getChildAt(0);
        assertEquals(2, table.getChildCount());
        TableRow header = (TableRow) table.getChildAt(0);
        assertEquals(2, header.getChildCount());
        assertEquals("Name", ((TextView) header.getChildAt(0)).getText().toString());
        assertHasSpan(header.getChildAt(0), "StyleSpan");
        TableRow row = (TableRow) table.getChildAt(1);
        assertEquals("Alpha", ((TextView) row.getChildAt(0)).getText().toString());
        assertEquals(Gravity.RIGHT, ((TextView) row.getChildAt(1)).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertHasSpan(row.getChildAt(0), "StrongEmphasisSpan");
        assertEquals(1, links((TextView) row.getChildAt(1)).length);
    }

    @Test public void nestedTablesRetainReadableCellsRatherThanOpaqueReplacementSpans() {
        View view = new ChatMarkdown(new BrowserContext()).render(
                "> | Name | Value |\n> | --- | --- |\n> | Alpha | **Beta** |");
        assertTrue(allText(view).contains("Name | Value"));
        assertTrue(allText(view).contains("Alpha | Beta"));
        assertHasSpan(view, "StrongEmphasisSpan");
    }

    @Test public void longCodeAndTablesNeverExpandTheMessageViewport() {
        String token = new String(new char[1000]).replace('\0', 'x');
        View view = new ChatMarkdown(new BrowserContext()).render(
                "```\n" + token + "\n```\n\n| A | B | C |\n| --- | --- | --- |\n| "
                + token + " | value | other |");
        int width = 240;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        assertEquals(width, view.getMeasuredWidth());
        ViewGroup body = (ViewGroup) view;
        for (int i = 0; i < body.getChildCount(); i++) {
            assertTrue(body.getChildAt(i).getMeasuredWidth() <= width);
        }
        HorizontalScrollView table = (HorizontalScrollView) body.getChildAt(1);
        assertTrue(table.getChildAt(0).getMeasuredWidth() > table.getMeasuredWidth());
        TextView code = textViews(body.getChildAt(0)).get(0);
        assertTrue(code.getLineCount() > 1);
        assertTrue(allText(view).contains(token));
    }

    @Test public void rendersHtmlAndImagesWithoutExecutableOrDownloadableContent() {
        BrowserContext context = new BrowserContext();
        View view = new ChatMarkdown(context).render(
                "![photo](https://media.example.com/private.png)\n\n"
                + "<script>alert('no')</script>\n\n"
                + "<img src=\"https://media.example.com/tracker.png\">");
        assertTrue(allText(view).contains("[image: photo]"));
        assertTrue(allText(view).contains("<script>alert('no')</script>"));
        assertTrue(allText(view).contains("<img src="));
        assertNull(context.opened);
        for (TextView text : textViews(view)) {
            Spanned spans = (Spanned) text.getText();
            for (Object span : spans.getSpans(0, spans.length(), Object.class)) {
                assertFalse(span.getClass().getName().contains("AsyncDrawable"));
            }
        }
    }

    @Test public void acceptsOnlyExplicitExternalWebAddresses() {
        for (String url : new String[]{"https://example.com/path?q=x#part", "http://example.com/",
                "HTTPS://EXAMPLE.COM/", "https://xn--bcher-kva.example/"}) {
            assertTrue(url, ChatMarkdown.isSafeExternalLink(url));
        }
        for (String url : new String[]{"javascript:alert(1)", "file:///sdcard/private", "content://data/1",
                "intent://example.com/#Intent;end", "data:text/html,test", "mailto:user@example.com",
                "//example.com/path", "/relative", "https://user:secret@example.com", "https://example.com@evil.com",
                "https://example.com\\@evil.com", "https://example.com\n", "https://", "https://localhost",
                "http://127.0.0.1", "http://192.168.1.2", "http://[::1]", "http://host.local",
                "https://host.local.", "http://host.internal", "https://user%40host@example.com", ""}) {
            assertFalse(url, ChatMarkdown.isSafeExternalLink(url));
        }
        assertFalse(ChatMarkdown.isSafeExternalLink(null));
    }

    @Test public void clickingLinkUsesBrowsableIntentAndDoesNotOpenDuringRender() {
        BrowserContext context = new BrowserContext();
        TextView text = textViews(new ChatMarkdown(context).render("[open](https://example.com/docs)")).get(0);
        assertNull(context.opened);
        links(text)[0].onClick(text);
        assertEquals(Intent.ACTION_VIEW, context.opened.getAction());
        assertEquals("https://example.com/docs", context.opened.getDataString());
        assertTrue(context.opened.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(context.opened.getComponent());
    }

    @Test public void blockedAndUnavailableLinksShowGenericVisibleFeedback() {
        BrowserContext context = new BrowserContext();
        ChatMarkdown markdown = new ChatMarkdown(context);
        TextView blocked = textViews(markdown.render("[private](file:///private/passwords)")).get(0);
        links(blocked)[0].onClick(blocked);
        assertNull(context.opened);
        assertEquals("已阻止不安全或本地链接", ShadowToast.getTextOfLatestToast());
        context.fail = true;
        TextView safe = textViews(markdown.render("[open](https://example.com/secret-token)")).get(0);
        links(safe)[0].onClick(safe);
        assertEquals("无法打开链接，请检查浏览器是否可用", ShadowToast.getTextOfLatestToast());
    }

    @Test public void bareWebLinksUseTheSameResolverButCodeIsNotLinkified() {
        BrowserContext context = new BrowserContext();
        ChatMarkdown markdown = new ChatMarkdown(context);
        TextView text = textViews(markdown.render("https://example.com/path")).get(0);
        assertEquals(1, links(text).length);
        links(text)[0].onClick(text);
        assertEquals("https://example.com/path", context.opened.getDataString());
        TextView code = textViews(markdown.render("`https://example.com`\n\n```\nhttps://example.com\n```")).get(0);
        assertEquals(0, links(code).length);
    }

    @Test public void renderingDoesNotStealFocusAndKeepsBodyTypography() {
        BrowserContext context = new BrowserContext();
        ChatMarkdown markdown = new ChatMarkdown(context);
        View body = markdown.render("plain\n\n[link](https://example.com)", Color.YELLOW);
        assertFalse(body.hasFocus());
        TextView text = textViews(body).get(0);
        assertEquals(25 * context.getResources().getDisplayMetrics().scaledDensity, text.getTextSize(), 0.01);
        assertEquals(Color.YELLOW, text.getCurrentTextColor());
        assertTrue(text.isFocusable());
        assertFalse(text.isFocusableInTouchMode());
        assertFalse(textViews(markdown.render("plain")).get(0).isFocusable());
    }

    private static ClickableSpan[] links(TextView text) {
        Spanned value = (Spanned) text.getText();
        return value.getSpans(0, value.length(), ClickableSpan.class);
    }

    private static List<TextView> textViews(View view) {
        List<TextView> result = new ArrayList<>();
        if (view instanceof TextView) result.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(textViews(group.getChildAt(i)));
        }
        return result;
    }

    private static String allText(View view) {
        StringBuilder result = new StringBuilder();
        for (TextView text : textViews(view)) result.append(text.getText());
        return result.toString();
    }

    private static void assertHasSpan(View view, String name) {
        for (TextView text : textViews(view)) {
            Spanned value = (Spanned) text.getText();
            for (Object span : value.getSpans(0, value.length(), Object.class)) {
                if (span.getClass().getSimpleName().equals(name)) return;
            }
        }
        fail("Missing " + name);
    }
}
