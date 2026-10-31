/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.Slog;
import org.korho.lsdextender.WirelessSettings;
import org.korho.lsdextender.WirelessSettingsState;
import java.io.File;
import java.util.Hashtable;
import org.dsi.ifc.bluetooth.TrustedDevice;

/** Owns settings and publishes a short-lived lease to both native consumers. */
public final class WirelessSettingsService implements WirelessSettings, CommandHandler, Runnable {
    private static WirelessSettingsService instance;
    // Settings always live beside lsd-extender on its card. No alternate path
    // property, internal-storage fallback, or implicit parent-directory creation.
    private final String store = settingsPath();
    private final String mountpoint = mountpointOf(store);
    private final String ppsRoot = ControlDirectory.read();
    private WirelessPolicy policy = new WirelessPolicy();
    private WirelessPolicy pending;
    private long request;
    private boolean ready;
    private boolean busy;
    private volatile boolean stopped;
    private String error = "";
    private String selected;
    private String preferred;
    private final WirelessLaunchRequest launch = new WirelessLaunchRequest();
    private Hashtable active = new Hashtable();
    private Hashtable names = new Hashtable();
    private final Lease lease = new Lease();
    private String saved = "";
    private boolean nativeReady;
    private boolean usbAck;
    private boolean btAck;
    private boolean cancelSession;
    private final Reports reports = new Reports();
    private Thread worker;
    private Thread leaseWorker;
    private volatile WirelessSettingsState state;

    private static String settingsPath() {
        String directory = Config.directory();
        return directory == null ? null : new File(directory, "wireless-aa.conf").getPath();
    }

    /** One normalized absolute object directory, fixed for this feature lifetime. */
    private static final class ControlDirectory {
        private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.";

        static String read() {
            // Config treats an empty -D value as absent; here it is invalid.
            try {
                String explicit = System.getProperty("lsdextender.wireless.pps_root");
                if (explicit != null && explicit.length() == 0) { return null; }
            } catch (SecurityException refused) { return null; }
            String root = Config.get("lsdextender.wireless.pps_root", "/ramdisk/pps/aawg");
            if (root == null || root.length() < 2 || root.length() > 1024 || root.charAt(0) != '/') { return null; }
            int start = 1;
            for (int i = 1; i <= root.length(); i++) {
                if (i == root.length() || root.charAt(i) == '/') {
                    String part = root.substring(start, i);
                    if (part.length() == 0 || part.equals(".") || part.equals("..")) { return null; }
                    start = i + 1;
                } else if (CHARACTERS.indexOf(root.charAt(i)) < 0) { return null; }
            }
            return root;
        }
    }

    /** The filesystem that contains the settings file. */
    private static String mountpointOf(String settings) {
        if (settings == null) { return null; }
        File directory = new File(settings).getParentFile();
        return directory == null ? null : directory.getParent();
    }

    static synchronized void start() {
        if (instance != null) { return; }
        instance = new WirelessSettingsService();
        if (instance.ppsRoot == null) {
            instance.error = "Invalid wireless PPS directory";
            Slog.write(" wireless: invalid PPS directory; service unavailable");
        }
        instance.updateSnapshot();
        LsdExtender.setWirelessSettings(instance);
        LsdExtender.registerCommands(instance);
        if (instance.ppsRoot == null) { return; }
        instance.worker = new Thread(instance, "lsd-wireless-settings");
        instance.worker.setDaemon(true);
        instance.worker.start();
    }

