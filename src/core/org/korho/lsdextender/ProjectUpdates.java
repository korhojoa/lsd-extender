/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Hashtable;

/** Core owns the fetch and install jobs so replacing the UI jar cannot duplicate them. */
public final class ProjectUpdates {
    public static final class State {
        public final String phase, message, versions, action;
        public final boolean busy, cancelSupported;
        public final long done, total;
        State(String phase, String message, String versions, boolean busy, long done, long total) {
            this(phase, message, versions, busy, done, total, "none");
        }
        State(String phase, String message, String versions, boolean busy, long done, long total, String action) {
            this(phase, message, versions, busy, done, total, action, false);
        }
        State(String phase, String message, String versions, boolean busy, long done, long total, String action, boolean cancelSupported) {
            this.phase = phase; this.message = message; this.versions = versions;
            this.busy = busy; this.done = done; this.total = total; this.action = action; this.cancelSupported = cancelSupported;
        }
    }
    private static final int MODE_FETCH = 0, MODE_INVENTORY = 1, MODE_INSTALL = 2, MODE_CANCEL = 3;
    private static volatile State state = new State("idle", "Fetch the latest compatible project packages", "", false, 0, 0);
    private static Thread worker;
    private static boolean backendProbing;
    private static volatile boolean inventoryAdmission;
    private static String activeRequest;
    private static int activeMode;
    private static boolean cancelRequested;
    private static final int STATUS_LIMIT = 131072;
    private static final String CANCELLED = "cancelled";
    private static long sequence = System.currentTimeMillis();
    /** Runs after an installation that replaced only runtime files. */
    public static volatile Runnable reloader = new Runnable() {
        public void run() { LsdExtender.reloadFeatures(); }
    };
    private ProjectUpdates() { }

    public static State snapshot() { return state; }
    /** Job kind for a busy snapshot; the caller must check State.busy first. */
    public static synchronized boolean inspecting() { return activeMode == MODE_INVENTORY; }

    /** Why the updater cannot run on this unit, or null (UpdaterFiles). */
    public static String configurationProblem() { return UpdaterFiles.problem(); }
    /** Unenrolled units can inspect installed versions without authorizing updates. */
    public static boolean inventoryOnly() { return InventoryConfig.selected(Config.directory()); }

    /** The Settings entry shows only when configured; one slog line per decision. */
    public static boolean configured() {
        String problem = configurationProblem();
        log("entry " + (problem == null ? "shown: worker and configuration present" : "hidden: " + problem));
        return problem == null;
    }

    public static boolean admittedInventoryOnly() { return inventoryAdmission; }

    /** Read-only update-status admission. Enrollment must initialize the backend first. */
    public static final class BackendProbe {
        private Process process;
        private boolean stopped;

        /** 1 is status success, 0 is unavailable, -1 leaves an active job alone. */
        public int check() {
            synchronized (ProjectUpdates.class) {
                if (worker != null || backendProbing) { return -1; }
                backendProbing = true;
            }
            Process child = null;
            Thread output = null, errors = null;
            try {
                // This helper only checks config mode with File.exists; it runs no command.
                boolean inventory = inventoryOnly();
                synchronized (this) {
                    if (stopped) { return 0; }
                    child = Runtime.getRuntime().exec(probeArguments());
                    process = child;
                }
                output = discard(child.getInputStream()); errors = discard(child.getErrorStream());
                for (int attempt = 0; attempt < 50; attempt++) {
                    synchronized (this) { if (stopped) { return 0; } }
                    try {
                        boolean ready = child.exitValue() == 0;
                        if (ready) { inventoryAdmission = inventory; }
                        return ready ? 1 : 0;
                    } catch (IllegalThreadStateException running) { Thread.sleep(100L); }
                }
                return 0;
            } catch (Exception failure) { return 0; }
            finally {
                try {
                    if (child != null) {
                        child.destroy();
                        try { child.getInputStream().close(); } catch (java.io.IOException ignored) { }
                        try { child.getErrorStream().close(); } catch (java.io.IOException ignored) { }
                        try { child.getOutputStream().close(); } catch (java.io.IOException ignored) { }
                    }
                    retire(output); retire(errors);
                } finally {
                    synchronized (this) { if (process == child) { process = null; } }
                    synchronized (ProjectUpdates.class) { backendProbing = false; ProjectUpdates.class.notifyAll(); }
                }
            }
        }

