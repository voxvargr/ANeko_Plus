package org.nqmgaming.aneko.companion;

import java.util.HashSet;
import java.util.Set;

/** Session fencing shared by discovery and the command receiver; independent of Android. */
final class PeerSessionGuard {
    private static final int MAX_RETIRED_SESSIONS = 128;
    private final Set<String> retired = new HashSet<>();
    private String current;

    boolean acceptHello(String candidate) {
        if (!validSession(candidate) || retired.contains(candidate)) return false;
        if (candidate.equals(current)) return true;
        // Never evict a revoked session and accidentally permit its delayed commands.
        // Restarting the companion session resets discovery after this exceptional limit.
        if (current != null && retired.size() >= MAX_RETIRED_SESSIONS) return false;
        if (current != null) retired.add(current);
        current = candidate;
        return true;
    }

    static boolean targetsLocalSession(String type, String recipient, String localSession) {
        return "HELLO".equals(type) || (validSession(localSession) && localSession.equals(recipient));
    }

    static boolean validPhoneHello(String senderSession, String authorityEpoch) {
        return validSession(senderSession) && senderSession.equals(authorityEpoch);
    }

    private static boolean validSession(String value) {
        return value != null && !value.isEmpty() && value.length() <= 64;
    }
}