    static void trusted(TrustedDevice[] devices, boolean valid) {
        start();
        if (!valid || devices == null) {
            // "Value not available" is not "no phones". Dropping the list here
            // clears the selection, which unplugs the device from App-Connect,
            // so the last valid list is kept instead.
            instance.reports.noteTrusted(false, 0, 0);
            return;
        }
        Hashtable a = new Hashtable();
        Hashtable n = new Hashtable();
        int mask = Config.getInt("lsdextender.wireless.bt_mask", 0);
        {
            for (int i = 0; i < devices.length && n.size() < WirelessPolicy.MAX_PHONES; i++) {
                TrustedDevice d = devices[i];
                if (d == null) { continue; }
                String addr = WirelessPolicy.address(d.deviceAddress);
                if (addr == null) { continue; }
                n.put(addr, d.deviceName == null ? addr : d.deviceName);
                if (mask == 0 ? d.activeServiceTypes != 0 : (d.activeServiceTypes & mask) != 0) {
                    a.put(addr, Boolean.TRUE);
                }
            }
        }
        synchronized (instance) {
            instance.active = a;
            instance.names = n;
            instance.notifyAll();
        }
        instance.reports.noteTrusted(true, n.size(), a.size());
    }

    static boolean canConnect() {
        WirelessSettingsService s = instance;
        if (s == null) { return false; }
        synchronized (s) {
            return WirelessLaunchRequest.eligible(s.ready, s.stopped, s.selected, s.active, s.policy);
        }
    }

    /** ASL only queues intent. All control and socket I/O stays on the RAM worker. */
    static void prepareLaunch() { prepareLaunch(null); }

    static void prepareLaunch(Runnable continuation) {
        WirelessSettingsService s = instance;
        if (s != null) { s.lease.queue(continuation); }
    }

    static void cancelLaunch() {
        WirelessSettingsService s = instance;
        if (s != null) { s.lease.cancel(); }
    }

    public WirelessSettingsState snapshot() { return state; }

    public synchronized boolean setEnabled(boolean enabled) {
        if (!ready || stopped || (busy && enabled)) { return false; }
        WirelessPolicy p = policy.copy();
        p.enabled = enabled;
        return submit(p, !enabled);
    }

    public synchronized boolean setAllowed(String address, boolean allowed) {
        String addr = WirelessPolicy.address(address);
        if (!ready || stopped || addr == null || (busy && allowed)) { return false; }
        WirelessPolicy p = policy.copy();
        WirelessPolicy.Phone phone = p.remember(addr, null);
        if (phone == null) { return false; }
        phone.allowed = allowed;
        return submit(p, !allowed);
    }

    private boolean submit(WirelessPolicy p, boolean restrictive) {
        pending = p;
        request++;
        busy = true;
        error = "";
        if (restrictive) {
            policy = p;
            selectPhone();
            LsdExtender.setWirelessSettingsRecovery(p.encode());
        }
        updateSnapshot();
        notifyAll();
        return true;
    }

    public void run() {
        try {
            String recovery = LsdExtender.getWirelessSettingsRecovery();
            if (store == null) { throw new java.io.IOException("lsd-extender card directory unavailable"); }
            String disk;
            synchronized (LsdExtender.wirelessSettingsIoLock()) {
                disk = WirelessSettingsStore.read(store);
            }
            WirelessPolicy loaded = recovery != null ? WirelessPolicy.decode(recovery)
                : disk == null ? new WirelessPolicy() : WirelessPolicy.decode(disk);
            synchronized (this) { policy = loaded; saved = disk == null ? "" : disk; }
        } catch (Exception e) {
            synchronized (this) { error = "Cannot read saved settings; wireless AA is off: " + e.getMessage(); }
        }
        synchronized (this) { ready = true; updateSnapshot(); }
        leaseWorker = new Thread(lease, "lsd-wireless-lease");
        leaseWorker.setDaemon(true);
        leaseWorker.start();
        while (!stopped) {
            try { tick(); }
            catch (Exception e) {
                controlFailed(e);
            }
            synchronized (this) {
                if (!stopped && pending == null) {
                    try { wait(1000L); } catch (InterruptedException ignored) { }
                }
            }
        }
    }

