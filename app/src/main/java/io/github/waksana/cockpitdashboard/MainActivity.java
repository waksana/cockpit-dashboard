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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private final RemoteState state = new RemoteState();
    private final ChatProjection projection = new ChatProjection();
    private final MarkdownNavigation markdownNavigation = new MarkdownNavigation();
    private PrivateStore store;
    private JSONObject settings = new JSONObject();
    private JSONObject meta;
    private JSONObject target;
    private HostClient client;
    private AudioCapture capture;
    private SpeechTranscriber speech;
    private PasskeyLogin login;
    private DeviceLogin deviceLogin;
    private DeviceCredentials deviceCredentials;
    private boolean deviceAvailable, revoking;
    private AlertDialog navigation;
    private AtomicBoolean directoryCancellation;
    private AppUpdater updater;
    private boolean checkedForUpdates;
    private String updateNotice = "";
    private ScrollView scroll;
    private LinearLayout conversation;
    private TextView heading, status, draftView, questionView;
    private LinearLayout draftActions;
    private Button cancelDraftButton, sendDraftButton;
    private ActiveQuestionView activeQuestion;
    private int previewKey = -1;
    private String previewRequest = "", previewAnswer = "";
    private ChatMarkdown markdown;
    private boolean foreground, dialog, connected, busyRead, live, hasOlder, storageFailed;
    private boolean capturing;
    private boolean loginFailurePendingResume;
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
        deviceCredentials = new DeviceCredentials(this);
        try {
            settings = store.read();
            state.restore(settings.optString("draft"), settings.optBoolean("uncertain"));
            target = settings.optJSONObject("target");
            receipt = settings.optString("receipt");
            if (!settings.optString("address").isEmpty()) {
                deviceAvailable = deviceCredentials.present(settings.getString("address"));
                if (deviceAvailable && (settings.has("gateSession") || settings.has("authorization"))) {
                    settings.remove("gateSession");
                    settings.remove("authorization");
                    store.write(settings);
                }
            }
        } catch (GeneralSecurityException | IOException | JSONException | IllegalArgumentException error) {
            storageFailed = true;
            notice = "无法解密本地配置，已禁止发送。请在 Android 设置中清除本 App 数据后重新配置。";
        }
        createViews();
        updater = new AppUpdater(this, new AppUpdater.Listener() {
            @Override public boolean isReady() {
                return foreground && !dialog && login == null && deviceLogin == null && !revoking && !storageFailed
                        && state.phase == RemoteState.Phase.IDLE;
            }
            @Override public void status(String message) {
                recordUpdateDiagnostic(message);
                updateNotice = "";
                renderState();
            }
            @Override public void failure(String message) {
                recordUpdateDiagnostic(message);
                updateNotice = "应用更新失败，详情见设置「操作说明与诊断」";
                renderState();
            }
        });
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(12, 8, 12, 8);
        return view;
    }

    private void createViews() {
        markdown = new ChatMarkdown(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 16, 28, 16);
        root.setBackgroundColor(Color.rgb(15, 20, 29));
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        heading = text(18, Color.LTGRAY);
        root.addView(heading);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(conversation);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        questionView = text(24, Color.rgb(254, 214, 123));
        activeQuestion = new ActiveQuestionView(this, this::previewChoice);
        draftView = text(26, Color.WHITE);
        draftView.setOnClickListener(v -> editDraft());
        draftActions = new LinearLayout(this);
        cancelDraftButton = new Button(this);
        cancelDraftButton.setText("取消");
        cancelDraftButton.setOnClickListener(v -> {
            if (state.down(KeyEvent.KEYCODE_DPAD_LEFT, 0) == RemoteState.Action.DISCARD) discard();
            renderState();
        });
        sendDraftButton = new Button(this);
        sendDraftButton.setText("发送");
        sendDraftButton.setOnClickListener(v -> {
            if (state.down(KeyEvent.KEYCODE_DPAD_RIGHT, 0) == RemoteState.Action.SEND) send();
            renderState();
        });
        draftActions.addView(cancelDraftButton, new LinearLayout.LayoutParams(0, -2, 1));
        draftActions.addView(sendDraftButton, new LinearLayout.LayoutParams(0, -2, 1));
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
        if (login != null) {
            login.resume();
        } else if (loginFailurePendingResume) {
            loginFailurePendingResume = false;
            renderState();
        } else connect();
        updater.resume();
        if (!checkedForUpdates) {
            checkedForUpdates = true;
            updater.check(false);
        }
    }

    @Override protected void onPause() {
        foreground = false;
        cancelPendingConfirm();
        markdownNavigation.clear();
        cancelDirectory();
        if (updater != null) updater.pause();
        if (navigation != null) navigation.dismiss();
        if (login != null) login.pause();
        if (deviceLogin != null) deviceLogin.cancel("已离开前台，扫码登录停止");
        connectionEpoch++;
        busyRead = false;
        connected = false;
        main.removeCallbacks(poll);
        interruptRecording();
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelPendingConfirm();
        cancelDirectory();
        recordingEpoch++;
        connectionEpoch++;
        if (login != null) login.cancel();
        if (deviceLogin != null) deviceLogin.cancel("登录窗口已关闭");
        if (updater != null) updater.destroy();
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
        if (!focus) { cancelPendingConfirm(); markdownNavigation.clear(); interruptRecording(); }
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
        if (!foreground || storageFailed || login != null || deviceLogin != null || revoking || dialog) return;
        if (settings.optString("address").isEmpty()) {
            openSettings();
            return;
        }
        if (settings.optString("sessionId").isEmpty()) {
            if (deviceAvailable || settings.optJSONObject("gateSession") != null || !settings.optString("authorization").isEmpty()) {
                chooseSession();
            } else showWelcome();
            return;
        }
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
                    settings.optString("authorization"), GateSession.fromJSON(settings.optJSONObject("gateSession")),
                    deviceAvailable ? deviceCredentials : null);
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
        markdownNavigation.clear();
        cancelPendingConfirm();
        conversation.removeAllViews();
        for (ChatProjection.Message message : projection.items()) {
            if (message.text.isEmpty()) continue;
            TextView label = text(16, message.speaker.equals("你") ? Color.rgb(136, 191, 255) : Color.LTGRAY);
            label.setText(message.askStatus != null ? "历史问题 · " + askStatus(message.askStatus)
                    : message.askReply ? "你 · 问题回复" : message.speaker + (message.complete ? "" : " · 回复中"));
            conversation.addView(label);
            if (message.askReply && (message.question == null || message.question.isEmpty())) {
                TextView unavailable = text(18, Color.LTGRAY);
                unavailable.setText("原问题记录不可用");
                conversation.addView(unavailable);
            }
            conversation.addView(markdown.render(message.text));
            for (int i = 0; i < message.choices.size(); i++) {
                TextView choice = text(23, Color.LTGRAY);
                choice.setText((i + 1) + ". " + message.choices.get(i));
                conversation.addView(choice);
            }
        }
        if (conversation.getChildCount() == 0) {
            TextView empty = text(26, Color.LTGRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setText("暂无聊天内容");
            conversation.addView(empty);
        }
        conversation.addView(questionView);
        conversation.addView(activeQuestion);
        conversation.addView(draftView);
        conversation.addView(draftActions);
        if (bottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String askStatus(ChatProjection.AskStatus status) {
        switch (status) {
            case ANSWERED: return "已回答";
            case FAILED: return "失败，未记录有效答案";
            case CANCELLED: return "已取消";
            case DISMISSED: return "已关闭";
            case EXPIRED: return "已过期";
            case UNKNOWN: return "结果未知";
            default: return "未记录结果，仅供查看";
        }
    }

    private void renderState() {
        if (heading == null) return;
        heading.setText((meta == null ? "Cockpit Dashboard" : meta.optString("title", "Copilot"))
                + "    " + (connected ? "已连接" : "未连接"));
        String question = "";
        if (PendingDecision.requiresCockpit(meta)) {
            question = "当前会话的待决请求需在 Cockpit 处理；本 App 不会猜测或自动批准。";
        }
        activeQuestion.update(connected ? PendingDecision.ask(meta) : null);
        questionView.setText(question);
        questionView.setVisibility(question.isEmpty() ? View.GONE : View.VISIBLE);
        draftView.setText(state.draft.isEmpty() ? "" : "未发送草稿（点击编辑）\n" + state.draft);
        draftView.setVisibility(state.draft.isEmpty() ? View.GONE : View.VISIBLE);
        draftActions.setVisibility(state.phase == RemoteState.Phase.IDLE ? View.GONE : View.VISIBLE);
        cancelDraftButton.setEnabled(state.phase != RemoteState.Phase.SENDING);
        cancelDraftButton.setText(state.phase == RemoteState.Phase.RECORDING
                || state.phase == RemoteState.Phase.TRANSCRIBING ? "取消本段" : "取消草稿");
        sendDraftButton.setEnabled(state.phase == RemoteState.Phase.DRAFT && connected && !storageFailed);
        String hint;
        switch (state.phase) {
            case RECORDING: hint = capturing ? "● 正在录音" : "准备录音"; break;
            case TRANSCRIBING: hint = "正在转写，尚未发送"; break;
            case DRAFT: hint = "草稿未发送"; break;
            case SENDING: hint = "正在发送一次，等待受理回执…"; break;
            case UNKNOWN: hint = "发送结果未知，请在 Cockpit 核对，禁止重发"; break;
            default: hint = sessionStatus();
        }
        status.setText(hint + (notice.isEmpty() ? "" : "\n" + notice)
                + (updateNotice.isEmpty() ? "" : "\n" + updateNotice));
        status.setTextColor(capturing ? Color.rgb(255, 105, 105) : Color.LTGRAY);
        if (updater != null) main.post(updater::presentIfReady);
    }

    private String sessionStatus() {
        if (!connected || meta == null) return "未连接";
        if (PendingDecision.ask(meta) != null) return "等待回答";
        if (PendingDecision.requiresCockpit(meta)) return "等待处理";
        if (meta.optBoolean("compacting")) return "正在压缩上下文";
        if ("error".equals(meta.optString("status"))) return "会话异常，请在 Cockpit 查看";
        JSONObject activity = meta.optJSONObject("activity");
        if ("running".equals(meta.optString("status")) || (activity != null
                && (activity.optBoolean("processing") || activity.optBoolean("hasActiveWork")))) return "运行中";
        if (activity != null) {
            JSONObject queue = activity.optJSONObject("queue");
            if (queue != null && (queue.optInt("pendingCount") > 0
                    || queue.optInt("steeringCount") > queue.optInt("inFlightSteeringCount"))) return "等待处理";
        }
        if (meta.has("activity") && activity == null) return "已连接 · 活动状态暂不可用";
        return "已连接";
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (updater != null && updater.isPresenting()) return super.dispatchKeyEvent(event);
        if (revoking) return true;
        if (deviceLogin != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) deviceLogin.cancel("已取消扫码登录");
            return true;
        }
        if (login != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                login.cancel();
                login = null;
                notice = "已取消登录；不会自动重试";
                renderState();
            }
            return true;
        }
        if (dialog) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK && markdownNavigation.active()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                markdownNavigation.clear();
                cancelPendingConfirm();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showMenu();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (previewKey != -1) return true;
                if (markdownNavigation.move(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)) return true;
                if (state.phase == RemoteState.Phase.IDLE || state.phase == RemoteState.Phase.DRAFT) {
                    boolean selected = activeQuestion.selectedChoice() != null;
                    if (selected || atBottom()) {
                        int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        if (activeQuestion.move(direction)) return true;
                        if (selected && direction > 0) return true;
                        if (selected) activeQuestion.clearSelection();
                    }
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP && scroll.getScrollY() == 0 && event.getRepeatCount() == 0) readPage(true);
                scroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * scroll.getHeight() / 3);
            }
            return true;
        }
        if (key == 23 || key == 66 || key == 160 || key == 21 || key == 22) {
            if ((key == 21 || key == 22) && state.phase == RemoteState.Phase.IDLE
                    && markdownNavigation.active()) {
                if (event.getAction() == KeyEvent.ACTION_UP) return true;
                if (markdownNavigation.horizontal(key == 21 ? -1 : 1)) return true;
            }
            if (key == 21) cancelPendingConfirm();
            if (key == 22 && previewKey != -1) return true;
            if (handleChoiceConfirm(event)) return true;
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
            if (action == RemoteState.Action.NONE && (key == 21 || key == 22)
                    && state.phase == RemoteState.Phase.IDLE) return super.dispatchKeyEvent(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleChoiceConfirm(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != 23 && key != 66 && key != 160) return false;
        if (previewKey == key) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                String request = previewRequest, answer = previewAnswer;
                cancelPendingConfirm();
                if (!event.isCanceled() && foreground && !dialog && !storageFailed) {
                    if (!request.isEmpty()) previewChoice(request, answer);
                    else if (state.phase == RemoteState.Phase.IDLE) markdownNavigation.confirm(conversation);
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (state.phase == RemoteState.Phase.IDLE || state.phase == RemoteState.Phase.DRAFT)) {
            previewKey = key;
            boolean choice = !markdownNavigation.active() && activeQuestion.selectedChoice() != null;
            previewRequest = choice ? activeQuestion.requestId() : "";
            previewAnswer = choice ? activeQuestion.selectedChoice() : "";
            main.postDelayed(recordAfterHold, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        return false;
    }

    private final Runnable recordAfterHold = () -> {
        int key = previewKey;
        boolean current = previewRequest.isEmpty() || previewRequest.equals(activeQuestion.requestId());
        cancelPendingConfirm();
        markdownNavigation.clear();
        if (key != -1 && current && foreground && !dialog && !storageFailed
                && state.down(key, 0) == RemoteState.Action.START) {
            startRecording();
            renderState();
        }
    };

    private void cancelPendingConfirm() {
        main.removeCallbacks(recordAfterHold);
        previewKey = -1;
        previewRequest = "";
        previewAnswer = "";
    }

    private void previewChoice(String requestId, String choice) {
        if (!foreground || !connected || storageFailed || dialog
                || (state.phase != RemoteState.Phase.IDLE && state.phase != RemoteState.Phase.DRAFT)) return;
        JSONObject ask = PendingDecision.ask(meta);
        if (ask == null || !requestId.equals(ask.optString("requestId"))) return;
        JSONArray choices = ask.optJSONArray("choices");
        boolean validChoice = false;
        if (choices != null) for (int i = 0; i < choices.length(); i++) {
            if (choice.equals(choices.optString(i))) validChoice = true;
        }
        if (!validChoice) return;
        state.transcript(choice);
        target = ask;
        notice = "";
        persist();
        renderState();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void startRecording() {
        if (!foreground || !connected || storageFailed || meta == null) {
            state.interrupt(); notice = "请先完成连接，未打开麦克风"; return;
        }
        if (PendingDecision.requiresCockpit(meta)) {
            state.interrupt(); notice = "当前原生待决状态需在 Cockpit 处理，不会猜测或自动批准"; return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.interrupt();
            notice = "请允许麦克风权限，然后重新按住确定键";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (state.draft.trim().isEmpty()) target = PendingDecision.ask(meta);
        capturing = false;
        notice = "正在打开 USB 麦克风，请等采音提示";
        receipt = "";
        final int epoch = ++recordingEpoch;
        capture = new AudioCapture(this, new AudioCapture.Listener() {
            @Override public void onStarted(String device) {
                if (recordingValid(epoch) && state.phase == RemoteState.Phase.RECORDING) {
                    capturing = true; notice = ""; renderState();
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
                if (recordingValid(epoch)) {
                    interruptRecording();
                    notice = message;
                    renderState();
                }
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
                            if (!state.finishSegment(text)) return;
                            if (state.draft.trim().isEmpty()) target = null;
                            notice = text.trim().isEmpty() ? "未识别到文字，未发送" : "";
                            persist();
                            renderState();
                            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                        }
                        @Override public void onError(String message) {
                            if (!recordingValid(epoch)) return;
                            speech = null;
                            interruptRecording();
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
                    interruptRecording();
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
        if (state.draft.trim().isEmpty()) target = null;
        notice = "本段录音或转写已中止，原有草稿保留，未发送";
        persist();
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
        if (state.phase == RemoteState.Phase.RECORDING || state.phase == RemoteState.Phase.TRANSCRIBING) {
            interruptRecording();
        } else if (state.phase == RemoteState.Phase.UNKNOWN) {
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

    private void editDraft() {
        if (!foreground || dialog || storageFailed || state.phase != RemoteState.Phase.DRAFT) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(state.draft);
        AlertDialog box = new AlertDialog.Builder(this).setTitle("编辑草稿")
                .setView(input).setPositiveButton("保存草稿", (d, w) -> {
                    state.transcript(input.getText().toString());
                    if (state.draft.trim().isEmpty()) target = null;
                    persist();
                }).setNegativeButton("返回", null).create();
        showNavigation(box, new Runnable[1], this::renderState);
    }

    private void showMenu() {
        if (dialog) return;
        cancelPendingConfirm();
        interruptRecording();
        Runnable[] after = new Runnable[1];
        AlertDialog box = new AlertDialog.Builder(this).setTitle("Cockpit Dashboard")
                .setItems(new String[]{"返回聊天", "重新连接（不重发）", "选择会话", "高级连接设置",
                        "扫码登录（手机批准）", "退出登录（仅本机）", "检查应用更新", "关闭 App（保留登录）",
                        "撤销此设备的服务端授权", "Passkey 登录（高级原生兼容）", "操作说明与诊断"},
                        (d, which) -> {
                            if (which == 1) after[0] = this::connect;
                            if (which == 2) after[0] = this::chooseSession;
                            if (which == 3) after[0] = this::openSettings;
                            if (which == 4) after[0] = this::startDeviceLogin;
                            if (which == 5) after[0] = this::confirmLogout;
                            if (which == 6) after[0] = () -> updater.check(true);
                            if (which == 7) after[0] = this::finish;
                            if (which == 8) after[0] = this::confirmRevoke;
                            if (which == 9) after[0] = this::startLogin;
                            if (which == 10) after[0] = this::showHelp;
                        }).create();
        showNavigation(box, after, () -> {});
    }

    private void recordUpdateDiagnostic(String message) {
        if (storageFailed) return;
        JSONArray prior = settings.optJSONArray("updateDiagnostics");
        JSONArray bounded = new JSONArray();
        if (prior != null) {
            for (int i = Math.max(0, prior.length() - 15); i < prior.length(); i++) {
                String entry = prior.optString(i);
                bounded.put(entry.substring(0, Math.min(entry.length(), 600)));
            }
        }
        bounded.put(message.substring(0, Math.min(message.length(), 600)));
        try {
            settings.put("updateDiagnostics", bounded);
            persist();
        } catch (JSONException error) {
            storageFailed = true;
            notice = "诊断信息无法保存，已禁止发送";
        }
    }

    private void showHelp() {
        StringBuilder contents = new StringBuilder("操作说明\n"
                + "长按确定：录音；松开：转写为草稿，不自动发送。\n"
                + "有草稿时再次录音：追加一段；失败、空结果或取消本段保留原稿。\n"
                + "左键：取消本段录音或当前草稿；右键：发送草稿一次。\n"
                + "上下键：滚动聊天或选择当前问题选项；短按确定：预览选项答案。\n"
                + "正文中短按确定：进入链接/表格阅读；上下选择，短按确定打开链接，左右滚动表格，返回退出阅读。\n"
                + "点击草稿可编辑；历史问题不能再次提交。\n\n应用更新诊断（最近 16 条）\n");
        JSONArray logs = settings.optJSONArray("updateDiagnostics");
        if (logs == null || logs.length() == 0) contents.append("暂无诊断");
        else for (int i = logs.length() - 1; i >= Math.max(0, logs.length() - 16); i--) {
            String entry = logs.optString(i);
            contents.append(entry, 0, Math.min(entry.length(), 600)).append("\n\n");
        }
        TextView body = text(18, Color.LTGRAY);
        body.setText(contents.toString());
        ScrollView container = new ScrollView(this);
        container.addView(body);
        AlertDialog box = new AlertDialog.Builder(this).setTitle("操作说明与诊断")
                .setView(container).setPositiveButton("返回", null).create();
        showNavigation(box, new Runnable[1], () -> {});
    }

    private void showNavigation(AlertDialog box, Runnable[] after, Runnable cleanup) {
        cancelPendingConfirm();
        markdownNavigation.clear();
        navigation = box;
        dialog = true;
        box.setOnDismissListener(d -> {
            // Android dispatches dismissal asynchronously: an old dialog must not clear its replacement.
            if (navigation != box) return;
            navigation = null;
            dialog = false;
            cleanup.run();
            if (foreground && !isDestroyed() && after[0] != null) after[0].run();
        });
        box.show();
    }

    private void showWelcome() {
        if (dialog || !foreground || storageFailed) return;
        Runnable[] after = new Runnable[1];
        AlertDialog box = new AlertDialog.Builder(this).setTitle("登录并选择会话")
                .setMessage(settings.optString("address") + "\n已保存地址，无需输入会话 ID。\n"
                        + "先扫码登录，再用遥控器选择 Assistant 会话。\n手机使用 Passkey 批准，电视无需凭据提供器。")
                .setPositiveButton("扫码登录", (d, w) -> after[0] = this::startDeviceLogin)
                .setNeutralButton("高级设置", (d, w) -> after[0] = this::openSettings)
                .setNegativeButton("稍后", null).create();
        showNavigation(box, after, () -> {});
    }

    private boolean canSwitchSession() {
        if (state.phase != RemoteState.Phase.IDLE) {
            notice = "请先处理或取消当前草稿；发送中或结果未知时不能更换会话";
            renderState();
            return false;
        }
        return true;
    }

    private void chooseSession() {
        if (!foreground || storageFailed || dialog || login != null || deviceLogin != null || revoking
                || !canSwitchSession()) return;
        loadDirectory(new ArrayList<>(), null);
    }

    private void loadDirectory(List<String> previous, String cursor) {
        if (!foreground || storageFailed || !canSwitchSession()) return;
        disconnect();
        final int epoch = connectionEpoch;
        busyRead = true;
        notice = "正在查找 Assistant 角色会话…";
        renderState();
        final HostClient directoryClient;
        try {
            String address = settings.getString("address");
            HostClient.validateSettings(address, "directory", settings.optString("authorization"));
            directoryClient = new HostClient(address, "", settings.optString("authorization"),
                    GateSession.fromJSON(settings.optJSONObject("gateSession")), deviceAvailable ? deviceCredentials : null);
        } catch (JSONException | IllegalArgumentException error) {
            busyRead = false;
            notice = "无法读取会话列表：" + safe(error);
            renderState();
            return;
        }
        Runnable[] after = new Runnable[1];
        AtomicBoolean cancelled = new AtomicBoolean();
        directoryCancellation = cancelled;
        AlertDialog loading = new AlertDialog.Builder(this).setTitle("正在读取会话")
                .setMessage("只显示 Assistant（assistant/coordinator）会话，自动跳过不匹配的目录页，不会加载会话。")
                .setNegativeButton("取消", (d, w) -> cancelled.set(true)).create();
        loading.setOnCancelListener(d -> cancelled.set(true));
        showNavigation(loading, after, () -> {
            cancelled.set(true);
            if (directoryCancellation == cancelled) directoryCancellation = null;
            connectionEpoch++;
            busyRead = false;
        });
        reads.execute(() -> {
            try {
                SessionDirectory page = directoryClient.directory(cursor, cancelled::get);
                main.post(() -> {
                    if (cancelled.get() || !valid(epoch)) return;
                    after[0] = () -> showDirectory(page, previous, cursor);
                    loading.dismiss();
                });
            } catch (IOException | JSONException error) {
                main.post(() -> {
                    if (cancelled.get() || !valid(epoch)) return;
                    after[0] = () -> showDirectoryError(error);
                    loading.dismiss();
                });
            }
        });
    }

    private void showDirectoryError(Exception error) {
        notice = "会话列表读取失败：" + safe(error);
        renderState();
        Runnable[] after = new Runnable[1];
        AlertDialog box = new AlertDialog.Builder(this).setTitle("无法读取会话")
                .setMessage(notice + "\n列表变化可重新读取；需要登录时请选择扫码登录。")
                .setPositiveButton(error instanceof HostClient.AuthenticationRequired ? "扫码登录" : "重新读取",
                        (d, w) -> after[0] = error instanceof HostClient.AuthenticationRequired
                                ? this::startDeviceLogin : this::chooseSession)
                .setNegativeButton("返回", null).create();
        showNavigation(box, after, () -> {});
    }

    private void showDirectory(SessionDirectory page, List<String> previous, String cursor) {
        if (!foreground || storageFailed) return;
        Runnable[] after = new Runnable[1];
        String[] labels = new String[page.entries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = page.entries.get(i).label(settings.optString("sessionId"));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Assistant 会话 · 第 " + (previous.size() + 1) + " 页")
                .setNegativeButton("返回聊天", (d, w) -> after[0] = () -> {
                    if (!settings.optString("sessionId").isEmpty()) connect();
                });
        if (labels.length == 0) builder.setMessage(page.next != null
                ? "已跳过多页不匹配会话，目录尚未查完，请继续查找。"
                : previous.isEmpty()
                        ? "未找到配置 Assistant（assistant/coordinator）角色的会话。"
                        : "后续没有更多 Assistant 会话，可返回上一页。");
        else builder.setItems(labels, (d, which) -> after[0] = () -> selectSession(page.entries.get(which)));
        if (page.next != null) {
            builder.setPositiveButton(labels.length == 0 ? "继续查找" : "下一页", (d, w) -> {
                List<String> history = new ArrayList<>(previous);
                history.add(cursor);
                after[0] = () -> loadDirectory(history, page.next);
            });
        }
        if (!previous.isEmpty()) {
            builder.setNeutralButton("上一页", (d, w) -> {
                List<String> history = new ArrayList<>(previous);
                String back = history.remove(history.size() - 1);
                after[0] = () -> loadDirectory(history, back);
            });
        }
        showNavigation(builder.create(), after, () -> {});
    }

    private void selectSession(SessionDirectory.Entry selected) {
        if (!foreground || storageFailed || !canSwitchSession()) return;
        disconnect();
        try {
            settings.put("sessionId", selected.id);
            meta = null;
            target = null;
            receipt = "";
            projection.clear();
            renderMessages(false);
            if (persist()) connect();
        } catch (JSONException error) {
            storageFailed = true;
            notice = "所选会话无法保存，已禁止发送";
            renderState();
        }
    }

    private boolean authenticationAvailable() {
        if (!foreground || storageFailed || login != null || deviceLogin != null || revoking
                || state.phase == RemoteState.Phase.SENDING || state.phase == RemoteState.Phase.RECORDING
                || state.phase == RemoteState.Phase.TRANSCRIBING) {
            notice = "当前不能变更登录；请等待发送回执或恢复前台";
            renderState();
            return false;
        }
        return true;
    }

    private void disconnect() {
        cancelPendingConfirm();
        markdownNavigation.clear();
        cancelDirectory();
        connectionEpoch++;
        connected = false;
        busyRead = false;
        main.removeCallbacks(poll);
        client = null;
    }

    private void cancelDirectory() {
        if (directoryCancellation != null) directoryCancellation.set(true);
        directoryCancellation = null;
    }

    private void startDeviceLogin() {
        if (!authenticationAvailable()) return;
        if (settings.optString("address").isEmpty()) {
            openSettings();
            return;
        }
        final String address = settings.optString("address");
        disconnect();
        notice = "正在申请扫码登录；返回键取消。失败不会自动重新申请。";
        renderState();
        deviceLogin = new DeviceLogin(this, new DeviceOAuth(address), new DeviceLogin.Listener() {
            @Override public void complete(DeviceOAuth.Tokens tokens) {
                deviceLogin = null;
                try {
                    deviceCredentials.install(address, tokens);
                    deviceAvailable = true;
                    settings.remove("gateSession");
                    settings.remove("authorization");
                    if (persist()) {
                        notice = "设备登录已加密保存；关闭 App 不退出，服务端最长授权期限由网关控制";
                        renderState();
                        if (state.phase == RemoteState.Phase.IDLE) chooseSession();
                        else connect();
                    }
                } catch (IOException | JSONException error) {
                    storageFailed = true;
                    notice = "新设备凭据无法可靠保存，已禁止发送；请在 Auth 页检查或撤销设备";
                    renderState();
                }
            }
            @Override public void failed(String message) {
                deviceLogin = null;
                loginFailurePendingResume = !foreground;
                notice = message;
                renderState();
            }
        });
        deviceLogin.start();
    }

    private void startLogin() {
        if (!authenticationAvailable()) return;
        if (settings.optString("address").isEmpty()) {
            openSettings();
            return;
        }
        try {
            GateApi gate = new GateApi(settings.getString("address"));
            disconnect();
            notice = "正在请求原生 Passkey；系统支持时可选择其他手机并扫码。返回键取消。\n"
                    + "首次使用需网关 App 白名单与域名关联；API " + Build.VERSION.SDK_INT;
            renderState();
            login = new PasskeyLogin(this, gate, new PasskeyLogin.Listener() {
                @Override public void complete(GateSession session) {
                    login = null;
                    try {
                        deviceCredentials.clear();
                        deviceAvailable = false;
                        settings.put("gateSession", session.toJSON()).put("authorization", "");
                        if (persist()) {
                            notice = "登录已加密保存；关闭 App 或重启设备不会清除";
                            renderState();
                            connect();
                        }
                    } catch (IOException | JSONException error) {
                        storageFailed = true;
                        notice = "登录状态无法保存，已禁止发送";
                        renderState();
                    }
                }
                @Override public void failed(String message) {
                    login = null;
                    loginFailurePendingResume = !foreground;
                    notice = message;
                    renderState();
                }
            });
            login.start();
        } catch (JSONException | IllegalArgumentException error) {
            notice = "不能启动 Passkey：" + safe(error);
            renderState();
        }
    }

    private void confirmLogout() {
        if (!authenticationAvailable() || dialog) return;
        Runnable[] after = new Runnable[1];
        AlertDialog box = new AlertDialog.Builder(this).setTitle("退出本机登录")
                .setMessage("清除本机保存的设备凭据、Cookie 和 Authorization，但保留连接设置、草稿与未知发送状态。"
                        + "不会撤销服务端授权；如需撤销设备，请选择服务端撤销或在 Auth 页操作。关闭 App 无需退出登录。")
                .setPositiveButton("清除本机登录", (d, which) -> {
                    disconnect();
                    try {
                        deviceCredentials.clear();
                        deviceAvailable = false;
                    } catch (IOException | JSONException error) {
                        storageFailed = true;
                        notice = "本机凭据清除未确认，已禁止发送；可在 Auth 页撤销设备";
                        renderState();
                        return;
                    }
                    settings.remove("gateSession");
                    settings.remove("authorization");
                    meta = null;
                    projection.clear();
                    persist();
                    if (!storageFailed) notice = "已退出本机登录；请重新登录后连接";
                    renderMessages(false);
                    renderState();
                }).setNegativeButton("取消", null).create();
        showNavigation(box, after, () -> {});
    }

    private void confirmRevoke() {
        if (!authenticationAvailable() || dialog) return;
        if (!deviceAvailable) {
            notice = "本机没有设备扫码凭据；Cookie 会话请在网关管理页撤销";
            renderState();
            return;
        }
        Runnable[] after = new Runnable[1];
        AlertDialog box = new AlertDialog.Builder(this).setTitle("撤销此设备的服务端授权")
                .setMessage("确认后向当前站点撤销设备凭据。不会撤回消息或清除草稿。"
                        + "网络失败不会假称已撤销；可改用仅本机退出，或在 Auth 页远端撤销。")
                .setPositiveButton("确认撤销", (d, w) -> after[0] = this::revokeDevice)
                .setNegativeButton("取消", null).create();
        showNavigation(box, after, () -> {});
    }

    private void revokeDevice() {
        if (!authenticationAvailable()) return;
        String address = settings.optString("address");
        disconnect();
        revoking = true;
        notice = "正在提交一次设备撤销；不会自动重试";
        renderState();
        writes.execute(() -> {
            try {
                boolean cleared = deviceCredentials.revoke(address, new DeviceOAuth(address));
                main.post(() -> {
                    if (isDestroyed()) return;
                    revoking = false;
                    deviceAvailable = !cleared;
                    notice = cleared ? "网关已受理设备撤销；本机设备凭据已清除，草稿保留"
                            : "网关已受理旧设备撤销；本机登录已变更，未清除新凭据";
                    renderState();
                });
            } catch (IOException | JSONException error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    revoking = false;
                    notice = "服务端撤销未确认；设备凭据已停用，不会自动重试。可仅本机退出或在 Auth 页撤销";
                    renderState();
                });
            }
        });
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
        if (!canSwitchSession()) return;
        Runnable[] after = new Runnable[1];
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 8, 24, 8);
        boolean first = settings.optString("address").isEmpty();
        EditText address = input(form, "Cockpit 域名（自动使用 HTTPS）", settings.optString("address"), false);
        EditText authorization = first ? null : input(form, "网关 Authorization（Basic/Bearer；可留空，不是 GitHub token）",
                settings.optString("authorization"), true);
        TextView info = text(15, Color.LTGRAY);
        info.setText("会话从列表选择，无需输入 ID；只聊天，不创建或管理会话。\n录音经现有 Speech/Azure 转写并产生费用；不会自动发送。\n"
                + "扫码由手机 Passkey 批准；电视无需凭据提供器，需网关支持设备授权。\n"
                + "更换域名会清除全部旧凭据；新站点的手动 Authorization 请保存域名后再填写。\n"
                + "有效登录会加密保留，关闭 App / 重启设备不退出登录。\n"
                + "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + " · USB 实际采音需本机验证");
        form.addView(info);
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(first ? "连接 Cockpit" : "高级连接设置")
                .setView(formScroll).setPositiveButton(first ? "保存并扫码登录" : "保存并连接", null)
                .setNegativeButton("取消", null);
        if (!first) builder.setNeutralButton("保存并扫码登录", null);
        AlertDialog box = builder.create();
        box.setOnShowListener(d -> {
            box.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            View.OnClickListener save = v -> {
                try {
                    String url = HostClient.normalizeAddress(address.getText().toString());
                    String auth = authorization == null ? "" : authorization.getText().toString().trim();
                    HostClient.validateSettings(url, "settings", auth);
                    url = okhttp3.HttpUrl.get(url).toString();
                    if (!url.equals(settings.optString("address"))) {
                        deviceCredentials.clear();
                        deviceAvailable = false;
                        settings.remove("gateSession");
                        settings.remove("sessionId");
                        auth = "";
                    }
                    if (!auth.isEmpty()) {
                        deviceCredentials.clear();
                        deviceAvailable = false;
                        settings.remove("gateSession");
                    }
                    settings.put("address", url).put("authorization", auth);
                    meta = null; receipt = "";
                    if (persist()) {
                        after[0] = first || v == box.getButton(AlertDialog.BUTTON_NEUTRAL)
                                ? this::startDeviceLogin : this::chooseSession;
                        box.dismiss();
                    }
                } catch (IOException error) {
                    storageFailed = true;
                    info.setText("旧设备凭据清除未确认，已禁止变更连接");
                } catch (JSONException | IllegalArgumentException error) { info.setText(safe(error)); }
            };
            box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save);
            if (!first) box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(save);
        });
        showNavigation(box, after, this::fullscreen);
    }

    private static String safe(Exception error) {
        if (error instanceof HostClient.Rejected || error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            return message == null ? "请求失败" : message.substring(0, Math.min(message.length(), 300));
        }
        return error instanceof JSONException ? "公开 API 响应格式不兼容" : "网络或服务不可用";
    }
}
