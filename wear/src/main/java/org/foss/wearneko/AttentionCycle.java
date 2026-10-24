// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

/** One awake attention effect. A missed frame window cancels it instead of replaying a landing. */
final class AttentionCycle {
    static final long DURATION_MS = 450L;
    static final long MAX_FRAME_GAP_MS = 250L;
    enum Update { IDLE, ACTIVE, LANDED, CANCELLED }
    private long startedAt;
    private long previousFrameAt;
    private boolean running;

    void start(long now) {
        startedAt = now;
        previousFrameAt = now;
        running = true;
    }

    Update advance(long now) {
        if (!running) return Update.IDLE;
        if (now < previousFrameAt || now-previousFrameAt > MAX_FRAME_GAP_MS) {
            cancel();
            return Update.CANCELLED;
        }
        previousFrameAt = now;
        if (now-startedAt >= DURATION_MS) {
            running = false;
            return Update.LANDED;
        }
        return Update.ACTIVE;
    }

    float progress(long now) {
        return Math.min(1f, Math.max(0f, (now-startedAt)/(float) DURATION_MS));
    }

    boolean isRunning() { return running; }
    void cancel() { running = false; }
}