        private String[] probeArguments() {
            String problem = configurationProblem();
            if (problem != null) { throw new IllegalStateException(problem); }
            String directory = Config.directory();
            // Never run inventory or prepare its config here: both can change backend state.
            String config = UpdaterFiles.agentConfig(directory);
            String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
            return new String[]{shell, "-c", "unset LD_PRELOAD; exec \"$@\"", "updater-entry-status",
                UpdaterFiles.workerPath(directory), "update-status", config};
        }

        private static Thread discard(final InputStream input) {
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try { byte[] bytes = new byte[512]; while (input.read(bytes) >= 0) { /* Drain without repeating status in HMI logs. */ } }
                    catch (java.io.IOException closed) { /* Owning process or probe closed the stream. */ }
                    finally { try { input.close(); } catch (java.io.IOException ignored) { } }
                }
            }, "lsd-updater-status-output");
            reader.setDaemon(true); reader.start(); return reader;
        }

        private static void retire(Thread reader) {
            if (reader != null) {
                try { reader.join(200L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }

        private static String[] workerArguments(String request, int mode, String status, String directory, String config) {
            String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
            // Keep configuration as argv data and remove inherited HMI preload libraries.
            return new String[]{shell, "-c", "unset LD_PRELOAD; exec \"$@\"", "project-updates",
                UpdaterFiles.workerPath(directory),
                "hmi", config, mode == MODE_CANCEL ? "cancel" : mode == MODE_INVENTORY ? "inventory" : mode == MODE_INSTALL ? "install" : "fetch",
                status, request};
        }

        private static void awaitCompletion() throws InterruptedException {
            synchronized (ProjectUpdates.class) { while (backendProbing && !cancelRequested) { ProjectUpdates.class.wait(); } }
        }

        public synchronized void stop() {
            stopped = true;
            if (process != null) { process.destroy(); }
        }
    }

    /** No file or network I/O on the caller, including the HMI event thread. */
    public static synchronized boolean fetch() { return launch(MODE_FETCH); }
    /** Installed versions do not depend on a working release source. */
    public static synchronized boolean inspect() { return launch(MODE_INVENTORY); }
    /** Install the staged release that the last check verified completely. */
    public static synchronized boolean install() { return launch(MODE_INSTALL); }
    /** Correlated cooperative abort; no I/O or worker termination on the caller. */
    public static synchronized boolean cancel() { return cancel(activeRequest); }
    /** A displayed control may cancel only the request it was created for. */
    public static synchronized boolean cancel(String expectedRequest) {
        if ("".equals(expectedRequest) || !cancellableRequest().equals(expectedRequest)) { return false; }
        cancelRequested = true; ProjectUpdates.class.notifyAll();
        Thread cancel = new Thread(new Cancellation(activeRequest), "lsd-project-update-cancel");
        cancel.setDaemon(true); cancel.start(); return true;
    }
    public static synchronized boolean cancelling() { return cancelRequested && state.busy; }
    public static synchronized boolean canCancel() { return worker != null && state.busy && state.cancelSupported && activeMode != MODE_INVENTORY && !cancelRequested; }
    /** Empty while cancellation is unavailable or already pending. */
    public static synchronized String cancellableRequest() { return canCancel() ? activeRequest : ""; }
    public static synchronized String cancellationRequest() { return cancelRequested && state.busy ? activeRequest : ""; }
    private static synchronized boolean current(String request) {
        return worker != null && request.equals(activeRequest) && state.busy;
    }
    private static final class Cancellation implements Runnable {
        private final String request;
        Cancellation(String request) { this.request = request; }
        public void run() {
            Process process = null;
            boolean accepted = false;
            try {
                String status = Config.get("lsdextender.updates.status", "/ramdisk/pps/project-updates/status");
                while (current(request)) {
                    State value = read(status, request);
                    if (value != null && value.busy && value.cancelSupported) {
                        process = Runtime.getRuntime().exec(arguments(request, MODE_CANCEL, status));
                        drain(process.getInputStream()); drain(process.getErrorStream());
                        int exit = process.waitFor();
                        if (exit != 0) { throw new IllegalStateException("Cancellation request rejected (exit " + exit + ")"); }
                        accepted = true; log("cancellation requested for " + request); return;
                    }
                    if (value != null && (!value.busy || !value.cancelSupported)) { return; }
                    Thread.sleep(100L);
                }
            } catch (Exception failure) {
                log("Cannot request cancellation: " + failure);
                synchronized (ProjectUpdates.class) {
                    if (request.equals(activeRequest)) { cancelRequested = false; }
                }
            } finally {
                if (process != null) { process.destroy(); }
                synchronized (ProjectUpdates.class) { if (!accepted && request.equals(activeRequest)) { cancelRequested = false; } }
            }
        }
    }
    private static boolean launch(final int mode) {
        if (worker != null) { return false; }
        if (!InventoryConfig.allowJob(mode == MODE_INVENTORY)) { return false; }
        final String request = String.valueOf(++sequence);
        activeRequest = request; activeMode = mode; cancelRequested = false;
        String message = mode == MODE_INVENTORY ? "Reading installed versions"
            : mode == MODE_INSTALL ? "Preparing installation" : "Preparing project download";
        state = new State("queued", message, state.versions, true, 0, 0, "none", mode != MODE_INVENTORY);
        worker = new Thread(new Runnable() { public void run() { execute(request, mode); } }, "lsd-project-updates");
        worker.setDaemon(true); worker.start(); return true;
    }

    private static String completionPhase(int mode) {
        if (mode == MODE_INVENTORY) { return "inventory"; }
        return mode == MODE_INSTALL ? "installed" : "ready";
    }
    private static void execute(final String request, int mode) {
        Process process = null;
        Watch watch = null;
        Thread monitor = null;
        String expected = completionPhase(mode);
        try {
            // The read-only status check must finish before an update job starts.
            BackendProbe.awaitCompletion();
            synchronized (ProjectUpdates.class) {
                if (cancelRequested) {
                    state = new State(CANCELLED, "Cancelled", state.versions, false, 0, 0);
                    log("queued request " + request + " cancelled before worker launch"); return;
                }
            }
            String status = Config.get("lsdextender.updates.status", "/ramdisk/pps/project-updates/status");
            process = Runtime.getRuntime().exec(arguments(request, mode, status));
            drain(process.getInputStream()); drain(process.getErrorStream());
            watch = new Watch(status, request);
            monitor = new Thread(watch, "lsd-project-update-status"); monitor.setDaemon(true); monitor.start();
            int exit = process.waitFor();
            watch.finished = true; monitor.interrupt(); monitor.join();
            State last = read(status, request);
            if (last != null) { state = retainVersions(last); }
            if (exit != 0 || last == null || !Watch.successful(mode, expected, last.phase)) {
                throw new IllegalStateException(last != null && "error".equals(last.phase)
                    ? last.message : "Project update worker did not complete (exit " + exit + ")");
            }
            state = retainVersions(last);
            if (mode == MODE_INSTALL && "reload".equals(last.action)) { reloadAfterInstall(); }
        } catch (Exception e) {
            if (watch != null) { watch.finished = true; }
            stopMonitor(monitor);
            String prefix = mode == MODE_INVENTORY ? "Cannot read installed versions: "
                : mode == MODE_INSTALL ? "Installation failed: " : "Cannot fetch updates: ";
            state = new State("error", prefix + e.getMessage(), state.versions, false, 0, 0);
            log(state.message);
        } finally {
            if (watch != null) { watch.finished = true; }
            if (monitor != null) { monitor.interrupt(); }
            if (process != null) { process.destroy(); }
            synchronized (ProjectUpdates.class) { worker = null; activeRequest = null; cancelRequested = false; }
        }
    }

    private static void stopMonitor(Thread monitor) {
        if (monitor == null) { return; }
        monitor.interrupt();
        try { monitor.join(); } catch (InterruptedException ignored) { }
    }

    /** The runtime jars were replaced on the card: load them again, as the reload verb does. */
    private static void reloadAfterInstall() {
        Runnable reload = reloader;
        if (reload == null) {
            state = new State("installed", state.message + "; restart the system to use the updates", state.versions,
                false, state.done, state.total, "restart");
            log("installed runtime files; no reloader registered, restart to use them"); return;
        }
        try { reload.run(); log("runtime features reloaded after installation"); }
        catch (Throwable t) {
            log("reload after installation failed: " + t);
            state = new State("installed", state.message + "; reload failed, restart the system", state.versions, false, state.done, state.total, "restart");
        }
    }

    private static String[] arguments(String request, int mode, String status) throws java.io.IOException {
        String problem = configurationProblem();
        if (problem != null) { throw new IllegalStateException(problem); }
        String directory = Config.directory();
        String config = InventoryConfig.argumentsConfig(directory, mode == MODE_INVENTORY);
        return BackendProbe.workerArguments(request, mode, status, directory, config);
    }

    private static void drain(final InputStream input) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) { log(line); }
                } catch (Exception ignored) {
                    // The worker closes its diagnostics on exit.
                } finally { try { reader.close(); } catch (Exception ignored) { } }
            }
        }, "lsd-project-update-log");
        thread.setDaemon(true); thread.start();
    }

    private static void log(String message) {
        Slog.write(" updates: wall_ms=" + System.currentTimeMillis() + " " + message);
    }
    private static State retainVersions(State next) {
        if (next.versions.length() == 0 && ("checking".equals(next.phase) || "error".equals(next.phase) || CANCELLED.equals(next.phase))) {
            return new State(next.phase, next.message, state.versions, next.busy, next.done, next.total, next.action, next.cancelSupported);
        }
        return next;
    }

    private static final class Watch implements Runnable {
        private static boolean successful(int mode, String expected, String phase) {
            return expected.equals(phase) || (mode != MODE_INVENTORY && CANCELLED.equals(phase));
        }
        private final String path, request;
        private volatile boolean finished;
        Watch(String path, String request) { this.path = path; this.request = request; }
        public void run() {
            while (!finished) {
                try {
                    State next = read(path, request);
                    if (next != null) { state = retainVersions(next); }
                    Thread.sleep(250L);
                } catch (Exception ignored) {
                    // The final read and worker exit determine success.
                }
            }
        }
    }

    private static boolean busyPhase(String phase) {
        return "checking".equals(phase) || "downloading".equals(phase) || "verifying".equals(phase) || "installing".equals(phase);
    }
    private static boolean terminalPhase(String phase) {
        return "ready".equals(phase) || "error".equals(phase) || "inventory".equals(phase) || "installed".equals(phase) || CANCELLED.equals(phase);
    }
    private static boolean validAction(String phase, String action) {
        return "none".equals(action) || (!CANCELLED.equals(phase) && ("reload".equals(action) || "restart".equals(action)));
    }

    /** Ignore stale PPS results and bounded malformed data. Called only off-thread. */
    private static State read(String path, String request) throws Exception {
        File file = new File(path);
        if (!file.exists()) { return null; }
        Hashtable values = new Hashtable();
        boolean duplicateCapability = false;
        InputStreamReader input = new InputStreamReader(new FileInputStream(file), "UTF-8");
        String data;
        try {
            char[] chars = new char[STATUS_LIMIT + 1]; int used = 0, count;
            while (used < chars.length && (count = input.read(chars, used, chars.length - used)) > 0) { used += count; }
            if (used > STATUS_LIMIT) { return null; }
            data = new String(chars, 0, used);
        } finally { input.close(); }
        BufferedReader reader = new BufferedReader(new StringReader(data));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf("::");
                if (split > 0) {
                    String key = line.substring(0, split);
                    if ("cancel_supported".equals(key) && values.containsKey(key)) { duplicateCapability = true; }
                    values.put(key, line.substring(split + 2));
                }
            }
        } finally { reader.close(); }
        if (!request.equals(values.get("request"))) { return null; }
        String phase = (String)values.get("phase"), message = (String)values.get("message");
        String versions = (String)values.get("versions");
        if (phase == null || message == null || versions == null) { return null; }
        long done = Long.parseLong((String)values.get("done")), total = Long.parseLong((String)values.get("total"));
        if (done < 0 || total < done) { return null; }
        String action = (String)values.get("action");
        if (action == null) { action = "none"; }
        if (!validAction(phase, action)) { return null; }
        boolean busy = busyPhase(phase);
        if (!busy && !terminalPhase(phase)) { return null; }
        return new State(phase, message, versions, busy, done, total, action,
            !duplicateCapability && "true".equals(values.get("cancel_supported")));
    }
}
