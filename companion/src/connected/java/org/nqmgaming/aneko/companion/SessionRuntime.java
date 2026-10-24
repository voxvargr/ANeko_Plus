package org.nqmgaming.aneko.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/** Live Data Layer transport. There is deliberately no manifest listener or autostart service. */
final class SessionRuntime {
    private static final String MESSAGE_PATH = "/aneko/companion/v1";
    private static final String SETTINGS_PATH = "/aneko/settings/v1/phone";
    private static final int MAX_PACK_BYTES = 8 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private final Context context;
    private final boolean phone;
    private final CompanionSession.Host host;
    private final SharedPreferences preferences;
    private final OwnershipTracker ownership;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final String localSession = UUID.randomUUID().toString();
    private final PeerSessionGuard peerSessions = new PeerSessionGuard();
    private final MessageClient messages;
    private final DataClient data;
    private final NodeClient nodes;
    private final Set<Long> prepared = new HashSet<>();
    private final Set<Long> activated = new HashSet<>();
    private final Map<String, Boolean> departures = new HashMap<>();
    private final Map<String, Runnable> departureCompletions = new HashMap<>();
    private final Set<String> reachable = new HashSet<>();
    private final Set<File> inboxFiles = new HashSet<>();
    private boolean started;
    private boolean closed;
    private boolean interactive;
    private boolean localVisible;
    private boolean peerInteractive;
    private boolean peerReady;
    private boolean peerConnected;
    private boolean peerSynced;
    private boolean watchAuthorized;
    private long lastPeerSeen;
    private long lastDiscover;
    private boolean tickQueued;
    private long nextTickAt;
    private long newestAuthorityId;
    private long syncBaseline;
    private int generation;
    private String peerId;
    private String peerSession;
    private String authorityEpoch;
    private String syncToken;
    private String pendingModeRequest;
    private String lastSettingsRevision = "";
    private String pendingSettingsRevision = "";
    private long lastSettingsSequence;
    private long pendingSettingsSequence;
    private String settingsJson;
    private File settingsPack;
    private volatile String status;
    private HandoffStateMachine machine;

