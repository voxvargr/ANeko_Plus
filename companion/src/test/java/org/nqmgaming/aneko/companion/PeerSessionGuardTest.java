package org.nqmgaming.aneko.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PeerSessionGuardTest {
    @Test public void delayedRetiredHelloCannotRollCurrentAuthorityBack() {
        PeerSessionGuard guard = new PeerSessionGuard();
        assertTrue(guard.acceptHello("phone-session-a"));
        assertTrue(guard.acceptHello("phone-session-b"));
        assertFalse(guard.acceptHello("phone-session-a"));
        assertTrue(guard.acceptHello("phone-session-b"));
        assertTrue(guard.acceptHello("phone-session-c"));
        assertFalse(guard.acceptHello("phone-session-a"));
        assertFalse(guard.acceptHello("phone-session-b"));
    }

    @Test public void previousWatchRecipientCannotAcceptOldPrepareOrActivateAfterRestart() {
        for (String type : new String[] {"PREPARE", "READY", "DEPART", "GONE", "ACTIVATE", "ACK"}) {
            assertFalse(PeerSessionGuard.targetsLocalSession(type, "watch-before-restart", "watch-after-restart"));
            assertTrue(PeerSessionGuard.targetsLocalSession(type, "watch-after-restart", "watch-after-restart"));
        }
    }

    @Test public void onlyDiscoveryHelloMayOmitRecipientBinding() {
        assertTrue(PeerSessionGuard.targetsLocalSession("HELLO", "", "local-session"));
        for (String type : new String[] {"HELLO_ACK", "PRESENCE", "MODE", "MODE_REQUEST", "BYE", "ACTIVATE"}) {
            assertFalse(PeerSessionGuard.targetsLocalSession(type, "", "local-session"));
            assertFalse(PeerSessionGuard.targetsLocalSession(type, null, "local-session"));
            assertTrue(PeerSessionGuard.targetsLocalSession(type, "local-session", "local-session"));
        }
    }

    @Test public void phoneHelloMustOwnItsClaimedAuthorityEpoch() {
        assertTrue(PeerSessionGuard.validPhoneHello("phone-session", "phone-session"));
        assertFalse(PeerSessionGuard.validPhoneHello("phone-session", "older-phone-session"));
        assertFalse(PeerSessionGuard.validPhoneHello("phone-session", ""));
        assertFalse(PeerSessionGuard.validPhoneHello("", ""));
    }

    @Test public void retiredSessionsAreNotForgottenWhenTheBoundIsReached() {
        PeerSessionGuard guard = new PeerSessionGuard();
        for (int i = 0; i <= 128; i++) assertTrue(guard.acceptHello("session-" + i));
        assertFalse(guard.acceptHello("session-129"));
        assertFalse(guard.acceptHello("session-0"));
        assertTrue(guard.acceptHello("session-128"));
    }
}
