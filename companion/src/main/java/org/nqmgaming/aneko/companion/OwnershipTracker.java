package org.nqmgaming.aneko.companion;

import java.util.Objects;
import java.util.Set;

/** Durable authorization journal. A destination must not appear before its grant is saved. */
final class OwnershipTracker {
    interface Store {
        String readOwner();
        /** Must finish writing durable storage before returning true. */
        boolean writeOwner(String owner);
    }

    private final Store store;
    private String durableOwner;

    OwnershipTracker(Store store) {
        this.store = Objects.requireNonNull(store, "store");
        durableOwner = store.readOwner();
    }

    boolean record(Set<HandoffStateMachine.Site> sites) {
        boolean phone = sites.contains(HandoffStateMachine.Site.PHONE);
        boolean watch = sites.contains(HandoffStateMachine.Site.WATCH);
        String owner = phone && watch ? "both" : phone ? "phone" : watch ? "watch" : "none";
        // IDLE snapshots and heartbeats must not cause synchronous disk writes every tick.
        if (owner.equals(durableOwner) && owner.equals(store.readOwner())) return true;
        // SharedPreferences.commit can update its in-memory map even when the disk write
        // fails. Only a successful write advances this instance's durable checkpoint.
        if (!store.writeOwner(owner)) return false;
        durableOwner = owner;
        return true;
    }

    boolean phoneMayStart(String mode) {
        String owner = durableOwner;
        // A previous BOTH session may still have a live watch. A newly selected single
        // device mode must first confirm that watch has disappeared.
        return "phone".equals(owner) || ("both".equals(owner) && "both".equals(mode));
    }
}
