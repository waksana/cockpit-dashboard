package io.github.waksana.cockpitdashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import io.noties.markwon.Markwon;
import io.noties.markwon.linkify.LinkifyPlugin;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private final RemoteState state = new RemoteState();
    private final ChatProjection projection = new ChatProjection();
    private PrivateStore store;
    private JSONObject settings = new JSONObject();
    private JSONObject meta;
    private JSONObject target;
    private HostClient client;
    private AudioCapture capture;
    private SpeechTranscriber speech;
    private ScrollView scroll;
    private LinearLayout conversation;
    private TextView heading, status, draftView, questionView;
    private Markwon markdown;
    private boolean foreground, dialog, connected, busyRead, live, hasOlder, storageFailed;
    private boolean capturing;
    private byte[] pendingAudio;
    private int connectionEpoch, recordingEpoch;
    private String olderCursor, forwardCursor;
    private String notice = "";
    private String receipt = "";
    private long nextMetaRead;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        store = new PrivateStore(this);
        try {
            settings = store.read();
            state.restore(settings.optString("draft"), settings.optBoolean("uncertain"));
            target = settings.optJSONObject("target");
            receipt = settings.optString("receipt");
        } catch (GeneralSecurityException | IOException | JSONException | IllegalArgumentException error) {
            storageFailed = true;
            notice = "无法解密本地配置，已禁止发送。请在 Android 设置中清除本 App 数据后重新配置。";
        }
        createViews();
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(12, 8, 12, 8);
        return view;
    }

    private void createViews() {
        markdown = Markwon.builder(this).usePlugin(LinkifyPlugin.create()).build();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 16, 28, 16);
        root.setBackgroundColor(Color.rgb(15, 20, 29));
        root.setFocusableInTouchMode(true);
        heading = text(18, Color.LTGRAY);
        root.addView(heading);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(conversation);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        questionView = text(24, Color.rgb(254, 214, 123));
        draftView = text(26, Color.WHITE);
        status = text(18, Color.LTGRAY);
        root.addView(status);
        setContentView(root);
        root.requestFocus();
        fullscreen();
        renderMessages(false);
        renderState();
    }

    @SuppressWarnings("deprecation")
    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (settings.optString("address").isEmpty()) {
            main.post(() -> { if (!storageFailed && !dialog && foreground) openSettings(); });
        } else connect();
    }

    @Override protected void onPause() {
        foreground = false;
        connectionEpoch++;
        busyRead = false;
        connected = false;
        main.removeCallbacks(poll);
        interruptRecording();
        super.onPause();
    }

    @Override protected void onDestroy() {
        recordingEpoch++;
        connectionEpoch++;
        if (capture != null) capture.cancel();
        if (speech != null) speech.cancel();
        if (pendingAudio != null) Arrays.fill(pendingAudio, (byte) 0);
        reads.shutdownNow();
        // In-flight mutations must not be retried or represented as cancelled.
        writes.shutdown();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) interruptRecording();
        else fullscreen();
    }

    private boolean persist() {
        try {
            settings.put("draft", state.draft).put("target", target == null ? JSONObject.NULL : target)
                    .put("uncertain", state.phase == RemoteState.Phase.SENDING || state.phase == RemoteState.Phase.UNKNOWN)
                    .put("receipt", receipt);
            store.write(settings);
            return true;
        } catch (GeneralSecurityException | IOException | JSONException error) {
            storageFailed = true;
            notice = "本地安全存储失败，已禁止发送；草稿尚未可靠保存。";
            renderState();
            return false;
        }
    }

    private void connect() {
        if (!foreground || storageFailed) return;
        final int epoch = ++connectionEpoch;
        main.removeCallbacks(poll);
        busyRead = true;
        connected = false;
        forwardCursor = null;
        olderCursor = null;
        notice = "正在连接…";
        renderState();
        final HostClient next;
        try {
            HostClient.validateSettings(settings.getString("address"), settings.getString("sessionId"),
                    settings.optString("authorization"));
            next = new HostClient(settings.getString("address"), settings.getString("sessionId"),
                    settings.optString("authorization"));
        } catch (JSONException | IllegalArgumentException error) {
            busyRead = false;
            notice = "连接配置无效，请按返回键打开设置";
            renderState();
            return;
        }
        client = next;
        reads.execute(() -> {
            try {
                next.capabilities();
                JSONObject current = next.meta();
                boolean loaded = current.getBoolean("loaded");
                JSONObject page = next.page(loaded, "backward", null, loaded);
                main.post(() -> {
                    if (!valid(epoch)) return;
                    try {
                        projection.clear();
                        projection.accept(page.getJSONArray("events"), false);
                        meta = current;
                        live = loaded;
                        olderCursor = page.getString("cursor");
                        hasOlder = page.getBoolean("hasMore");
                        forwardCursor = loaded ? page.getString("liveCursor") : null;
                        connected = true;
                        notice = loaded ? "" : "会话未加载；发送时由 Cockpit 恢复，当前显示已保存历史";
                        renderMessages(true);
                    } catch (JSONException error) {
                        notice = "历史协议不兼容，未建立实时连接";
                        connected = false;
                    }
                    busyRead = false;
                    renderState();
                    if (connected) main.postDelayed(poll, 1200);
                });
            } catch (IOException | JSONException error) {
                main.post(() -> readFailed(epoch, "连接失败：" + safe(error)));
            }
        });
    }

    private boolean valid(int epoch) {
        return foreground && epoch == connectionEpoch && !isDestroyed();
    }

    private void readFailed(int epoch, String message) {
        if (!valid(epoch)) return;
        busyRead = false;
        connected = false;
        notice = message + "。按返回键可重新连接；不会自动重发";
        interruptRecording();
        renderState();
    }

    private final Runnable poll = () -> readPage(false);

    private void readPage(boolean older) {
        if (!foreground || busyRead || !connected || (older && !hasOlder)) return;
        final int epoch = connectionEpoch;
        final HostClient active = client;
        final boolean wasLive = live;
        final String cursor = older ? olderCursor : forwardCursor;
        final boolean refreshMeta = System.currentTimeMillis() >= nextMetaRead;
        busyRead = true;
        reads.execute(() -> {
            try {
                JSONObject current = refreshMeta ? active.meta() : null;
                if (current != null && current.getBoolean("loaded") != wasLive) {
                    main.post(() -> { if (valid(epoch)) connect(); });
                    return;
                }
                JSONObject page = (!wasLive && !older) ? null
                        : active.page(wasLive, older ? "backward" : "forward", cursor, false);
                main.post(() -> {
                    if (!valid(epoch)) return;
                    try {
                        if (current != null) { meta = current; nextMetaRead = System.currentTimeMillis() + 3000; }
                        if (page != null) {
                            boolean bottom = atBottom();
                            int oldHeight = conversation.getHeight(), oldY = scroll.getScrollY();
                            JSONArray events = page.getJSONArray("events");
                            projection.accept(events, older);
                            if (older) {
                                olderCursor = page.getString("cursor");
                                hasOlder = page.getBoolean("hasMore");
                            } else forwardCursor = page.getString("cursor");
                            if (events.length() > 0) {
                                renderMessages(!older && bottom);
                                if (older) scroll.post(() -> scroll.scrollTo(0,
                                        Math.max(0, oldY + conversation.getHeight() - oldHeight)));
                            }
                        }
                        busyRead = false;
                        renderState();
                        main.removeCallbacks(poll);
                        main.postDelayed(poll, 1200);
                    } catch (JSONException error) {
                        readFailed(epoch, "历史协议错误：" + safe(error));
                    }
                });
            } catch (IOException | JSONException error) {
                main.post(() -> readFailed(epoch, "读取中断：" + safe(error)));
            }
        });
    }

    private boolean atBottom() {
        return scroll.getScrollY() + scroll.getHeight() >= conversation.getHeight() - 100;
    }

    private void renderMessages(boolean bottom) {
        conversation.removeAllViews();
        for (ChatProjection.Message message : projection.items()) {
            if (message.text.isEmpty()) continue;
            TextView label = text(16, message.speaker.equals("你") ? Color.rgb(136, 191, 255) : Color.LTGRAY);
            label.setText(message.speaker + (message.complete ? "" : " · 回复中"));
            conversation.addView(label);
            TextView body = text(25, Color.WHITE);
            markdown.setMarkdown(body, message.text);
            // Navigation remains on the remote's chat surface; URLs stay readable.
            body.setFocusable(false);
            conversation.addView(body);
        }
        if (conversation.getChildCount() == 0) {
            TextView empty = text(26, Color.LTGRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setText("按住确定键说话\n松开预览 · 左键发送 · 右键取消");
            conversation.addView(empty);
        }
        conversation.addView(questionView);
        conversation.addView(draftView);
        if (bottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderState() {
        if (heading == null) return;
        heading.setText((meta == null ? "Cockpit Dashboard" : meta.optString("title", "Copilot"))
                + "    " + (connected ? "已连接" : "未连接"));
        String question = "";
        if (meta != null) {
            JSONObject ask = meta.optJSONObject("ask");
            if (ask != null) {
                question = ask.optString("question");
                JSONArray choices = ask.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    StringBuilder words = new StringBuilder();
                    for (int i = 0; i < choices.length(); i++) {
                        if (i > 0) words.append(" / ");
                        words.append(choices.optString(i));
                    }
                    question += "\n" + words;
                }
            } else if (meta.optJSONObject("planRequest") != null || meta.optJSONObject("elicitation") != null) {
                question = "当前会话需要在 Cockpit 处理计划或授权请求；本 App 不会自动批准。";
            }
        }
        questionView.setText(question);
        questionView.setVisibility(question.isEmpty() ? View.GONE : View.VISIBLE);
        draftView.setText(state.draft.isEmpty() ? "" : "未发送草稿\n" + state.draft);
        draftView.setVisibility(state.draft.isEmpty() ? View.GONE : View.VISIBLE);
        String hint;
        switch (state.phase) {
            case RECORDING: hint = capturing ? "● 正在采音，松开结束 · 右键取消" : "准备录音，请等待 USB 采音提示"; break;
            case TRANSCRIBING: hint = "正在转写，尚未发送 · 右键取消"; break;
            case DRAFT: hint = "未发送草稿 · 左键发送 / 右键取消"; break;
            case SENDING: hint = "正在发送一次，等待受理回执…"; break;
            case UNKNOWN: hint = "发送结果未知：请先在 Cockpit 查看，禁止重发 · 右键仅清除本地草稿"; break;
            default: hint = "按住确定键说话 · 上下滚动 · 返回键设置";
        }
        status.setText(hint + (notice.isEmpty() ? "" : "\n" + notice)
                + (receipt.isEmpty() ? "" : "\n" + receipt));
        status.setTextColor(capturing ? Color.rgb(255, 105, 105) : Color.LTGRAY);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (dialog) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showMenu();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_UP && scroll.getScrollY() == 0 && event.getRepeatCount() == 0) readPage(true);
                scroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * scroll.getHeight() / 3);
            }
            return true;
        }
        if (key == 23 || key == 66 || key == 160 || key == 21 || key == 22) {
            RemoteState.Action action = event.getAction() == KeyEvent.ACTION_DOWN
                    ? state.down(key, event.getRepeatCount()) : event.isCanceled() ? RemoteState.Action.NONE : state.up(key);
            if (event.isCanceled()) interruptRecording();
            switch (action) {
                case START: startRecording(); break;
                case STOP:
                    if (!capturing) {
                        interruptRecording();
                        notice = "USB 采音尚未就绪，本次已取消；请等录音提示再说话";
                    } else {
                        capturing = false;
                        if (capture != null) capture.stop();
                    }
                    break;
                case SEND: send(); break;
                case DISCARD: discard(); break;
                default: break;
            }
            renderState();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void startRecording() {
        if (!foreground || !connected || storageFailed || meta == null) {
            state.clear(); notice = "请先完成连接，未打开麦克风"; return;
        }
        JSONArray decisions = meta.optJSONArray("decisions");
        if (meta.optJSONObject("planRequest") != null || meta.optJSONObject("elicitation") != null
                || (decisions != null && decisions.length() > 1)) {
            state.clear(); notice = "当前原生待决状态需在 Cockpit 处理，不会猜测或自动批准"; return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.clear();
            notice = "请允许麦克风权限，然后重新按住确定键";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        target = meta.optJSONObject("ask");
        capturing = false;
        notice = "正在打开 USB 麦克风，请等采音提示";
        receipt = "";
        final int epoch = ++recordingEpoch;
        capture = new AudioCapture(this, new AudioCapture.Listener() {
            @Override public void onStarted(String device) {
                if (recordingValid(epoch) && state.phase == RemoteState.Phase.RECORDING) {
                    capturing = true; notice = "USB 麦克风：" + device; renderState();
                }
            }
            @Override public void onComplete(byte[] pcm) {
                if (!recordingValid(epoch)) { Arrays.fill(pcm, (byte) 0); return; }
                state.phase = RemoteState.Phase.TRANSCRIBING;
                capturing = false;
                capture = null;
                renderState();
                transcribe(epoch, pcm);
            }
            @Override public void onError(String message) {
                if (recordingValid(epoch)) { capturing = false; state.clear(); target = null; notice = message; renderState(); }
            }
        });
        capture.start();
    }

    private boolean recordingValid(int epoch) {
        return foreground && epoch == recordingEpoch && !isDestroyed()
                && (state.phase == RemoteState.Phase.RECORDING || state.phase == RemoteState.Phase.TRANSCRIBING);
    }

    private void transcribe(int epoch, byte[] pcm) {
        HostClient owner = client;
        pendingAudio = pcm;
        writes.execute(() -> {
            try {
                JSONObject credential = owner.speechCredential();
                main.post(() -> {
                    if (!recordingValid(epoch)) { Arrays.fill(pcm, (byte) 0); return; }
                    speech = new SpeechTranscriber(owner.http, new SpeechTranscriber.Listener() {
                        @Override public void onComplete(String text) {
                            if (!recordingValid(epoch)) return;
                            speech = null;
                            state.transcript(text);
                            notice = text.trim().isEmpty() ? "未识别到文字，未发送" : "";
                            persist();
                            renderState();
                            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                        }
                        @Override public void onError(String message) {
                            if (!recordingValid(epoch)) return;
                            speech = null;
                            state.clear();
                            target = null;
                            notice = message + "；未发送，请重新录音";
                            renderState();
                        }
                    });
                    speech.start(credential, pcm);
                    pendingAudio = null;
                });
            } catch (IOException | JSONException error) {
                Arrays.fill(pcm, (byte) 0);
                main.post(() -> {
                    if (!recordingValid(epoch)) return;
                    state.clear(); target = null;
                    notice = "获取转写凭证失败：" + safe(error) + "；未发送";
                    renderState();
                });
            }
        });
    }

    private void interruptRecording() {
        if (state.phase != RemoteState.Phase.RECORDING && state.phase != RemoteState.Phase.TRANSCRIBING) return;
        recordingEpoch++;
        capturing = false;
        if (pendingAudio != null) Arrays.fill(pendingAudio, (byte) 0);
        pendingAudio = null;
        if (capture != null) capture.cancel();
        if (speech != null) speech.cancel();
        capture = null; speech = null;
        state.interrupt();
        target = null;
        notice = "录音或转写已因离开前台／失去焦点中止，未发送";
        renderState();
    }

    private void send() {
        if (!connected || storageFailed || client == null) {
            state.phase = RemoteState.Phase.DRAFT;
            notice = "未连接或本地存储不可用，未发送";
            return;
        }
        if (!persist()) { state.phase = RemoteState.Phase.DRAFT; return; }
        HostClient owner = client;
        String text = state.draft;
        JSONObject answerTarget = target;
        writes.execute(() -> {
            try {
                JSONObject result = owner.send(text, answerTarget);
                main.post(() -> {
                    if (isDestroyed()) return;
                    state.clear(); target = null;
                    receipt = result.optBoolean("queued") ? "已进入原生队列，尚不代表处理完成"
                            : "已受理" + (result.has("messageId") ? " · " + result.optString("messageId") : "");
                    notice = "";
                    persist();
                    renderState();
                });
            } catch (HostClient.Rejected error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    state.phase = RemoteState.Phase.DRAFT;
                    notice = safe(error);
                    persist(); renderState();
                });
            } catch (IOException | JSONException error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    state.phase = RemoteState.Phase.UNKNOWN;
                    notice = "没有可靠回执，可能已经发送；请在原会话核对";
                    persist(); renderState();
                });
            }
        });
    }

    private void discard() {
        if (state.phase == RemoteState.Phase.UNKNOWN) {
            dialog = true;
            new AlertDialog.Builder(this).setMessage("这条消息可能已经发送。清除只删除本地草稿，不撤回消息，也不重发。")
                    .setPositiveButton("仅清除本地", (d, which) -> clearDraft())
                    .setNegativeButton("保留", null).setOnDismissListener(d -> { dialog = false; renderState(); }).show();
        } else clearDraft();
    }

    private void clearDraft() {
        recordingEpoch++;
        capturing = false;
        if (pendingAudio != null) Arrays.fill(pendingAudio, (byte) 0);
        pendingAudio = null;
        if (capture != null) capture.cancel();
        if (speech != null) speech.cancel();
        capture = null; speech = null;
        state.clear(); target = null;
        notice = "已取消本次草稿；不会取消已发送的任务";
        persist();
        renderState();
    }

    private void showMenu() {
        if (dialog) return;
        interruptRecording();
        dialog = true;
        new AlertDialog.Builder(this).setTitle("Cockpit Dashboard")
                .setItems(new String[]{"返回聊天", "重新连接（不重发）", "连接设置", "退出"},
                        (d, which) -> {
                            if (which == 1) connect();
                            if (which == 2) main.post(this::openSettings);
                            if (which == 3) finish();
                        }).setOnDismissListener(d -> dialog = false).show();
    }

    private EditText input(LinearLayout form, String label, String value, boolean secret) {
        TextView caption = text(16, Color.LTGRAY);
        caption.setText(label);
        form.addView(caption);
        EditText edit = new EditText(this);
        edit.setSingleLine();
        edit.setText(value);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        form.addView(edit);
        return edit;
    }

    private void openSettings() {
        if (dialog || !foreground || storageFailed) return;
        if (state.phase != RemoteState.Phase.IDLE) {
            notice = "请先处理或取消当前草稿，再更换连接；不会把旧草稿发到新会话";
            renderState(); return;
        }
        dialog = true;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 8, 24, 8);
        EditText address = input(form, "Cockpit HTTPS 根地址", settings.optString("address"), false);
        EditText session = input(form, "已有常规 session ID（从 Cockpit 会话链接取得）", settings.optString("sessionId"), false);
        EditText authorization = input(form, "网关 Authorization（Basic/Bearer；可留空，不是 GitHub token）",
                settings.optString("authorization"), true);
        TextView info = text(15, Color.LTGRAY);
        info.setText("仅连接这一个 session；不创建、管理或切换其他会话。\n录音经现有 Speech/Azure 转写并产生费用；不会自动发送。\n"
                + "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + " · USB 实际采音需本机验证");
        form.addView(info);
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        AlertDialog box = new AlertDialog.Builder(this).setTitle("首次连接 / 设置")
                .setView(formScroll).setPositiveButton("保存并连接", null)
                .setNegativeButton("取消", null).create();
        box.setOnDismissListener(d -> { dialog = false; fullscreen(); });
        box.setOnShowListener(d -> {
            box.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String url = address.getText().toString().trim();
                    String id = session.getText().toString().trim();
                    String auth = authorization.getText().toString().trim();
                    HostClient.validateSettings(url, id, auth);
                    settings.put("address", url).put("sessionId", id).put("authorization", auth);
                    meta = null; receipt = "";
                    if (persist()) { box.dismiss(); connect(); }
                } catch (JSONException | IllegalArgumentException error) { info.setText(safe(error)); }
            });
        });
        box.show();
    }

    private static String safe(Exception error) {
        if (error instanceof HostClient.Rejected || error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            return message == null ? "请求失败" : message.substring(0, Math.min(message.length(), 300));
        }
        return error instanceof JSONException ? "公开 API 响应格式不兼容" : "网络或服务不可用";
    }
}
