/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.util.Vector;

/**
 * Core dispatcher for owned event listeners and feature hooks.
 * Features register their callbacks through this class.
 *
 * Contract with the hooks: every entry point is called from inside a
 * try/catch(Throwable), returns quickly, and never runs work in a
 * static initializer beyond a slog line. Features live in their own jars, register
 * themselves when their class loads, and are looked up by name here so that core
 * never references a class whose jar may be absent.
 *
 * Source level is Java 1.2 (J9 2.3, CDC Foundation 1.1): no generics, no autoboxing,
 * no varargs, no StringBuilder.
 */
public final class LsdExtender {
    private static final String RELOAD_LOG = " reload: ";
    /** Feature classes core will try to load after boot; absent jars are skipped. */
    private static final String[] KNOWN_FEATURES = new String[]{
        "org.korho.lsdextender.wirelessaa.WirelessAA",
        "org.korho.lsdextender.wirelessaa.BtWatch",
        "org.korho.lsdextender.kombisync.KombiSync",
        "org.korho.lsdextender.dsispy.DsiSpy",
        "org.korho.lsdextender.keys.Keys",
        "org.korho.lsdextender.ev.EvFile",
        "org.korho.lsdextender.aaguidance.AaGuidance",
        "org.korho.lsdextender.audio.Audio",
    };

    /** Command handlers for the TCP feed (see CommandHandler). */
    private static final Vector commandHandlers = new Vector();

    public static void registerCommands(CommandHandler h) {
        synchronized (commandHandlers) {
            commandHandlers.addElement(h);
        }
    }

    /** A line from a feed client. Core commands first, then the features. */
    public static String command(String line) {
        if (RestartCommand.NAME.equals(line.trim())) { return Commands.RESTART.command(RestartCommand.NAME); }
        return Commands.command(line);
    }

    private static final class Commands {
        private static final String SET_PREFIX = "set ";
        private static final CommandHandler RESTART = new RestartCommand();
        static String command(String line) {
            line = line.trim();
            if (line.length() == 0) {
                return null;
            }
            Slog.write(" cmd: " + line);
            if (line.equals("help")) {
                return "ok core: help | set key=value | get key | features | reload | restart | spawn <command> | observe <aslEventId> | unobserve <id> | features answer the rest (subscribe, unsubscribe, intercept ...)";
            }
            if (line.equals("reload")) {
                try {
                    return "ok " + reloadFeatures();
                } catch (Throwable t) {
                    return "err reload failed: " + t;
                }
            }
            if (line.equals("capabilities")) { return "ok reload-ui=1"; }
            if (line.startsWith("spawn ")) {
                // same runner as the conf lines; blocks this connection's reader for at most
                // the spawn timeout, other clients are unaffected
                String report = Spawn.run(line.substring(6).trim());
                Slog.write(report);
                return "ok" + report;
            }
            if (line.startsWith("set ")) {
                int eq = line.indexOf('=');
                if (eq <= SET_PREFIX.length()) {
                    return "err usage: set key=value";
                }
                Config.override(line.substring(4, eq).trim(), line.substring(eq + 1).trim());
                return "ok " + line.substring(4, eq).trim() + " set (until reboot)";
            }
            if (line.startsWith("get ")) {
                String v = Config.get(line.substring(4).trim(), null);
                return "ok " + line.substring(4).trim() + "=" + (v == null ? "(unset)" : v);
            }
            if (line.startsWith("observe ") || line.startsWith("unobserve ")) {
                return observe(line);
            }
            if (line.equals("features")) {
                StringBuffer b = new StringBuffer("ok ").append(RuntimeCore.banner());
                synchronized (listeners) {
                    for (int i = 0; i < listeners.size(); i++) {
                        b.append("; ").append(((MflListener) listeners.elementAt(i)).name());
                    }
                }
                return b.toString();
            }
            Object[] hs;
            synchronized (commandHandlers) {
                hs = new Object[commandHandlers.size()];
                commandHandlers.copyInto(hs);
            }
            for (int i = 0; i < hs.length; i++) {
                try {
                    String r = ((CommandHandler) hs[i]).command(line);
                    if (r != null) {
                        return r;
                    }
                } catch (Throwable t) {
                    return "err " + t;
                }
            }
            return "err unknown command: " + line;
        }

        private static String observe(String line) {
            // Published ASL services: register our target independently, so the
            // event is delivered independently to our receiver
            boolean on = line.startsWith("observe ");
            int id;
            try {
                id = Integer.parseInt(line.substring(on ? 8 : 10).trim());
            } catch (Throwable t) {
                return "err usage: observe <eventId>";
            }
            HsmTarget t = smartphoneTarget;
            if (t == null) {
                return "err no SI target yet";
            }
            if (on) {
                if (!observed.contains(new Integer(id))) {
                    if (!observeAsl(id)) { return "err own ASL observer unavailable"; }
                    observed.addElement(new Integer(id));
                }
                return "ok observing ASL event " + id;
            }
            observed.removeElement(new Integer(id));
            return "ok no longer logging ASL event " + id + " (the observer registration stays until reboot)";
        }
    }

    /** Hardkey listeners (see KeyListener); fed by the keys feature. */
    private static final Vector keyListeners = new Vector();

    public static void registerKeys(KeyListener l) {
        synchronized (keyListeners) {
            keyListeners.addElement(l);
        }
    }