    private void controlFailed(Exception e) {
        synchronized (LsdExtender.wirelessControlIoLock()) {
            if (stopped) { return; }
            synchronized (this) {
                error = "Wireless control failed: " + e.getMessage();
                launch.cancel();
                nativeReady = false;
                lease.publishedPhone = null;
                updateSnapshot();
            }
            // Native admission also expires if removal fails.
            try { WirelessFiles.remove(ppsRoot + "/policy"); } catch (Exception ignored) { }
            try { WirelessFiles.remove(ppsRoot + "/phone"); } catch (Exception ignored) { }
        }
        reports.report();
    }

    private void tick() throws Exception {
        WirelessPolicy desired;
        long action;
        boolean savingRequest;
        synchronized (this) {
            java.util.Enumeration keys = names.keys();
            while (keys.hasMoreElements()) {
                String addr = (String)keys.nextElement();
                policy.remember(addr, (String)names.get(addr));
                if (pending != null) { pending.remember(addr, (String)names.get(addr)); }
            }
            desired = pending == null ? policy.copy() : pending.copy();
            savingRequest = pending != null;
            pending = null;
            action = request;
            selectPhone();
        }
        // Publish revocations before attempting flash I/O. Grants wait for a save.
        lease.publish();
        String encoded = desired.encode();
        boolean savedOK = !savingRequest && encoded.equals(saved);
        if (!savedOK) {
            try {
                if (store == null) { throw new java.io.IOException("lsd-extender card directory unavailable"); }
                synchronized (LsdExtender.wirelessSettingsIoLock()) {
                    if (stopped) { return; }
                    saveToCard(encoded);
                }
                synchronized (this) { saved = encoded; }
                savedOK = true;
            } catch (Exception e) {
                synchronized (this) { error = "Not saved; check settings storage: " + e.getMessage(); }
            }
        }
        synchronized (this) {
            if (stopped) { return; }
            if (action == request) {
                if (savedOK && savingRequest) { policy = desired; }
                if (savedOK) { error = ""; }
                busy = false;
            }
            // Preserve the effective state, including an unsaved Off/Block, on reload.
            LsdExtender.setWirelessSettingsRecovery(policy.encode());
            selectPhone();
            notifyAll();
        }
        lease.publish();
        synchronized (this) { updateSnapshot(); }
    }

    private final class Reports {
        private String lastReport = "";
        private String lastTrusted = "";

        private void noteTrusted(boolean valid, int devices, int active) {
            String line = " wireless: bt trusted valid=" + (valid ? "1" : "0")
                + " devices=" + devices + " active=" + active
                + (valid ? "" : " (ignored; keeping the last list)");
            synchronized (WirelessSettingsService.this) {
                if (line.equals(lastTrusted)) { return; }
                lastTrusted = line;
            }
            Slog.write(line);
        }

        private void report() {
            String line;
            synchronized (WirelessSettingsService.this) {
                int allowed = 0;
                for (int i = 0; i < policy.phones.size(); i++) {
                    if (((WirelessPolicy.Phone)policy.phones.elementAt(i)).allowed) { allowed++; }
                }
                line = " wireless: enabled=" + (policy.enabled ? "1" : "0")
                    + " selected=" + (selected == null ? "-" : selected)
                    + " allowed=" + allowed + " remembered=" + policy.phones.size()
                    + " bt_trusted=" + names.size() + " bt_active=" + active.size()
                    + " usb_ack=" + (usbAck ? "1" : "0") + " bt_ack=" + (btAck ? "1" : "0")
                    + " card=" + (store == null ? "unavailable" : savedState())
                    + lease.usbDetail()
                    + (error.length() > 0 ? " error=" + error : "");
                if (line.equals(lastReport)) { return; }
                lastReport = line;
            }
            Slog.write(line);
        }

        private String savedState() {
            return saved.length() == 0 ? "unsaved" : saved.equals(policy.encode()) ? "saved" : "stale";
        }
    }