    private final MessageClient.OnMessageReceivedListener messageListener = event -> {
        if (!MESSAGE_PATH.equals(event.getPath()) || event.getData().length > 8_192) return;
        String sender = event.getSourceNodeId();
        byte[] bytes = event.getData().clone();
        main.post(() -> receiveMessage(sender, bytes));
    };
    private final DataClient.OnDataChangedListener dataListener = events -> {
        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED) acceptDataItem(event.getDataItem());
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickQueued = false;
            if (!started) return;
            SessionPowerPolicy.Plan power = powerPlan();
            if (!power.runsTimers()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastDiscover >= power.discoverIntervalMs) discover(false);
            if (peerConnected && now - lastPeerSeen > power.peerStaleMs) peerLost();
            if (peerId != null && peerConnected) {
                send(peerId, envelope("PRESENCE", 0));
                if (phone && !peerSynced && syncToken != null) sendSyncDeparture();
                if (!phone && pendingModeRequest != null) send(peerId, envelope("MODE_REQUEST", 0));
            }
            if (phone && machine != null && peerSynced) {
                machine.retryTick(now);
                evaluateTarget();
            }
            scheduleTick();
        }
    };

    SessionRuntime(Context context, boolean phone, CompanionSession.Host host) {
        this.context = context;
        this.phone = phone;
        this.host = host;
        preferences = context.getSharedPreferences("companion_sync", Context.MODE_PRIVATE);
        ownership = new OwnershipTracker(new OwnershipTracker.Store() {
            @Override public String readOwner() { return preferences.getString("owner", "phone"); }
            @Override public boolean writeOwner(String owner) {
                return preferences.edit().putString("owner", owner).commit();
            }
        });
        if (!phone) pendingModeRequest = preferences.getString("requested_mode", null);
        messages = Wearable.getMessageClient(context);
        data = Wearable.getDataClient(context);
        nodes = Wearable.getNodeClient(context);
        status = preferences.getString("status", "Not connected");
        PowerManager power = context.getSystemService(PowerManager.class);
        interactive = power != null && power.isInteractive();
        authorityEpoch = phone ? localSession : null;
    }

    static boolean isSupported() { return true; }

    void start() { main.post(() -> {
        if (started || closed) return;
        started = true;
        // A restarted watch waits for authority. A phone with uncertain remote ownership
        // also stays hidden until the old watch has acknowledged that it is gone.
        boolean localAllowed = phone && ownership.phoneMayStart(mode());
        localVisible = false;
        if (localAllowed && interactive) showLocal(); else host.hide();
        setStatus(phone ? "Looking for an active watch companion" : "Waiting for the phone companion");
        messages.addListener(messageListener).addOnFailureListener(error ->
            main.post(() -> setStatus("Wear connection unavailable")));
        data.addListener(dataListener).addOnFailureListener(error ->
            main.post(() -> setStatus("Settings connection unavailable")));
        discover(true);
        scheduleTick();
        if (phone && settingsJson != null) publishCurrentSettings();
    }); }

    void close() { main.post(() -> {
        if (closed) return;
        closed = true;
        started = false;
        generation++;
        main.removeCallbacks(tick);
        tickQueued = false;
        messages.removeListener(messageListener);
        data.removeListener(dataListener);
        if (peerId != null) send(peerId, envelope("BYE", 0));
        watchAuthorized = false;
        prepared.clear();
        activated.clear();
        departures.clear();
        departureCompletions.clear();
        machine = null;
        setStatus("Companion session stopped");
        io.shutdown();
        // Imported skin data belongs to the host; only this session's temporary ZIPs are removed.
        for (File file : inboxFiles) file.delete();
        inboxFiles.clear();
    }); }

    void updatePresence(boolean value) { main.post(() -> {
        interactive = value;
        if (!started) return;
        if (!interactive) {
            // Sleeping must complete a pending departure even when Host.hide cancels its
            // animator and that animator no longer delivers its completion callback.
            for (Runnable completion : new java.util.ArrayList<>(departureCompletions.values())) completion.run();
            host.hide();
            localVisible = false;
        } else if (phone && machine != null && machine.getSnapshot().phase == HandoffStateMachine.Phase.IDLE
                && machine.getSnapshot().active.contains(HandoffStateMachine.Site.PHONE)) {
            showLocal();
        } else if (!phone && watchAuthorized) {
            showLocal();
        }
        if (peerId != null) send(peerId, envelope("PRESENCE", 0));
        if (phone) evaluateTarget();
        if (interactive) resumeTriggeredTraffic();
        scheduleTick();
    }); }

    void publishSettings(String json, File pack) { main.post(() -> {
        if (!phone || closed || json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) return;
        try { new JSONObject(json); } catch (JSONException invalid) { return; }
        settingsJson = json;
        settingsPack = pack;
        if (started) publishCurrentSettings();
    }); }

    void setMode(String mode) { main.post(() -> {
        String checked = CompanionSession.validatedMode(mode);
        preferences.edit().putString("mode", checked).apply();
        if (!phone) {
            pendingModeRequest = checked;
            preferences.edit().putString("requested_mode", checked).apply();
        }
        if (!started) return;
        if (phone) {
            if (peerId != null) send(peerId, envelope("MODE", 0));
            evaluateTarget();
        } else if (peerId != null) {
            pendingModeRequest = checked;
            send(peerId, envelope("MODE_REQUEST", 0));
        } else {
            pendingModeRequest = checked;
        }
        // A deliberate mode change can contact a sleeping peer; routine timers cannot.
        if (phone || interactive) resumeTriggeredTraffic();
        scheduleTick();
    }); }

    String getStatus() { return status; }

    private String mode() {
        return CompanionSession.validatedMode(preferences.getString("mode", "phone"));
    }

    private boolean transferPending() {
        return phone ? (!peerSynced && syncToken != null) ||
            (machine != null && machine.getSnapshot().phase != HandoffStateMachine.Phase.IDLE)
            : pendingModeRequest != null;
    }

    private SessionPowerPolicy.Plan powerPlan() {
        return SessionPowerPolicy.plan(phone, interactive, peerConnected, peerInteractive,
            watchAuthorized, transferPending());
    }

    private void scheduleTick() {
        SessionPowerPolicy.Plan power = powerPlan();
        if (!started || closed || !power.runsTimers()) {
            main.removeCallbacks(tick);
            tickQueued = false;
            return;
        }
        long due = SystemClock.elapsedRealtime() + power.tickDelayMs;
        // Incoming traffic must not postpone a pending transfer's retry indefinitely.
        if (tickQueued && nextTickAt <= due) return;
        main.removeCallbacks(tick);
        nextTickAt = due;
        tickQueued = true;
        main.postDelayed(tick, power.tickDelayMs);
    }

    private void resumeTriggeredTraffic() {
        if (!started || (!phone && !interactive)) return;
        discover(true);
        if (peerId == null) return;
        if (phone && !peerSynced && syncToken != null) sendSyncDeparture();
        if (!phone && pendingModeRequest != null) send(peerId, envelope("MODE_REQUEST", 0));
        if (phone && machine != null && peerSynced) machine.retryTick(SystemClock.elapsedRealtime());
    }

    private void discover(boolean explicit) {
        if (!started || (!phone && !interactive) || (!explicit && !powerPlan().runsTimers())) return;
        lastDiscover = SystemClock.elapsedRealtime();
        nodes.getConnectedNodes().addOnSuccessListener(list -> main.post(() -> {
            // A discovery requested before SCREEN_OFF must not send a late batch of HELLOs.
            if (!started || (!phone && !interactive) || (!explicit && !powerPlan().runsTimers())) return;
            reachable.clear();
            for (Node node : list) {
                reachable.add(node.getId());
                // The role/version HELLO response, not an arbitrary connected node, selects a peer.
                send(node.getId(), envelope("HELLO", 0));
            }
            if (peerId != null && !reachable.contains(peerId)) peerLost();
        })).addOnFailureListener(error -> main.post(() -> {
            if (started && (phone || interactive) && (explicit || powerPlan().runsTimers())) peerLost();
        }));
    }

    private JSONObject envelope(String type, long id) {
        JSONObject value = new JSONObject();
        try {
            value.put("v", 1).put("type", type).put("role", phone ? "phone" : "watch")
                .put("session", localSession).put("epoch", authorityEpoch == null ? "" : authorityEpoch)
                .put("recipientSession", "HELLO".equals(type) || peerSession == null ? "" : peerSession)
                .put("id", id).put("interactive", interactive).put("ready", host.isReady())
                .put("mode", mode());
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private void send(String node, JSONObject value) {
        if (node == null) return;
        messages.sendMessage(node, MESSAGE_PATH, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void receiveMessage(String sender, byte[] bytes) {
        if (!started) return;
        try {
            JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (message.optInt("v") != 1 || !(phone ? "watch" : "phone").equals(message.optString("role"))) return;
            String type = message.optString("type");
            String remoteSession = message.optString("session");
            if (remoteSession.isEmpty() || remoteSession.length() > 64) return;
            if (!PeerSessionGuard.targetsLocalSession(type, message.optString("recipientSession"), localSession)) return;
            if ("HELLO".equals(type)) {
                if (peerId != null && !peerId.equals(sender)) return;
                if (!phone && !PeerSessionGuard.validPhoneHello(remoteSession, message.optString("epoch"))) return;
                if (!peerSessions.acceptHello(remoteSession)) return;
                boolean replacement = peerSession == null || !peerSession.equals(remoteSession);
                peerId = sender;
                peerSession = remoteSession;
                peerConnected = true;
                lastPeerSeen = SystemClock.elapsedRealtime();
                peerInteractive = message.optBoolean("interactive");
                peerReady = message.optBoolean("ready");
                if (phone) {
                    if (replacement) beginSynchronization();
                    send(sender, envelope("HELLO_ACK", 0));
                    if (!replacement) evaluateTarget();
                } else {
                    String epoch = message.optString("epoch");
                    if (epoch.isEmpty()) return;
                    if (!epoch.equals(authorityEpoch)) {
                        authorityEpoch = epoch;
                        generation++;
                        prepared.clear();
                        activated.clear();
                        departures.clear();
                        departureCompletions.clear();
                        newestAuthorityId = 0;
                        watchAuthorized = false;
                        // A new authority invalidates every old visibility grant immediately.
                        // Do not leave a visible watch waiting for a later DEPART packet.
                        host.hide();
                        localVisible = false;
                    }
                    if (pendingModeRequest == null) preferences.edit().putString("mode",
                        CompanionSession.validatedMode(message.optString("mode"))).apply();
                    send(sender, envelope("HELLO_ACK", 0));
                    if (pendingModeRequest != null) send(sender, envelope("MODE_REQUEST", 0));
                    loadCachedSettings();
                }
                return;
            }
            if (!sender.equals(peerId) || !remoteSession.equals(peerSession)) return;
            if (!message.optString("epoch").equals(authorityEpoch)) return;
            peerConnected = true;
            lastPeerSeen = SystemClock.elapsedRealtime();
            peerInteractive = message.optBoolean("interactive");
            peerReady = message.optBoolean("ready");
            if ("HELLO_ACK".equals(type)) {
                // Both devices originate HELLO, so a lost response is repaired by discovery.
                if (phone && syncToken != null && !peerSynced) sendSyncDeparture();
                return;
            }
            if ("BYE".equals(type)) { peerLost(); return; }
            if ("PRESENCE".equals(type)) { if (phone) evaluateTarget(); return; }
            if ("MODE_REQUEST".equals(type) && phone) {
                preferences.edit().putString("mode", CompanionSession.validatedMode(message.optString("mode"))).apply();
                send(sender, envelope("MODE", 0));
                evaluateTarget();
                return;
            }
            if ("MODE".equals(type) && !phone) {
                String received = CompanionSession.validatedMode(message.optString("mode"));
                if (pendingModeRequest == null || pendingModeRequest.equals(received)) {
                    preferences.edit().putString("mode", received).apply();
                    preferences.edit().remove("requested_mode").apply();
                    pendingModeRequest = null;
                }
                return;
            }
            long id = message.optLong("id", -1);
            if (id < 0) return;
            if (phone) receivePhoneSignal(message, type, id);
            else receiveWatchCommand(message, type, id);
        } catch (JSONException ignored) {
            // Unrecognized protocol data never changes visibility or settings.
        } finally {
            // A valid wake/presence or transfer reply may change a suspended session's schedule.
            scheduleTick();
        }
    }

    private void beginSynchronization() {
        peerSynced = false;
        if (machine != null && machine.getSnapshot().departing.contains(HandoffStateMachine.Site.PHONE)) {
            host.hide();
            localVisible = false;
        }
        generation++;
        departures.clear();
        departureCompletions.clear();
        machine = null;
        syncToken = UUID.randomUUID().toString();
        syncBaseline = System.currentTimeMillis() * 1_000L;
        setStatus("Synchronizing companion ownership");
        sendSyncDeparture();
    }

    private void sendSyncDeparture() {
        JSONObject value = envelope("DEPART", syncBaseline);
        try { value.put("sync", syncToken); } catch (JSONException impossible) { return; }
        send(peerId, value);
    }

    private void receivePhoneSignal(JSONObject message, String type, long id) {
        if ("GONE".equals(type) && syncToken != null && syncToken.equals(message.optString("sync"))) {
            if (peerSynced) return;
            peerSynced = true;
            final int epoch = generation;
            machine = new HandoffStateMachine(new HandoffStateMachine.Host() {
                @Override public void prepare(long transfer, HandoffStateMachine.Site site) {
                    if (site == HandoffStateMachine.Site.WATCH) send(peerId, envelope("PREPARE", transfer));
                    else if (host.isReady()) main.post(() -> {
                        if (current(epoch)) machine.onReady(transfer, site);
                    });
                }
                @Override public void depart(long transfer, HandoffStateMachine.Site site) {
                    if (site == HandoffStateMachine.Site.WATCH) send(peerId, envelope("DEPART", transfer));
                    else departOnce("local:" + transfer, epoch, () -> {
                        if (current(epoch)) machine.onGone(transfer, site);
                    });
                }
                @Override public void activate(long transfer, HandoffStateMachine.Site site) {
                    // If disk persistence failed, a restart could otherwise show the phone
                    // from its old owner value after the watch has already been activated.
                    if (!rememberOwnership(machine.getSnapshot())) return;
                    if (site == HandoffStateMachine.Site.WATCH) send(peerId, envelope("ACTIVATE", transfer));
                    else if (host.isReady()) {
                        showLocal();
                        main.post(() -> { if (current(epoch)) machine.onAck(transfer, site); });
                    }
                }
                @Override public void cancelPrepare(long transfer, HandoffStateMachine.Site site) {
                    if (site == HandoffStateMachine.Site.WATCH) send(peerId, envelope("CANCEL", transfer));
                }
                @Override public void changed(HandoffStateMachine.Snapshot snapshot) {
                    if (!rememberOwnership(snapshot)) {
                        host.hide();
                        localVisible = false;
                        return;
                    }
                    if (snapshot.phase == HandoffStateMachine.Phase.IDLE) {
                        if (snapshot.active.contains(HandoffStateMachine.Site.PHONE)) showLocal();
                        else { host.hide(); localVisible = false; }
                        boolean onPhone = snapshot.active.contains(HandoffStateMachine.Site.PHONE);
                        boolean onWatch = snapshot.active.contains(HandoffStateMachine.Site.WATCH);
                        setStatus(onPhone && onWatch ? "Companion on both" : onPhone ? "Companion on phone"
                            : onWatch ? "Companion on watch" : "Waiting to confirm companion ownership");
                    } else setStatus("Moving companion: " + snapshot.phase.name().toLowerCase(Locale.ROOT));
                    scheduleTick();
                }
            }, HandoffStateMachine.Target.PHONE, syncBaseline + 1, 6_000L);
            rememberOwnership(machine.getSnapshot());
            evaluateTarget();
            if (machine.getSnapshot().phase == HandoffStateMachine.Phase.IDLE) showLocal();
            return;
        }
        if (!peerSynced || machine == null) return;
        if ("READY".equals(type)) machine.onReady(id, HandoffStateMachine.Site.WATCH);
        else if ("GONE".equals(type)) machine.onGone(id, HandoffStateMachine.Site.WATCH);
        else if ("ACK".equals(type)) machine.onAck(id, HandoffStateMachine.Site.WATCH);
    }

    private void receiveWatchCommand(JSONObject message, String type, long id) {
        String sync = message.optString("sync");
        if ("DEPART".equals(type) && !sync.isEmpty()) {
            if (id < newestAuthorityId && !departures.containsKey("sync:" + sync)) return;
            newestAuthorityId = Math.max(newestAuthorityId, id);
            prepared.clear();
            watchAuthorized = false;
            int epoch = generation;
            departOnce("sync:" + sync, epoch, () -> {
                JSONObject gone = envelope("GONE", id);
                try { gone.put("sync", sync); } catch (JSONException impossible) { return; }
                send(peerId, gone);
            });
            return;
        }
        if (id < newestAuthorityId) return;
        if ("PREPARE".equals(type)) {
            newestAuthorityId = id;
            if (host.isReady()) {
                prepared.clear();
                prepared.add(id);
                send(peerId, envelope("READY", id));
                setStatus("Ready for companion arrival");
            }
        } else if ("CANCEL".equals(type)) {
            prepared.remove(id);
        } else if ("DEPART".equals(type)) {
            newestAuthorityId = id;
            watchAuthorized = false;
            int epoch = generation;
            departOnce("remote:" + id, epoch, () -> send(peerId, envelope("GONE", id)));
        } else if ("ACTIVATE".equals(type) && (prepared.contains(id) || activated.contains(id))) {
            newestAuthorityId = id;
            if (!host.isReady()) return;
            watchAuthorized = true;
            if (activated.add(id)) showLocal();
            send(peerId, envelope("ACK", id));
            setStatus("Companion on watch");
        }
    }

    private boolean current(int epoch) { return started && generation == epoch && machine != null; }

    private void departOnce(String key, int epoch, Runnable gone) {
        Boolean state = departures.get(key);
        if (Boolean.TRUE.equals(state)) { gone.run(); return; }
        if (Boolean.FALSE.equals(state)) return;
        if (departures.size() > 64) departures.entrySet().removeIf(entry -> entry.getValue());
        departures.put(key, false);
        Runnable finished = () -> main.post(() -> {
            if (!started || generation != epoch || Boolean.TRUE.equals(departures.get(key))) return;
            host.hide();
            localVisible = false;
            departures.put(key, true);
            departureCompletions.remove(key);
            gone.run();
        });
        departureCompletions.put(key, finished);
        if (!interactive || !localVisible || !host.isReady()) {
            host.hide();
            finished.run();
        } else host.depart(finished);
    }

    private void showLocal() {
        if (!host.isReady()) return;
        if (phone) {
            if (machine == null) {
                if (!ownership.phoneMayStart(mode())) return;
            } else if (!machine.getSnapshot().active.contains(HandoffStateMachine.Site.PHONE)
                    || !rememberOwnership(machine.getSnapshot())) return;
        } else if (!watchAuthorized) return;
        if (interactive) {
            if (!localVisible) host.arrive();
            localVisible = true;
        } else {
            host.hide();
            localVisible = false;
        }
    }

    private boolean rememberOwnership(HandoffStateMachine.Snapshot snapshot) {
        if (ownership.record(snapshot.active)) return true;
        setStatus("Waiting to save companion ownership safely");
        return false;
    }

    private void evaluateTarget() {
        if (!phone || machine == null || !peerSynced) return;
        boolean watchAvailable = peerConnected && peerReady;
        String selected = mode();
        HandoffStateMachine.Target target;
        if ("both".equals(selected) && watchAvailable) target = HandoffStateMachine.Target.BOTH;
        else if ("watch".equals(selected) && watchAvailable) target = HandoffStateMachine.Target.WATCH;
        else if ("auto".equals(selected) && !interactive && peerInteractive && watchAvailable)
            target = HandoffStateMachine.Target.WATCH;
        else target = HandoffStateMachine.Target.PHONE;
        machine.requestTarget(target, SystemClock.elapsedRealtime());
    }

    private void peerLost() {
        if (!peerConnected) return;
        peerConnected = false;
        peerReady = false;
        if (phone && machine != null) {
            machine.onPeerLost(SystemClock.elapsedRealtime());
            evaluateTarget();
        }
        setStatus("Peer disconnected; waiting to confirm ownership");
        scheduleTick();
    }

    private void setStatus(String value) {
        status = value;
        preferences.edit().putString("status", value).apply();
    }

    private void publishCurrentSettings() {
        String json = settingsJson;
        File pack = settingsPack;
        if (json == null || closed) return;
        long sequence = Math.max(preferences.getLong("settings_sequence", 0) + 1,
            System.currentTimeMillis() * 1_000L);
        preferences.edit().putLong("settings_sequence", sequence).apply();
        io.execute(() -> {
            try {
                byte[] bytes = pack == null ? null : readBounded(new FileInputStream(pack), MAX_PACK_BYTES);
                PutDataMapRequest request = PutDataMapRequest.create(SETTINGS_PATH);
                DataMap map = request.getDataMap();
                map.putInt("version", 1);
                map.putString("json", json);
                map.putString("revision", UUID.randomUUID().toString());
                map.putLong("sequence", sequence);
                if (bytes != null) {
                    map.putAsset("skin", Asset.createFromBytes(bytes));
                    map.putString("sha256", sha256(bytes));
                }
                main.post(() -> {
                    if (started) data.putDataItem(request.asPutDataRequest().setUrgent())
                        .addOnFailureListener(error -> main.post(() -> setStatus("Settings sync unavailable")));
                });
            } catch (IOException invalid) {
                main.post(() -> { if (started) setStatus("Skin sync needs a ZIP no larger than 8 MiB"); });
            }
        });
    }

    private void loadCachedSettings() {
        if (phone || !started) return;
        data.getDataItems().addOnSuccessListener(buffer -> {
            try { for (DataItem item : buffer) acceptDataItem(item); }
            finally { buffer.release(); }
        });
    }

    private void acceptDataItem(DataItem item) {
        if (phone || !SETTINGS_PATH.equals(item.getUri().getPath())) return;
        final String sender = item.getUri().getHost();
        try {
            DataMap map = DataMapItem.fromDataItem(item).getDataMap();
            if (map.getInt("version", 0) != 1) return;
            final String json = map.getString("json", "");
            final String revision = map.getString("revision", "");
            final long sequence = map.getLong("sequence", 0);
            final String hash = map.getString("sha256", "");
            final Asset asset = map.getAsset("skin");
            main.post(() -> receiveSettings(sender, json, revision, sequence, hash, asset));
        } catch (RuntimeException ignored) { }
    }

    private void receiveSettings(String sender, String json, String revision, long sequence, String hash, Asset asset) {
        if (!started || sender == null || !sender.equals(peerId) || revision.isEmpty() || revision.equals(lastSettingsRevision)
                || revision.equals(pendingSettingsRevision) || sequence <= lastSettingsSequence ||
                sequence < pendingSettingsSequence || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) return;
        try { new JSONObject(json); } catch (JSONException invalid) { return; }
        pendingSettingsRevision = revision;
        pendingSettingsSequence = sequence;
        if (asset == null) { deliverSettings(sender, json, revision, sequence, null); return; }
        data.getFdForAsset(asset).addOnSuccessListener(response -> {
            if (closed) { response.release(); return; }
            io.execute(() -> {
                File zip = null;
                try {
                    byte[] bytes = readBounded(response.getInputStream(), MAX_PACK_BYTES);
                    if (!sha256(bytes).equals(hash)) throw new IOException("Asset digest mismatch");
                    File directory = new File(context.getCacheDir(), "companion-inbox");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache unavailable");
                    zip = File.createTempFile("skin-", ".zip", directory);
                    try (FileOutputStream out = new FileOutputStream(zip)) { out.write(bytes); }
                    File result = zip;
                    main.post(() -> deliverSettings(sender, json, revision, sequence, result));
                } catch (IOException invalid) {
                    if (zip != null) zip.delete();
                    main.post(() -> {
                        if (!revision.equals(pendingSettingsRevision)) return;
                        pendingSettingsRevision = "";
                        pendingSettingsSequence = 0;
                        if (started) setStatus("Skin sync could not be imported");
                    });
                } finally { response.release(); }
            });
        }).addOnFailureListener(error -> main.post(() -> {
            if (revision.equals(pendingSettingsRevision)) {
                pendingSettingsRevision = "";
                pendingSettingsSequence = 0;
            }
        }));
    }

    private void deliverSettings(String sender, String json, String revision, long sequence, File zip) {
        if (!started || sender == null || !sender.equals(peerId) || !revision.equals(pendingSettingsRevision)) {
            if (zip != null) zip.delete();
            return;
        }
        if (zip != null) inboxFiles.add(zip);
        try { host.onSettings(json, zip); }
        catch (RuntimeException invalid) {
            pendingSettingsRevision = "";
            pendingSettingsSequence = 0;
            setStatus("The received settings could not be applied");
            return;
        }
        lastSettingsRevision = revision;
        lastSettingsSequence = sequence;
        pendingSettingsRevision = "";
        pendingSettingsSequence = 0;
        setStatus("Settings synchronized");
    }

    private static byte[] readBounded(InputStream source, int limit) throws IOException {
        if (source == null) throw new IOException("Missing asset");
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException("Asset too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
