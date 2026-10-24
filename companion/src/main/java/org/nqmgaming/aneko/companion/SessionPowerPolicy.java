package org.nqmgaming.aneko.companion;

/** Pure timer policy. Event-driven protocol replies and explicit user actions do not use these timers. */
final class SessionPowerPolicy {
    static final long NO_TIMER = -1L;

    static final class Plan {
        final long tickDelayMs;
        final long discoverIntervalMs;
        final long peerStaleMs;

        private Plan(long tickDelayMs, long discoverIntervalMs, long peerStaleMs) {
            this.tickDelayMs = tickDelayMs;
            this.discoverIntervalMs = discoverIntervalMs;
            this.peerStaleMs = peerStaleMs;
        }

        boolean runsTimers() { return tickDelayMs != NO_TIMER; }
    }

    private static final Plan SUSPENDED = new Plan(NO_TIMER, NO_TIMER, NO_TIMER);
    private static final Plan TRANSFER = new Plan(1_000L, 4_000L, 12_000L);
    private static final Plan SEARCHING = new Plan(4_000L, 4_000L, 12_000L);
    private static final Plan IDLE = new Plan(15_000L, 60_000L, 60_000L);
    private static final Plan WATCH_OWNED = new Plan(4_000L, 60_000L, 60_000L);

    static Plan plan(boolean phone, boolean interactive, boolean peerConnected,
                     boolean peerInteractive, boolean watchAuthorized, boolean transferPending) {
        // Even a pending watch-side mode request waits for screen-on; no watchdog wakes the watch.
        if (!phone && !interactive) return SUSPENDED;
        // Silence is expected after the watch reports sleep. Preserve its identity and ownership.
        if (phone && peerConnected && !peerInteractive && !transferPending) return SUSPENDED;
        if (transferPending) return TRANSFER;
        if (!peerConnected) return SEARCHING;
        if (!phone && watchAuthorized) return WATCH_OWNED;
        return IDLE;
    }

    private SessionPowerPolicy() { }
}