    /**
     * Save to the card. The card normally mounts read-only, so a first failure
     * is answered by making it writable for the length of the write and putting
     * it back. A card that already accepts the write is never remounted, so a
     * sync in progress is left alone. Caller holds the settings I/O lock.
     */
    private void saveToCard(String encoded) throws Exception {
        try {
            WirelessSettingsStore.write(store, encoded);
            return;
        } catch (Exception refused) {
            if (mountpoint == null
                || !Config.getBool("lsdextender.wireless.card_remount", true)
                || !CardMount.remount(mountpoint, true)) {
                throw refused;
            }
        }
        try {
            WirelessSettingsStore.write(store, encoded);
        } finally {
            CardMount.remount(mountpoint, false);
        }
    }

    private void selectPhone() {
        // A valid Bluetooth snapshot can temporarily omit a connected phone.
        // Keep that choice separately from current transport eligibility: an
        // absent phone must not silently select another permitted phone.
        if (preferred != null && policy.permits(preferred)) {
            selected = active.containsKey(preferred) ? preferred : null;
            return;
        }
        preferred = null;
        selected = null;
        for (int i = 0; i < policy.phones.size(); i++) {
            WirelessPolicy.Phone p = (WirelessPolicy.Phone)policy.phones.elementAt(i);
            if (policy.permits(p.address) && active.containsKey(p.address)) {
                preferred = p.address;
                selected = p.address;
                break;
            }
        }
    }

    /** RAM lease state has its own worker and never waits for card writes. */
    private final class Lease implements Runnable {
        private volatile long revision = System.currentTimeMillis();
        private long epoch = revision;
        private String lastSelection = "";
        private String lastProjection = "";
        private String publishedPhone;

        private void queue(Runnable continuation) {
            synchronized (WirelessSettingsService.this) {
                if (!canConnect()) { return; }
                alignSelection();
                if (continuation == null) { launch.queue(selected, epoch); }
                else { launch.queue(selected, epoch, continuation, System.currentTimeMillis()); }
                WirelessSettingsService.this.notifyAll();
            }
        }

        private void cancel() {
            synchronized (WirelessSettingsService.this) {
                launch.cancel();
                cancelSession = true;
                WirelessSettingsService.this.notifyAll();
            }
        }

        /** Called under the service lock before an intent captures its epoch. */
        private void alignSelection() {
            String selection = (policy.enabled ? "1:" : "0:") + selected;
            if (!selection.equals(lastSelection)) {
                epoch = Math.max(epoch + 1L, System.currentTimeMillis());
                lastSelection = selection;
            }
        }

        private static final long NO_CONTINUATION = 0L;

