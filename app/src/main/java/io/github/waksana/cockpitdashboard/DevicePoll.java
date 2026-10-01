package io.github.waksana.cockpitdashboard;

/** Elapsed-realtime polling policy only; the owner schedules and serializes requests. */
final class DevicePoll {
    private final long deadline;
    private long interval, next;
    private boolean stopped;

    DevicePoll(long nowElapsedMs, long expiresInSeconds, long intervalSeconds) {
        if (nowElapsedMs < 0 || expiresInSeconds <= 0 || intervalSeconds <= 0) {
            throw new IllegalArgumentException("Invalid polling lifetime");
        }
        deadline = add(nowElapsedMs, millis(expiresInSeconds));
        interval = millis(intervalSeconds);
        next = add(nowElapsedMs, interval);
    }

    /** Milliseconds until the next request; Long.MAX_VALUE means do not poll. */
    long delay(long nowElapsedMs) {
        if (expired(nowElapsedMs)) return Long.MAX_VALUE;
        return nowElapsedMs >= next ? 0 : next - Math.max(0, nowElapsedMs);
    }

    boolean expired(long nowElapsedMs) { return stopped || nowElapsedMs >= deadline; }

    long remainingSeconds(long nowElapsedMs) {
        if (expired(nowElapsedMs)) return 0;
        long remaining = deadline - Math.max(0, nowElapsedMs);
        return remaining / 1000 + (remaining % 1000 == 0 ? 0 : 1);
    }

    void pending(long nowElapsedMs) { waitAfter(nowElapsedMs); }

    void slowDown(long nowElapsedMs) {
        if (expired(nowElapsedMs)) return;
        interval = add(interval, 5000);
        waitAfter(nowElapsedMs);
    }

    void timeout(long nowElapsedMs) {
        if (expired(nowElapsedMs)) return;
        interval = add(interval, interval);
        waitAfter(nowElapsedMs);
    }

    void stop() { stopped = true; }

    private void waitAfter(long nowElapsedMs) {
        if (!expired(nowElapsedMs)) next = add(Math.max(0, nowElapsedMs), interval);
    }

    private static long millis(long seconds) {
        return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
    }

    private static long add(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