    private static final Vector touchListeners = new Vector();

    public static void registerTouch(TouchListener listener) {
        synchronized (touchListeners) { touchListeners.addElement(listener); }
    }

    /** Observe only. Listeners enqueue work and cannot consume stock input. */
    public static void onTouch(int gesture, int x, int y, long time) {
        Object[] snapshot;
        synchronized (touchListeners) {
            snapshot = new Object[touchListeners.size()]; touchListeners.copyInto(snapshot);
        }
        for (int i = 0; i < snapshot.length; i++) {
            try { ((TouchListener)snapshot[i]).onTouch(gesture, x, y, time); }
            catch (Throwable failure) { Slog.write(" touch observer failed: " + failure); }
        }
    }

    /** Rotary encoder listeners (see EncoderListener); fed by the keys feature. */
    private static final Vector encoderListeners = new Vector();

    public static void registerEncoder(EncoderListener l) {
        synchronized (encoderListeners) {
            encoderListeners.addElement(l);
        }
    }

    public static void onEncoder(int keyboard, int key, int inc) {
        Object[] ls;
        synchronized (encoderListeners) {
            ls = new Object[encoderListeners.size()];
            encoderListeners.copyInto(ls);
        }
        for (int i = 0; i < ls.length; i++) {
            try {
                if (((EncoderListener) ls[i]).onEncoder(keyboard, key, inc)) {
                    return;
                }
            } catch (Throwable t) {
                Slog.write(" encoder listener failed: " + t);
            }
        }
    }

    public static void onKey(int keyboard, int key, int state) {
        Object[] ls;
        synchronized (keyListeners) {
            ls = new Object[keyListeners.size()];
            keyListeners.copyInto(ls);
        }
        for (int i = 0; i < ls.length; i++) {
            try {
                if (((KeyListener) ls[i]).onKey(keyboard, key, state)) {
                    return;
                }
            } catch (Throwable t) {
                Slog.write(" key listener failed: " + t);
            }
        }
    }

    /** Native SI identity read through its public helper, used for legacy read-only context. */
    private static HsmTarget smartphoneTarget;

    public static HsmTarget siTarget() {
        return smartphoneTarget;
    }

    /** Our independently registered target for DSI and published-service clients. */
    public static de.vw.mib.genericevents.Target nativeTarget() { return OwnedAslTarget.target(); }

    private static final Vector listeners = new Vector();
    private static boolean bannerDone;
    private static boolean featuresLoaded;

    private LsdExtender() {
    }

    /**
     * Announce an owned class and its feature archive after loading.
     * The first call prints the core build banner; every call names the class
     * and the build stamp of its archive.
     */
    public static void announce(String className, String jarBanner) {
        synchronized (LsdExtender.class) {
            if (!bannerDone) {
                bannerDone = true;
                Slog.write(" " + RuntimeCore.banner());
            }
        }
        Slog.write(" loaded override " + className + " [" + jarBanner + "]");
    }

    /** Owned wireless launch preparation; no stock list or selection interception. */
    private static volatile WirelessHook wirelessHook;

    /** The runtime wireless jar registers its hook here on load (null = clear). */
    public static void setWirelessHook(WirelessHook h) {
        wirelessHook = h;
        Slog.write(" wireless hook " + (h == null ? "cleared" : "registered: " + h.getClass().getName()));
    }

    /** The hooks read it here; null when no runtime jar is loaded. */
    public static WirelessHook getWirelessHook() {
        return wirelessHook;
    }

    private static volatile WirelessSettings wirelessSettings;
    // A string, never a feature object: restrictive changes survive feature reload
    // even when flash is unavailable. The feature retries the durable write.
    private static String wirelessSettingsRecovery;
    private static final Object wirelessIoMonitor = new Object();
    private static final Object wirelessControlMonitor = new Object();

    /** Serializes worker I/O across feature generations; never used by widgets. */
    public static Object wirelessSettingsIoLock() {
        return wirelessIoMonitor;
    }

    /** Lease renewal must never wait behind settings-card I/O. */
    public static Object wirelessControlIoLock() {
        return wirelessControlMonitor;
    }

    public static void setWirelessSettings(WirelessSettings settings) {
        wirelessSettings = settings;
    }

    public static WirelessSettings getWirelessSettings() {
        return wirelessSettings;
    }

    public static synchronized void setWirelessSettingsRecovery(String value) {
        wirelessSettingsRecovery = value;
    }

    public static synchronized String getWirelessSettingsRecovery() {
        return wirelessSettingsRecovery;
    }

    /** Features call this from their static initializer. */
    public static void register(MflListener l) {
        synchronized (listeners) {
            listeners.addElement(l);
        }
        Slog.write(" feature registered: " + l.name());
    }

    /**
     * Load feature classes so their static initializers can register. Runs once,
     * after the smartphone-integration target exists, so the HMI is well past early
     * boot. Two sources: classes bundled into lsd-extender.jar itself (the boot class
     * path), and feature-only jars listed as "jar" lines in lsd-extender.conf, which
     * are opened at runtime with a class loader of our own. Known feature classes are
     * tried in both places; "feature" lines name additional ones.
     */
    static void loadFeatures() {
        synchronized (LsdExtender.class) {
            if (featuresLoaded) {
                return;
            }
            featuresLoaded = true;
        }
        String[] extra = Config.featureNames();
        // 1. bundled
        for (int i = 0; i < KNOWN_FEATURES.length; i++) {
            RuntimeJars.loadFeature(KNOWN_FEATURES[i], null, "bundled");
        }
        for (int i = 0; i < extra.length; i++) {
            RuntimeJars.loadFeature(extra[i], null, "bundled");
        }
        // 2. runtime jars from the conf
        loadRuntimeJars(extra);
        Slog.write(" features loaded: " + listeners.size() + " registered");
    }

