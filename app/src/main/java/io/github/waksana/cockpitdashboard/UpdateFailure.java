package io.github.waksana.cockpitdashboard;

import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Only fixed labels and numeric facts may cross the updater's diagnostic boundary. */
final class UpdateFailure extends IOException {
    enum Stage {
        INSTALLED_PACKAGE("读取已安装应用"), RELEASE("获取发布信息"), MANIFEST("获取更新清单"),
        APK_DOWNLOAD("下载 APK"), APK_STORAGE("保存 APK"), FILE_VERIFY("文件完整性校验"),
        APK_PARSE("系统解析 APK"), PACKAGE("包名校验"), VERSION("版本校验"),
        MIN_SDK("Android 兼容性校验"), SIGNATURE("签名比对"),
        INSTALLED_SIGNER("读取安装版签名"), APK_SIGNER("读取更新包签名"),
        SOURCE_SETTINGS("打开安装来源设置"), INSTALLER("打开安装器");

        final String label;
        Stage(String label) { this.label = label; }
    }

    enum Reason {
        DNS("无法解析下载服务地址"), TIMEOUT("连接或读取超时"), TLS("TLS 安全连接失败"),
        CONNECT("无法连接下载服务"), INTERRUPTED("操作中断"), IO("读写失败"),
        TRUNCATED("响应提前结束"), PROTOCOL("响应传输格式异常"), HTTP("服务器返回非成功状态"),
        REDIRECT("重定向不符合安全规则"), REDIRECT_LIMIT("重定向次数超限"),
        METADATA("发布信息格式或内容不符合规则"), TOO_LARGE("响应超过大小限制"),
        SIZE("文件大小与清单不符"), HASH("SHA-256 与清单不符"),
        STORAGE("无法创建或保存私有更新文件"), ARCHIVE("系统未能解析 APK"),
        PACKAGE("包名与本应用不符"), VERSION_CODE("版本号与清单不符"),
        VERSION_NAME("版本名称与清单不符"), NOT_NEWER("更新包版本不高于安装版"),
        MIN_SDK("APK 要求更高的 Android API"), BINARY_MANIFEST("无法读取 APK 最低 API 要求"),
        SIGNER_MISSING("无法取得单一有效签名"), SIGNER_MULTIPLE("不支持多个签名者"),
        SIGNER_HISTORY("签名历史缺失或不支持证书轮换"), CERTIFICATE("安装版与更新包证书不一致"),
        PERMISSION("系统拒绝访问"), NOT_FOUND("系统组件或应用信息不可用"),
        INVALID("系统参数或返回结果异常");

        final String label;
        Reason(String label) { this.label = label; }
    }

    final Stage stage;
    final Reason reason;
    private final String facts;

    UpdateFailure(Stage stage, Reason reason) { this(stage, reason, ""); }

    private UpdateFailure(Stage stage, Reason reason, String facts) {
        super(stage.name() + "/" + reason.name() + facts);
        this.stage = stage;
        this.reason = reason;
        this.facts = facts;
    }

    static UpdateFailure http(Stage stage, int status) {
        return new UpdateFailure(stage, Reason.HTTP, " HTTP=" + status);
    }

    static UpdateFailure size(Stage stage, long expected, long actual) {
        return new UpdateFailure(stage, Reason.SIZE, " expectedBytes=" + expected + " actualBytes=" + actual);
    }

    static UpdateFailure minSdk(int required) {
        return new UpdateFailure(Stage.MIN_SDK, Reason.MIN_SDK, " minSdk=" + required);
    }

    static UpdateFailure at(Stage stage, Throwable error) {
        if (error instanceof UpdateFailure) return (UpdateFailure) error;
        Reason reason;
        if (error instanceof UnknownHostException) reason = Reason.DNS;
        else if (error instanceof SocketTimeoutException) reason = Reason.TIMEOUT;
        else if (error instanceof SSLException) reason = Reason.TLS;
        else if (error instanceof ConnectException) reason = Reason.CONNECT;
        else if (error instanceof EOFException) reason = Reason.TRUNCATED;
        else if (error instanceof ProtocolException) reason = Reason.PROTOCOL;
        else if (error instanceof InterruptedIOException) reason = Reason.INTERRUPTED;
        else if (error instanceof SecurityException) reason = Reason.PERMISSION;
        else if (error instanceof PackageManager.NameNotFoundException || error instanceof ActivityNotFoundException) {
            reason = Reason.NOT_FOUND;
        } else if (error instanceof IllegalArgumentException) reason = Reason.INVALID;
        else reason = Reason.IO;
        return new UpdateFailure(stage, reason);
    }

    String describe(int api, Integer targetCode) {
        return stage.label + "：" + reason.label + " [" + stage.name() + "/" + reason.name()
                + "; API=" + api + (targetCode == null ? "" : "; targetCode=" + targetCode) + facts + "]";
    }
}