        private void sendLaunch() throws Exception {
            String request;
            synchronized (WirelessSettingsService.this) {
                request = launch.take(selected, lease.epoch, canConnect());
            }
            WirelessLaunchRequest.send(request);
            String session = null;
            String usb = null;
            try {
                session = WirelessFiles.readControl(ppsRoot + "/session");
                usb = WirelessFiles.readControl(ppsRoot + "/usb_status");
            } catch (Exception unavailable) { /* Missing or unreadable state is not ready. */ }
            final long token;
            synchronized (WirelessSettingsService.this) {
                launch.observe(selected, lease.epoch, canConnect(), lease.revision,
                    session, usb, System.currentTimeMillis());
                if (launch.takeCancellation()) { cancelSession = true; }
                token = de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch == null
                    ? 0L : launch.queueToken();
            }
            if (token == NO_CONTINUATION) { return; }
            de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                public void run() {
                    synchronized (WirelessSettingsService.this) {
                        Runnable action = launch.takeContinuation(token, selected, lease.epoch,
                            !stopped && canConnect(), System.currentTimeMillis());
                        if (action != null) {
                            Slog.write(" wireless: phone TCP ready; running owned launch");
                            action.run();
                        }
                    }
                }
            });
        }

        /** RAM-only control continues while the save worker waits for card I/O. */
        public void run() {
            while (!stopped) {
                try {
                    publish();
                    // Failure preserves first-OUT fallback and valid policy.
                    try { sendLaunch(); }
                    catch (Exception e) { Slog.write(" wireless: early prepare unavailable: " + e); }
                    long observed = revision;
                    boolean bt = acknowledged(ppsRoot + "/bt_status");
                    boolean usb = acknowledged(ppsRoot + "/usb_status");
                    synchronized (WirelessSettingsService.this) {
                        btAck = bt;
                        usbAck = usb;
                        nativeReady = bt && usb && observed == revision;
                        updateSnapshot();
                    }
                    reports.report();
                } catch (Exception e) { controlFailed(e); }
                synchronized (WirelessSettingsService.this) {
                    if (!stopped) {
                        try { WirelessSettingsService.this.wait(launch.pending() ? 100L : 1000L); }
                        catch (InterruptedException ignored) { }
                    }
                }
            }
        }

        private void publish() throws Exception {
            synchronized (LsdExtender.wirelessControlIoLock()) {
                if (stopped) { return; }
                final String address;
                final String displayName;
                String projection;
                boolean clearSession;
                synchronized (WirelessSettingsService.this) {
                    clearSession = cancelSession;
                    cancelSession = false;
                    if (clearSession) {
                        epoch = Math.max(epoch + 1L, System.currentTimeMillis());
                        revision = Math.max(revision + 1L, System.currentTimeMillis());
                    }
                    selectPhone();
                    address = selected;
                    WirelessPolicy.Phone p = policy.find(address);
                    // This label becomes the virtual USB product name in App-Connect.
                    // Keep the transport first so it survives a clipped phone name.
                    displayName = p == null ? null : "Wireless: " + p.name;
                    alignSelection();
                    StringBuffer b = new StringBuffer("enabled=").append(policy.enabled ? '1' : '0')
                        .append("\nselected=").append(address == null ? "-" : address).append('\n');
                    for (int i = 0; i < policy.phones.size(); i++) {
                        p = (WirelessPolicy.Phone)policy.phones.elementAt(i);
                        if (p.allowed) { b.append("allow=").append(p.address).append('\n'); }
                    }
                    projection = b.toString();
                    if (!projection.equals(lastProjection)) {
                        revision = Math.max(revision + 1L, System.currentTimeMillis());
                        lastProjection = projection;
                        nativeReady = false;
                    }
                }
                WirelessFiles.writeControl(ppsRoot + "/policy", "version=1\nrevision=" + revision + "\nepoch=" + epoch + "\nupdated_ms="
                    + System.currentTimeMillis() + "\n" + projection + "end=1\n");
                if (clearSession) {
                    WirelessFiles.remove(ppsRoot + "/session");
                    Slog.write(" wireless: owned launch cancelled; session grant cleared");
                }
                String phone = address == null ? "" : address + " " + displayName + "\n";
                if (!phone.equals(publishedPhone)) {
                    if (address == null) { WirelessFiles.remove(ppsRoot + "/phone"); }
                    else { WirelessFiles.writePps(ppsRoot + "/phone", "value::" + phone.replace('\r', ' ')); }
                    publishedPhone = phone;
                    // Native control is independent of ASL availability; model work is queued.
                    if (de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch != null) {
                        de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                            public void run() {
                                if (stopped) { return; }
                                synchronized (WirelessSettingsService.this) {
                                    if (address == null ? selected != null : !address.equals(selected) || !policy.permits(address)) { return; }
                                }
                                if (address == null) { WirelessDevices.clearPhone(); }
                                else { WirelessDevices.setPhone(displayName, address); }
                            }
                        });
                    }
                }
            }
        }

        /**
         * The extra fields devu-vaa writes beside its acknowledgement: whether a
         * phone stream is connected, how many bytes are held for it, and how the
         * TCP connections went (accepted / refused for no session / refused peer /
         * lost). These counters complement the driver's slog output and distinguish
         * a missing connection from a refused connection. Empty when absent.
         */
        private String usbDetail() {
            try {
                String value = WirelessFiles.readControl(ppsRoot + "/usb_status");
                StringBuffer b = new StringBuffer();
                String[] keys = { "stream", "held", "accepted", "nosession", "badpeer", "lost" };
                for (int i = 0; i < keys.length; i++) {
                    int at = value.indexOf("\n" + keys[i] + "=");
                    if (at < 0) { continue; }
                    int from = at + keys[i].length() + 2;
                    int to = value.indexOf('\n', from);
                    if (to < 0) { continue; }
                    b.append(' ').append(keys[i]).append('=').append(value.substring(from, to));
                }
                return b.toString();
            } catch (Exception e) { return ""; }
        }

        private boolean acknowledged(String path) {
            try {
                String value = WirelessFiles.readControl(path);
                String expected = "revision=" + revision + "\n";
                int i = value.indexOf("updated_ms=");
                if (!value.startsWith(expected) || i < 0 || !value.endsWith("end=1\n")) { return false; }
                int end = value.indexOf('\n', i);
                long age = System.currentTimeMillis() - Long.parseLong(value.substring(i + 11, end));
                return age >= 0 && age <= 10000L;
            } catch (Exception e) { return false; }
        }
    }

    private void updateSnapshot() {
        int count = policy.phones.size();
        String[] addresses = new String[count];
        String[] labels = new String[count];
        boolean[] permissions = new boolean[count];
        boolean[] connected = new boolean[count];
        for (int i = 0; i < count; i++) {
            WirelessPolicy.Phone p = (WirelessPolicy.Phone)policy.phones.elementAt(i);
            addresses[i] = p.address;
            labels[i] = p.name;
            permissions[i] = p.allowed;
            connected[i] = active.containsKey(p.address);
        }
        String status = error.length() > 0 ? error : !ready ? "Loading settings..." : busy ? "Saving..."
            : !nativeReady ? "Waiting for wireless services" : policy.enabled ? "Wireless AA is on" : "Wireless AA is off";
        state = new WirelessSettingsState(ready, policy.enabled, busy, status, addresses, labels, permissions, connected);
    }

    public String command(String line) { return Commands.command(this, line); }

    /** Text protocol over the same settings API used by the HMI. */
    private static final class Commands {
        static String command(WirelessSettingsService service, String line) {
            if (!line.equals("wireless") && !line.startsWith("wireless ")) { return null; }
            String arg = line.length() == 8 ? "status" : line.substring(9).trim();
            if (arg.equals("status")) {
                WirelessSettingsState s = service.snapshot();
                StringBuffer b = new StringBuffer("wireless enabled=").append(s.enabled).append(" busy=").append(s.busy)
                    .append(" storage=").append(service.store == null ? "card unavailable" : service.store).append(" status=").append(s.status);
                for (int i = 0; i < s.size(); i++) {
                    b.append("; ").append(s.address(i)).append(s.allowed(i) ? " allowed " : " blocked ")
                        .append(s.connected(i) ? "connected " : "offline ").append(s.name(i));
                }
                return b.toString();
            }
            boolean accepted;
            if (arg.equals("on") || arg.equals("off")) { accepted = service.setEnabled(arg.equals("on")); }
            else if (arg.startsWith("allow ")) { accepted = service.setAllowed(arg.substring(6), true); }
            else if (arg.startsWith("block ")) { accepted = service.setAllowed(arg.substring(6), false); }
            else { return "err usage: wireless status|on|off|allow ADDRESS|block ADDRESS"; }
            return accepted ? "queued; wireless status reports save and native application" : "err wireless busy, unavailable, invalid address, or phone limit reached";
        }
    }

    /** Invoked by core before dropping this feature generation. */
    public void shutdown() {
        synchronized (this) { stopped = true; launch.cancel(); notifyAll(); }
        try { if (worker != null) { worker.join(5000L); } } catch (InterruptedException ignored) { }
        try { if (leaseWorker != null) { leaseWorker.join(5000L); } } catch (InterruptedException ignored) { }
        // The lease expires if the replacement feature is absent or cannot start.
    }
}