    /** The class loader of the runtime jars currently in use (null before the first load). */
    private static ClassLoader runtimeLoader;
    private static int generation;
    public static boolean isReloading() { return RuntimeReload.busy; }

    private static void loadRuntimeJars(String[] extra) {
        String[] jars = Config.jarPaths();
        if (jars.length == 0) {
            return;
        }
        ClassLoader loader = RuntimeJars.open(jars);
        if (loader == null) {
            return;
        }
        RuntimeJars.activate(loader, extra, "loaded from conf jar", false);
    }

    /**
     * The "reload" verb: forget the conf, take back everything the runtime jars
     * registered (listeners, keys, encoders, commands, deferred work, DSI
     * subscriptions; a feature's public no-arg shutdown() is called first so it can
     * stop its threads), then read the conf again and load the runtime jars afresh
     * from new RAM copies with a new class loader. Bundle classes are untouched: they
     * are on the boot class path and need a reboot. Returns the reply line.
     */
    public static String reloadFeatures() {
        return RuntimeReload.run();
    }

    /** Native cleanup ACK only schedules bounded owned file retirement off ASL. */
    static void runtimeSubscriptionRetired() { RuntimeJars.cleanupAsync(); }

    private static final class RuntimeReload {
        private static final Object lock = new Object();
        private static volatile boolean busy;
        private static void setBusy(boolean value) { busy = value; }

        static String run() {
            synchronized (lock) {
                setBusy(true);
                try { checkGuards(); RuntimeJars.sweep(true); return replaceRuntimeFeatures(); }
                finally { setBusy(false); }
            }
        }

        private static void checkGuards() {
            Object[] guards;
            synchronized (listeners) {
                guards = new Object[listeners.size()];
                listeners.copyInto(guards);
            }
            for (int i = 0; i < guards.length; i++) {
                if (guards[i] instanceof ReloadGuard) { ((ReloadGuard)guards[i]).prepareReload(); }
            }
        }

        private static String replaceRuntimeFeatures() {
            ClassLoader old;
            synchronized (LsdExtender.class) {
                old = runtimeLoader;
            }
            String[] previousNames = Config.featureNames();
            RuntimeJars.Image previous = RuntimeJars.previous(old);
            Config.Snapshot config = Config.snapshot();
            String coreLabel = RuntimeCore.banner();
            ClassLoader next;
            try {
                Config.reload();
                RuntimeCore.refresh();
                next = RuntimeJars.open(Config.jarPaths(), true);
            } catch (Throwable failed) {
                Config.restore(config); RuntimeCore.restore(coreLabel);
                throw new IllegalStateException("reload preparation failed; previous runtime unchanged: " + failed);
            }
            int dropped = 0;
            int subs = 0;
            int before = listeners.size();
            try {
                if (old != null) {
                    int[] counts = retireRegistrations(old);
                    dropped = counts[0]; subs = counts[1];
                }
                before = listeners.size();
                RuntimeJars.activate(next, Config.featureNames(), "loaded", true);
            } catch (Throwable failed) {
                Config.restore(config); RuntimeCore.restore(coreLabel);
                rollback(next, old, previous, previousNames, failed);
            }
            RuntimeJars.discard(old);
            DeferredPass.queue("reload generation " + generation);
            String r = RELOAD_LOG + dropped + " registrations and " + subs + " DSI subscriptions dropped, "
                + (listeners.size() - before) + " features registered again from " + Config.jarPaths().length
                + " jar(s) (generation " + generation + ")";
            r = r + "; core: " + RuntimeCore.banner();
            String loaded = runtimeFeatureBanners();
            if (loaded.length() > 0) {
                r = r + "; loaded: " + loaded;
            }
            r = r + RuntimeJars.retirementStatus();
            Slog.write(r);
            return r.trim();
        }

        private static void rollback(ClassLoader failedLoader, ClassLoader old, RuntimeJars.Image previous, String[] names, Throwable cause) {
            Slog.write(RELOAD_LOG + EventClock.stamp() + " candidate failed: " + cause);
            try {
                Throwable cleanupFailure = null;
                if (failedLoader != null) {
                    try { retireRegistrations(failedLoader); }
                    catch (Throwable failed) { cleanupFailure = failed; }
                }
                if (old != null) {
                    try { retireRegistrations(old); }
                    catch (Throwable failed) { cleanupFailure = failed; }
                }
                if (cleanupFailure != null) { throw new IllegalStateException("runtime cleanup remains unproved: " + cleanupFailure); }
                RuntimeJars.activate(previous == null ? null : previous.reopen(), names, "rolled back", true);
                RuntimeJars.discard(old);
                DeferredPass.queue("reload rollback generation " + generation);
                RuntimeJars.discard(failedLoader);
            } catch (Throwable failed) {
                RuntimeJars.discard(failedLoader);
                throw new IllegalStateException("reload failed: " + cause + "; rollback failed: " + failed);
            }
            throw new IllegalStateException("reload failed: " + cause + "; previous runtime features and in-memory config rolled back" + RuntimeJars.retirementStatus());
        }

