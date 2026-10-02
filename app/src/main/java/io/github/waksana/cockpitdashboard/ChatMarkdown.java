package io.github.waksana.cockpitdashboard;

import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.parser.Parser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Locale;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.linkify.LinkifyPlugin;

/** One offline rendering path for both streaming snapshots and persisted messages. */
public final class ChatMarkdown {
    private final Context context;
    private final Markwon markwon;

    public ChatMarkdown(Context context) {
        this.context = context;
        markwon = Markwon.builder(context)
                .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override public void configureParser(Parser.Builder builder) {
                        builder.extensions(Collections.singleton(TablesExtension.create()));
                    }

                    @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                        builder.linkResolver((view, destination) -> openLink(destination));
                    }

                    @Override public void configureVisitor(MarkwonVisitor.Builder builder) {
                        builder.on(SoftLineBreak.class, (visitor, node) -> visitor.forceNewLine());
                        builder.on(Image.class, (visitor, node) -> {
                            visitor.builder().append("[image: ");
                            visitor.visitChildren(node);
                            visitor.builder().append("]");
                        });
                        // No HTML/image plugins or WebView: raw HTML stays inert, readable text.
                        builder.on(HtmlInline.class, (visitor, node) ->
                                visitor.builder().append(node.getLiteral()));
                        builder.on(HtmlBlock.class, (visitor, node) -> {
                            visitor.blockStart(node);
                            visitor.builder().append(node.getLiteral());
                            visitor.blockEnd(node);
                        });
                        builder.on(TableCell.class, (visitor, node) -> visitor.visitChildren(node));
                        // Tables nested in lists/quotes retain their cell text and inline styling.
                        builder.on(TableBlock.class, (visitor, node) -> {
                            visitor.blockStart(node);
                            renderTableText(visitor, node);
                            visitor.blockEnd(node);
                        });
                    }
                }).build();
    }

    public View render(String markdown) {
        return render(markdown, Color.WHITE);
    }

    /** Must be called on the UI thread; never requests focus or starts media downloads. */
    public View render(String markdown, int textColor) {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Node document = markwon.parse(markdown == null ? "" : markdown);
        Document group = new Document();
        for (Node node = document.getFirstChild(); node != null;) {
            Node next = node.getNext();
            node.unlink();
            if (node instanceof TableBlock) {
                appendGroup(body, group, textColor);
                group = new Document();
                appendBlock(body, table(node, textColor));
            } else {
                group.appendChild(node);
            }
            node = next;
        }
        appendGroup(body, group, textColor);
        return body;
    }

    private void appendGroup(LinearLayout body, Document group, int color) {
        if (group.getFirstChild() != null) appendBlock(body, text(markwon.render(group), color));
    }

    private void appendBlock(LinearLayout body, View block) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (body.getChildCount() > 0) params.topMargin = dp(12);
        body.addView(block, params);
    }

    private TextView text(Spanned value, int color) {
        TextView view = new TextView(context);
        view.setTextSize(25);
        view.setTextColor(color);
        view.setLinkTextColor(Color.rgb(136, 191, 255));
        view.setHorizontallyScrolling(false);
        view.setSingleLine(false);
        markwon.setParsedMarkdown(view, value);
        boolean hasLinks = value.getSpans(0, value.length(), ClickableSpan.class).length > 0;
        view.setMovementMethod(hasLinks ? LinkMovementMethod.getInstance() : null);
        view.setFocusable(hasLinks);
        view.setFocusableInTouchMode(false);
        view.setClickable(hasLinks);
        view.setLongClickable(false);
        return view;
    }

    private View table(Node table, int color) {
        HorizontalScrollView viewport = new HorizontalScrollView(context);
        viewport.setFillViewport(true);
        viewport.setFocusable(true);
        TableLayout grid = new TableLayout(context);
        grid.setStretchAllColumns(true);
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                appendRow(grid, row, section instanceof TableHead, color);
            }
        }
        viewport.addView(grid, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return viewport;
    }

    private void appendRow(TableLayout grid, Node node, boolean header, int color) {
        TableRow row = new TableRow(context);
        row.setBackgroundColor(header ? Color.rgb(41, 53, 72) : Color.rgb(23, 30, 42));
        for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext()) {
            TextView value = text(markwon.render(cell), color);
            value.setPadding(dp(10), dp(8), dp(10), dp(8));
            if (header && value.length() > 0) {
                SpannableString heading = new SpannableString(value.getText());
                heading.setSpan(new StyleSpan(Typeface.BOLD), 0, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(heading);
            }
            if (cell instanceof TableCell) {
                TableCell.Alignment alignment = ((TableCell) cell).getAlignment();
                value.setGravity(alignment == TableCell.Alignment.RIGHT ? Gravity.RIGHT
                        : alignment == TableCell.Alignment.CENTER ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
            }
            // Cell width is bounded even for unbroken tokens; only the table viewport scrolls.
            row.addView(value, new TableRow.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        grid.addView(row);
    }

    private static void renderTableText(MarkwonVisitor visitor, Node node) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableCell) {
                visitor.visitChildren(child);
                if (child.getNext() != null) visitor.builder().append(" | ");
            } else {
                renderTableText(visitor, child);
            }
        }
        if (node.getFirstChild() instanceof TableCell) visitor.forceNewLine();
    }

    static boolean isSafeExternalLink(String destination) {
        if (destination == null || destination.isEmpty()) return false;
        for (int i = 0; i < destination.length(); i++) {
            char c = destination.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\') return false;
        }
        try {
            URI uri = new URI(destination);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            if (uri.getRawUserInfo() != null || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            // Only ordinary external DNS names, not device/local addresses or IP literals.
            if (!host.contains(".") || host.matches("[0-9.]+") || host.contains(":")) return false;
            return !host.endsWith(".localhost") && !host.endsWith(".local")
                    && !host.endsWith(".internal") && !host.endsWith(".home")
                    && !host.endsWith(".lan");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private void openLink(String destination) {
        if (!isSafeExternalLink(destination)) {
            Toast.makeText(context, "已阻止不安全或本地链接", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // HTTP is accepted for explicit ordinary web links, never upgraded silently.
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(destination));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(context, "无法打开链接，请检查浏览器是否可用", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
