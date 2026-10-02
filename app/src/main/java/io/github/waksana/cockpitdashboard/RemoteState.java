package io.github.waksana.cockpitdashboard;

final class RemoteState {
    enum Phase { IDLE, RECORDING, TRANSCRIBING, DRAFT, SENDING, UNKNOWN }
    enum Action { NONE, START, STOP, SEND, DISCARD }
    Phase phase = Phase.IDLE;
    private int held = -1;
    private String segmentBase;
    String draft = "";

    void restore(String text, boolean uncertain) {
        transcript(text);
        if (uncertain) phase = Phase.UNKNOWN;
    }

    Action down(int key, int repeat) {
        if (repeat != 0) return Action.NONE;
        if (key == 23 || key == 66 || key == 160) {
            if ((phase != Phase.IDLE && phase != Phase.DRAFT) || held != -1) return Action.NONE;
            held = key;
            segmentBase = draft;
            phase = Phase.RECORDING;
            return Action.START;
        }
        if (key == 22 && phase == Phase.DRAFT && held == -1 && !draft.trim().isEmpty()) {
            phase = Phase.SENDING;
            return Action.SEND;
        }
        if (key == 21 && phase != Phase.SENDING && phase != Phase.IDLE) {
            return Action.DISCARD;
        }
        return Action.NONE;
    }

    Action up(int key) {
        if (key != held) return Action.NONE;
        held = -1;
        if (phase != Phase.RECORDING) return Action.NONE;
        phase = Phase.TRANSCRIBING;
        return Action.STOP;
    }

    void transcript(String text) {
        held = -1;
        segmentBase = null;
        draft = text;
        phase = text.trim().isEmpty() ? Phase.IDLE : Phase.DRAFT;
    }

    boolean finishSegment(String text) {
        if (phase != Phase.TRANSCRIBING || segmentBase == null) return false;
        String base = segmentBase;
        if (text.trim().isEmpty()) transcript(base);
        else transcript(base.isEmpty() ? text : base + (base.endsWith("\n") ? "" : "\n") + text);
        return true;
    }

    void clear() {
        held = -1;
        segmentBase = null;
        draft = "";
        phase = Phase.IDLE;
    }

    void interrupt() {
        held = -1;
        if (phase == Phase.RECORDING || phase == Phase.TRANSCRIBING) {
            transcript(segmentBase == null ? draft : segmentBase);
        }
    }
}