        private static int[] retireRegistrations(ClassLoader loader) {
            int[] counts = new int[2]; Throwable failure = null;
            try { counts[0] = unregisterAll(loader); }
            catch (Throwable failed) { failure = failed; }
            try { counts[1] = DsiSubscribe.unsubscribeAll(loader); }
            catch (Throwable failed) { failure = failed; }
            clearWireless(loader);
            if (failure != null) { throw new IllegalStateException("runtime retirement failed: " + failure); }
            return counts;
        }

        private static void clearWireless(ClassLoader loader) {
            WirelessHook wh = wirelessHook;
            if (wh != null && DsiSubscribe.loaderOf(wh) == loader) { wirelessHook = null; }
            WirelessSettings ws = wirelessSettings;
            if (ws != null && DsiSubscribe.loaderOf(ws) == loader) { wirelessSettings = null; }
        }

        /**
         * The names (with build banners) of the features registered by the current
         * runtime class loader, so the reload reply says WHICH feature at WHICH
         * version is now live, not just a count and a generation number. Each
         * feature's name() carries its Build.BANNER (feature, version, build time,
         * git rev). Only MflListener features are listed; a feature that registers
         * only keys/encoders/commands is counted but not named here.
         */
        private static String runtimeFeatureBanners() {
            ClassLoader rl;
            synchronized (LsdExtender.class) {
                rl = runtimeLoader;
            }
            if (rl == null) {
                return "";
            }
            StringBuffer b = new StringBuffer();
            synchronized (listeners) {
                for (int i = 0; i < listeners.size(); i++) {
                    MflListener l = (MflListener) listeners.elementAt(i);
                    if (DsiSubscribe.loaderOf(l) == rl) {
                        if (b.length() > 0) {
                            b.append("; ");
                        }
                        b.append(l.name());
                    }
                }
            }
            return b.toString();
        }
    }

    /**
     * Remove every registered object that the given loader owns, calling its
     * shutdown() first when it has one. Returns the count.
     */
    private static int unregisterAll(ClassLoader loader) {
        int n = 0;
        n += dropFrom(listeners, loader, "feature");
        n += dropFrom(keyListeners, loader, "key listener");
        n += dropFrom(touchListeners, loader, "touch observer");
        n += dropFrom(encoderListeners, loader, "encoder listener");
        n += dropFrom(aslListeners, loader, "ASL listener");
        n += dropFrom(commandHandlers, loader, "command handler");
        // deferred entries are {Deferred, int[] runs}
        Object[] items;
        synchronized (deferred) {
            items = new Object[deferred.size()];
            deferred.copyInto(items);
        }
        for (int i = 0; i < items.length; i++) {
            Object d = ((Object[]) items[i])[0];
            if (DsiSubscribe.loaderOf(d) == loader) {
                synchronized (deferred) {
                    deferred.removeElement(items[i]);
                }
                n++;
            }
        }
        DeferredPass.armRetry();
        return n;
    }

    private static final java.util.Hashtable shutDown = new java.util.Hashtable();

    private static int dropFrom(Vector v, ClassLoader loader, String what) {
        Object[] items;
        synchronized (v) {
            items = new Object[v.size()];
            v.copyInto(items);
        }
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            Object o = items[i];
            if (DsiSubscribe.loaderOf(o) != loader) {
                continue;
            }
            if (!shutDown.containsKey(o)) {
                if (!RuntimeJars.shutdown(o)) { RuntimeJars.retain(loader, o); }
                shutDown.put(o, o);
            }
            synchronized (v) {
                v.removeElement(o);
            }
            Slog.write(RELOAD_LOG + what + " " + o.getClass().getName() + " dropped");
            n++;
        }
        if (v == commandHandlers) { // NOPMD CompareObjectsWithEquals - only this registry ends the reload cleanup.
            shutDown.clear();
        }
        return n;
    }

    private static final class RuntimeJars {
        static void activate(ClassLoader loader, String[] extra, String where, boolean strict) {
            synchronized (LsdExtender.class) { runtimeLoader = loader; }
            if (loader == null) { return; }
            for (int i = 0; i < KNOWN_FEATURES.length; i++) {
                if (!RuntimeJars.isRegistered(KNOWN_FEATURES[i])) {
                    boolean present = loader.getResource(KNOWN_FEATURES[i].replace('.', '/') + ".class") != null;
                    if (!RuntimeJars.loadFeature(KNOWN_FEATURES[i], loader, where) && strict && present) {
                        throw new IllegalStateException("present runtime feature failed: " + KNOWN_FEATURES[i]);
                    }
                }
            }
            for (int i = 0; i < extra.length; i++) {
                if (!RuntimeJars.isRegistered(extra[i]) && !RuntimeJars.loadFeature(extra[i], loader, where)) {
                    throw new IllegalStateException("configured runtime feature failed: " + extra[i]);
                }
            }
        }

        private static final java.util.Hashtable images = new java.util.Hashtable();

        private static final class Image {
            private final java.net.URL[] urls;
            private final java.io.File[] files;
            private final boolean immutable;
            private final Vector failures = new Vector();
            Image(java.net.URL[] urls, java.io.File[] files, boolean immutable) {
                this.urls = urls; this.files = files; this.immutable = immutable;
            }
            ClassLoader reopen() {
                ClassLoader loader = new java.net.URLClassLoader(urls, LsdExtender.class.getClassLoader());
                images.put(loader, this);
                return loader;
            }
        }

        static Image previous(ClassLoader loader) {
            if (loader == null) { return null; }
            Image image = (Image) images.get(loader);
            if (image == null || !image.immutable) {
                throw new IllegalStateException("reload refused: previous immutable RAM snapshots unavailable");
            }
            for (int i = 0; i < image.files.length; i++) {
                if (!image.files[i].isFile()) { throw new IllegalStateException("reload refused: previous RAM snapshot missing"); }
            }
            return image;
        }

        private static final Vector retired = new Vector();
        private static final Object retirementLock = new Object();
        private static boolean retirementWorker;
        private static boolean retirementRequested;

        static void discard(ClassLoader loader) {
            if (loader == null) { return; }
            Image image = (Image) images.remove(loader);
            if (image == null) { return; }
            synchronized (retired) { retired.addElement(new Object[]{loader, image}); }
            sweep(false);
        }

        static void retain(ClassLoader loader, Object feature) {
            Image image = (Image) images.get(loader);
            if (image != null && !image.failures.contains(feature)) { image.failures.addElement(feature); }
        }

        static boolean shutdown(Object feature) {
            try {
                java.lang.reflect.Method method = feature.getClass().getMethod("shutdown", new Class[0]);
                method.invoke(feature, new Object[0]);
                return true;
            } catch (NoSuchMethodException none) {
                return true;
            } catch (java.lang.reflect.InvocationTargetException failed) {
                return shutdownFailed(feature, failed.getTargetException());
            } catch (Throwable failed) {
                return shutdownFailed(feature, failed);
            }
        }

        private static boolean shutdownFailed(Object feature, Throwable cause) {
            Slog.write(RELOAD_LOG + EventClock.stamp() + " shutdown of " + feature.getClass().getName()
                + " failed; lifetime UNKNOWN: " + cause);
            return false;
        }

        static String retirementStatus() {
            synchronized (retired) {
                return retired.isEmpty() ? "" : "; previous retirement UNKNOWN: " + retired.size() + " retained loader(s)";
            }
        }

        private static Object[] retiredImages() {
            synchronized (retired) {
                Object[] result = new Object[retired.size()]; retired.copyInto(result); return result;
            }
        }

        private static boolean blocked(Image image) {
            if (images.contains(image) || !image.failures.isEmpty()) { return true; }
            Object[] records = retiredImages();
            for (int i = 0; i < records.length; i++) {
                Object[] record = (Object[]) records[i];
                if (record[1] == image && DsiSubscribe.hasSubscriptions((ClassLoader) record[0])) { return true; } // NOPMD CompareObjectsWithEquals - exact shared snapshot ownership.
            }
            return false;
        }

        static void sweep(boolean retryShutdown) {
            synchronized (retirementLock) { sweepOwned(retryShutdown); }
        }

        private static void sweepOwned(boolean retryShutdown) {
            Object[] records = retiredImages();
            java.util.Hashtable attempted = new java.util.Hashtable();
            for (int i = 0; i < records.length; i++) {
                Object[] record = (Object[]) records[i]; Image image = (Image) record[1];
                if (retryShutdown && !attempted.containsKey(image)) {
                    attempted.put(image, image);
                    Object[] failures = new Object[image.failures.size()]; image.failures.copyInto(failures);
                    for (int j = 0; j < failures.length; j++) {
                        if (shutdown(failures[j])) { image.failures.removeElement(failures[j]); }
                    }
                }
                if (DsiSubscribe.hasSubscriptions((ClassLoader) record[0]) || !image.failures.isEmpty()) { continue; }
                boolean removed = true;
                if (!blocked(image)) {
                    for (int j = 0; j < image.files.length; j++) {
                        if (image.files[j].exists() && !image.files[j].delete()) {
                            removed = false;
                            Slog.write(RELOAD_LOG + EventClock.stamp() + " RAM snapshot cleanup failed: " + image.files[j]);
                        }
                    }
                }
                if (removed) { synchronized (retired) { retired.removeElement(record); } }
            }
        }

        static void cleanupAsync() {
            synchronized (retired) {
                retirementRequested = true;
                if (retirementWorker || retired.isEmpty()) { return; }
                retirementWorker = true;
            }
            try {
                Thread worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int pass = 0; pass < 2; pass++) {
                            synchronized (retired) { retirementRequested = false; }
                            sweep(false);
                            synchronized (retired) { if (!retirementRequested) { return; } }
                        }
                    } finally {
                        boolean again;
                        synchronized (retired) { retirementWorker = false; again = retirementRequested; }
                        if (again) { cleanupAsync(); }
                    }
                }
            }, "lsd-extender-runtime-retirement");
                worker.setDaemon(true); worker.start();
            } catch (Throwable failed) {
                synchronized (retired) { retirementWorker = false; }
                Slog.write(RELOAD_LOG + EventClock.stamp() + " retirement worker unavailable; sources retained UNKNOWN: " + failed);
            }
        }
        private static boolean isRegistered(String className) {
            synchronized (listeners) {
                for (int i = 0; i < listeners.size(); i++) {
                    if (listeners.elementAt(i).getClass().getName().equals(className)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean loadFeature(String className, ClassLoader loader, String where) {
            try {
                if (loader == null) {
                    Class.forName(className);
                } else {
                    Class.forName(className, true, loader);
                }
                Slog.write(RELOAD_LOG + EventClock.stamp() + " feature " + className + " " + where);
                return true;
            } catch (ClassNotFoundException e) {
                if (loader != null) {
                    Slog.write(" feature " + className + " not in the conf jars either");
                }
            } catch (Throwable t) {
                Slog.write(RELOAD_LOG + EventClock.stamp() + " feature " + className + " failed (" + where + "): " + t);
            }
            return false;
        }

        /**
         * A URLClassLoader over the conf's jar lines, parent = bootstrap (sees stock + core).
         * Each jar is copied to /dev/shmem first and loaded from there: the loader reads
         * classes lazily, and a jar rewritten in place on the card under a running HMI
         * (a card update) would otherwise serve it a mix of old and new classes. The copy
         * also gives a reload a file the previous loader never opened.
         */
        static ClassLoader open(String[] paths) {
            return open(paths, false);
        }

        static ClassLoader open(String[] paths, boolean strict) {
            java.util.Vector copies = new java.util.Vector();
            try {
                int gen;
                synchronized (LsdExtender.class) {
                    gen = ++generation;
                }
                java.util.Vector urls = new java.util.Vector();
                boolean immutable = true;
                for (int i = 0; i < paths.length; i++) {
                    java.io.File f = new java.io.File(paths[i]);
                    if (!f.exists()) {
                        if (strict) { throw new IllegalStateException("configured jar missing: " + paths[i]); }
                        Slog.write(" conf jar MISSING, skipped: " + paths[i]);
                        continue;
                    }
                    java.io.File use = ramCopy(f, gen);
                    if (use.equals(f)) { immutable = false; }
                    else { copies.addElement(use); }
                    if (strict && use.equals(f)) { throw new IllegalStateException("candidate immutable RAM snapshot unavailable: " + paths[i]); }
                    if (strict) { validateArchive(use); }
                    urls.addElement(use.toURL());
                    Slog.write(" conf jar: " + paths[i] + (use.equals(f) ? "" : " (copy " + use.getPath() + ")"));
                }
                if (urls.isEmpty()) {
                    return null;
                }
                java.net.URL[] arr = new java.net.URL[urls.size()];
                urls.copyInto(arr);
                // parent = whatever loaded core: the bootstrap loader on the unit (bundle on
                // -Xbootclasspath/p:), the application loader in the off-car harness
                java.io.File[] files = new java.io.File[copies.size()];
                copies.copyInto(files);
                return new Image(arr, files, immutable).reopen();
            } catch (Throwable t) {
                for (int i = 0; i < copies.size(); i++) { ((java.io.File) copies.elementAt(i)).delete(); }
                if (strict) { throw new IllegalStateException("candidate runtime preparation failed: " + t); }
                Slog.write(" conf jars: cannot open (runtime jar loading unavailable here): " + t);
                return null;
            }
        }

        private static void validateArchive(java.io.File file) throws java.io.IOException {
            java.util.zip.ZipFile archive = null;
            try { archive = new java.util.zip.ZipFile(file); }
            finally { if (archive != null) { archive.close(); } }
        }

        /** Copy a jar to the RAM directory for this generation; the original when that fails. */
        private static java.io.File ramCopy(java.io.File f, int gen) {
            String dir = Config.get("lsdextender.jar_copy_dir", "/dev/shmem");
            if (dir.length() == 0 || !new java.io.File(dir).isDirectory()) {
                return f;
            }
            java.io.File out = new java.io.File(dir, "lsd-extender-g" + gen + "-" + f.getName());
            java.io.FileInputStream in = null;
            java.io.FileOutputStream o = null;
            try {
                in = new java.io.FileInputStream(f);
                o = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    o.write(buf, 0, r);
                }
                o.close();
                o = null;
                return out;
            } catch (Throwable t) {
                Slog.write(" conf jar: copy to " + dir + " failed, loading from the card: " + t);
                out.delete();
                return f;
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                    if (o != null) {
                        o.close();
                    }
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * From the owned worker, once, after our target has been registered: the
     * smartphone-integration target exists, so the HMI is past early boot. Starts
     * the feed, the features and the conf's spawn lines.
     */
    static void siTargetReady(HsmTarget target) {
        RuntimeCore.refresh();
        smartphoneTarget = target;
        trace(target, " smartphone-integration target ready; starting features");
        try {
            Publisher.start();
        } catch (Throwable t) {
            Slog.write(" publish: start failed: " + t);
        }
        String conf = Config.confPath();
        trace(target, " config in use: " + (conf == null ? "none (defaults)" : conf));
        loadFeatures();
        try {
            Spawn.startAll();
        } catch (Throwable t) {
            Slog.write(" spawn: start failed: " + t);
        }
        ClusterEarly.start();
        org.korho.lsdextender.boot.HookInstaller.activated();
        // Preparation above runs on our worker. Native deferred setup stays on ASL.
        de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
            public void run() { OwnedAslTarget.enable(); runDeferred(); }
        });
    }

    /** Deferred work: first ASL pass, prompt late registration, then SI-event retries. */
    private static final Vector deferred = new Vector();
    private static final int MAX_DEFERRED_RUNS = 500;
    private static final String DEFERRED_LOG = " deferred: ";

    public static void defer(Deferred d) {
        synchronized (deferred) {
            if (DeferredPass.closed) { return; }
            deferred.addElement(new Object[]{d, new int[]{0}, new long[]{System.currentTimeMillis() + 30000L}});
        }
        Slog.write(DEFERRED_LOG + d.name());
        DeferredPass.registered();
    }

    /**
     * One deferred pass on the ASL thread, queued through the stock
     * ServiceManager.aslThreadSwitch (the ThreadSwitchingTarget of task hsmtask,
     * where our event receiver also runs, so the pass never overlaps one
     * from an SI event). A reload runs on the feed command thread and an SI
     * receiver event may not follow it, so the new generation's deferred work
     * (DSI subscriptions) would otherwise wait. Remaining entries are retried
     * on later SI events as before; new registrations request another queued pass.
     * Every line carries mono_ms and wall_ms.
     */
    private static final class DeferredPass implements Runnable {
        private static boolean enabled;
        private static boolean queued;
        private static boolean running;
        private static boolean requested;
        private static boolean closed;
        private static de.vw.mib.timer.Timer retry;
        private static int retryToken;
        private final String why;

        private DeferredPass(String why) {
            this.why = why;
        }

        private static int pending() {
            synchronized (deferred) {
                return deferred.size();
            }
        }

        private static void log(String msg) {
            Slog.write(DEFERRED_LOG + EventClock.stamp() + " " + msg);
        }

        /** Late registrations need an ASL pass even without another SI event. */
        static void registered() {
            synchronized (deferred) {
                if (!enabled || closed) {
                    return;
                }
                requested = true;
            }
            queue("late registration");
        }

        /** Queues one pass; additions during a pass request a later pass. */
        static void queue(String why) {
            int n;
            synchronized (deferred) {
                n = deferred.size();
                if (closed || n == 0 || queued || running) {
                    return;
                }
                queued = true;
            }
            String head = n + " pending after " + why;
            try {
                de.vw.mib.genericevents.ThreadSwitchingTarget asl =
                    de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch;
                if (smartphoneTarget == null || asl == null) {
                    synchronized (deferred) { queued = false; }
                    log(head + ", ASL dispatcher unavailable");
                    armRetry();
                    return;
                }
                asl.enqueue(new DeferredPass(why));
                log(head + ", one pass queued on the ASL thread");
            } catch (Throwable t) {
                synchronized (deferred) { queued = false; }
                log(head + ", queueing the pass failed: " + t);
                armRetry();
            }
        }

        private static void cancelRetry() {
            retryToken++;
            de.vw.mib.timer.Timer old = retry;
            retry = null;
            if (old != null) { old.stop(); }
        }

        private static void armRetry() {
            synchronized (deferred) {
                discardExpired();
                if (closed || deferred.isEmpty()) { cancelRetry(); return; }
                if (queued || running || retry != null) { return; }
                de.vw.mib.timer.TimerManager manager =
                    de.vw.mib.asl.framework.internal.framework.ServiceManager.timerManager;
                de.vw.mib.genericevents.ThreadSwitchingTarget asl =
                    de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch;
                if (manager == null || asl == null) { log("native retry timer unavailable"); return; }
                final int token = ++retryToken;
                try {
                    retry = manager.createTimer("lsd-extender deferred", 100L, false, new Runnable() {
                        public void run() {
                            synchronized (deferred) {
                                if (closed || token != retryToken) { return; }
                                retry = null;
                                discardExpired();
                            }
                            queue("readiness retry");
                            armRetry();
                        }
                    }, asl);
                    retry.start();
                } catch (Throwable failed) {
                    cancelRetry();
                    log("native retry timer failed: " + failed);
                }
            }
        }

        private static void discardExpired() {
            long now = System.currentTimeMillis();
            for (int i = deferred.size() - 1; i >= 0; i--) {
                Object[] entry = (Object[])deferred.elementAt(i);
                if (now >= ((long[])entry[2])[0]) {
                    deferred.removeElementAt(i);
                    log("elapsed readiness deadline: " + ((Deferred)entry[0]).name());
                }
            }
        }

        public void run() {
            try {
                log("ASL pass after " + why + ": " + pending() + " pending");
                runDeferred(true);
                int left = pending();
                log("ASL pass after " + why + " done: " + left + " pending");
            } catch (Throwable t) {
                log("ASL pass after " + why + " failed: " + t);
            }
        }
    }

    private static void runDeferred() {
        runDeferred(false);
    }

    private static void runDeferred(boolean queuedPass) {
        Object[] items;
        synchronized (deferred) {
            if (DeferredPass.closed) { return; }
            DeferredPass.discardExpired();
            if (queuedPass) {
                DeferredPass.queued = false;
            }
            if (DeferredPass.running) {
                return;
            }
            DeferredPass.enabled = true;
            DeferredPass.running = true;
            DeferredPass.requested = false;
            items = new Object[deferred.size()];
            deferred.copyInto(items);
        }
        try {
            for (int i = 0; i < items.length; i++) {
                runDeferredEntry((Object[]) items[i]);
            }
        } finally {
            boolean again;
            synchronized (deferred) {
                DeferredPass.running = false;
                again = DeferredPass.requested;
            }
            if (again) {
                DeferredPass.queue("registration during ASL pass");
            }
            DeferredPass.armRetry();
        }
    }

    static void shutdownDeferred() {
        synchronized (deferred) {
            DeferredPass.closed = true;
            DeferredPass.cancelRetry();
            deferred.removeAllElements();
        }
    }

    private static void runDeferredEntry(Object[] entry) {
        Deferred d = (Deferred) entry[0];
        int[] runs = (int[]) entry[1];
        boolean done;
        try {
            runs[0]++;
            done = d.run();
            if (!done && runs[0] >= MAX_DEFERRED_RUNS) {
                Slog.write(DEFERRED_LOG + d.name() + " gave up after " + runs[0] + " runs");
                done = true;
            }
        } catch (Throwable t) {
            Slog.write(DEFERRED_LOG + d.name() + " failed: " + t);
            done = true;
        }
        if (done) {
            synchronized (deferred) {
                deferred.removeElement(entry);
            }
        }
    }

    /** ASL event ids the channel asked to observe ("observe <id>"): logged on every delivery. */
    private static final Vector observed = new Vector();

    /** Features that want an ASL event (see AslEventListener). */
    private static final Vector aslListeners = new Vector();

    /**
     * Queues an independent observer for a published service. False if our
     * target/ASL dispatcher is unavailable. No stock subscription is changed.
     */
    public static boolean addAslListener(AslEventListener l) {
        if (!observeAsl(l.aslEventId())) {
            return false;
        }
        synchronized (aslListeners) {
            aslListeners.addElement(l);
        }
        return true;
    }

    private static void dispatchAsl(HsmTarget target, int id, de.vw.mib.genericevents.EventGeneric ev) {
        Object[] ls;
        synchronized (aslListeners) {
            if (aslListeners.isEmpty()) {
                return;
            }
            ls = new Object[aslListeners.size()];
            aslListeners.copyInto(ls);
        }
        for (int i = 0; i < ls.length; i++) {
            AslEventListener l = (AslEventListener) ls[i];
            if (l.aslEventId() != id) {
                continue;
            }
            try {
                l.onAslEvent(target, ev);
            } catch (Throwable t) {
                Slog.write(" ASL listener " + l.getClass().getName() + " failed on " + id + ": " + t);
            }
        }
    }

    /** Event from our own receiver; private SI-directed events are not observed. */
    public static void onEvent(HsmTarget target, de.vw.mib.genericevents.EventGeneric ev) {
        int id = ev.getReceiverEventId();
        dispatchAsl(target, id, ev);
        if (!observed.isEmpty() && observed.contains(new Integer(id))) {
            StringBuffer b = new StringBuffer(" event ").append(id).append(" [");
            for (int i = 0; i < 4; i++) {
                try {
                    b.append(i > 0 ? " " : "").append("int").append(i).append('=').append(ev.getInt(i));
                } catch (Throwable stop) {
                    break;
                }
            }
            b.append("] ").append(ev.toString());
            Slog.write(b.toString());
        }
        if (id == ClusterEarly.HMI_SWITCH_EVENT) {
            try {
                ClusterEarly.onHmiSwitch(ev.getInt(0));
            } catch (Throwable t) {
                Slog.write(" cluster-early: handover event unreadable: " + t);
            }
        }
        onEvent(target, id);
    }

    /** Queues our target subscription on the ASL thread; does not touch stock. */
    static boolean observeAsl(int id) {
        return smartphoneTarget != null && OwnedAslTarget.observe(id);
    }

    /** Runs deferred feature work on an incoming SI event. */
    public static void onEvent(HsmTarget target, int eventId) {
        runDeferred();
    }

    /**
     * Published MFL observation, delivered to every owned feature.
     * Return values cannot consume or change the stock target's event delivery.
     */
    public static boolean onMflEvent(HsmTarget target, int gesture) {
        trace(target, ".onMflEvent(" + gesture + ")");
        Object[] ls;
        synchronized (listeners) {
            ls = new Object[listeners.size()];
            listeners.copyInto(ls);
        }
        for (int i = 0; i < ls.length; i++) {
            MflListener l = (MflListener) ls[i];
            try {
                l.onMflEvent(target, gesture);
            } catch (Throwable t) {
                trace(target, " feature " + l.name() + " failed on gesture " + gesture + ": " + t);
            }
        }
        return false;
    }

    /** One line in sloginfo (and on the HMI trace channel when it is enabled); never throws. */
    public static void trace(HsmTarget target, String msg) {
        Slog.write(msg);
        try {
            if (target != null && target.isTraceEnabled()) {
                target.trace().append("lsd-extender").append(msg).log();
            }
        } catch (Throwable ignored) {
            // tracing is best effort
        }
    }

    /** Same, without a target. */
    public static void trace(String msg) {
        Slog.write(msg);
    }

    /** Feed only, no slog: for high-rate samples (touch drags) a consumer reads live.
     *  Same "lsd-extender" prefix as every slog-mirrored line, so consumers see one shape. */
    public static void feed(String msg) {
        Publisher.publish("lsd-extender" + msg);
    }
}
