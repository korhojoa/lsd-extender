/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aasettings;

import org.korho.lsdextender.AndroidAutoSetting;
import org.korho.lsdextender.AndroidAutoSettings;
import org.korho.lsdextender.AndroidAutoSettingsBridge;
import org.korho.lsdextender.AndroidAutoSettingsState;
import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.CarPlaySettingsBridge;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.Slog;
import org.korho.lsdextender.boot.HookInstaller;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.io.File;
import java.io.InputStream;
import java.util.StringTokenizer;
import java.util.Vector;

/** All native process and card I/O runs on this worker, never in a widget callback. */
public final class ProjectionSettingsService implements AndroidAutoSettings, MflListener, CommandHandler, Runnable {
    private volatile AndroidAutoSettingsState state = new AndroidAutoSettingsState(false, false, "", "Loading settings...", new AndroidAutoSetting[0], 0, false);
    private final String directory, argument, commandName, helperName, systemName;
    private final boolean carPlay;
    /** The last helper answer was "no config"; written to slog once per entry into that state. */
    private boolean absentReported;
    private volatile boolean stopped;
    private boolean refreshRequested = true;
    private String[] pending;
    private long lastRead;
    private long completed;
    private boolean lastSaveOK;
    private String saveError = "";
    private boolean firstReported;
    private Thread worker;
    private Process child;
    private String base;

    private ProjectionSettingsService(String directory, String argument, boolean carPlay) {
        this.directory = directory; this.argument = argument; this.carPlay = carPlay;
        commandName = carPlay ? "carplaysettings" : "aasettings";
        helperName = carPlay ? "carplay-settings" : "android-auto-settings";
        systemName = carPlay ? "CarPlay" : "Android Auto";
    }

    public static ProjectionSettingsService start(String directory, String argument, boolean carPlay) {
        ProjectionSettingsService s = new ProjectionSettingsService(directory, argument, carPlay);
        return start(s);
    }

    public static ProjectionSettingsService startAndroidAuto(String directory, String base) {
        ProjectionSettingsService s = new ProjectionSettingsService(directory, directory, false);
        s.base = base;
        return start(s);
    }

    private static ProjectionSettingsService start(ProjectionSettingsService s) {
        if (s.carPlay) { CarPlaySettingsBridge.register(s); } else { AndroidAutoSettingsBridge.register(s); }
        s.worker = new Thread(s, "lsd-" + s.commandName);
        s.worker.setDaemon(true); s.worker.start();
        HookInstaller.log(s.commandName + ": settings bridge registered, worker started (helper "
            + (s.directory == null ? "unavailable: no card directory" : new File(s.directory, s.helperName).getPath()) + ")");
        return s;
    }

    /** The first helper answer writes one ready line (or why not), so silence never means "not run". */
    private void reportFirst(String result) {
        if (!firstReported) {
            firstReported = true;
            HookInstaller.log(commandName + ": " + result);
        }
    }

    public String name() { return commandName + " [" + Build.BANNER + "]"; }
    public boolean onMflEvent(HsmTarget target, int gesture) { return false; }
    public AndroidAutoSettingsState snapshot() { return state; }
    public synchronized void refresh() { refreshRequested = true; notifyAll(); }

    public synchronized boolean save(String revision, String[] keys, String[] values) {
        if (stopped || !state.ready || state.busy || keys == null || values == null || keys.length != values.length || keys.length == 0) { return false; }
        String[] job = new String[keys.length + 2]; job[0] = "commit"; job[1] = revision;
        for (int i = 0; i < keys.length; i++) {
            if (!identifier(keys[i]) || values[i] == null) { return false; }
            if (!values[i].equals("inherit")) {
                try { Integer.parseInt(values[i]); } catch (Exception e) { return false; }
            }
            job[i + 2] = keys[i] + "=" + values[i];
        }
        saveError = "";
        pending = job;
        state = copy(true, "Saving...", completed, false);
        notifyAll();
        return true;
    }

    private AndroidAutoSettingsState copy(boolean busy, String status, long serial, boolean saved) {
        AndroidAutoSettingsState s = state;
        return new AndroidAutoSettingsState(s.ready, busy, s.revision, status, s.rows(), serial, saved, saveError);
    }

    private static boolean identifier(String s) {
        if (s == null || s.length() == 0 || s.length() > 80) { return false; }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '.') { return false; }
        }
        return true;
    }

    public void run() {
        while (!stopped) {
            String[] job;
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (pending == null && (!refreshRequested || (now >= lastRead && now - lastRead < 2500L))) {
                    try { wait(500L); } catch (InterruptedException ignored) { }
                    continue;
                }
                job = pending; pending = null; refreshRequested = false;
                if (job == null) { job = new String[]{"snapshot"}; }
            }
            boolean save = job[0].equals("commit");
            AndroidAutoSettingsState before = state;
            try {
                String response = execute(job);
                String missing = SettingsProtocol.absent(response);
                if (missing != null) {
                    if (!absent(missing, save)) { break; }
                    lastRead = System.currentTimeMillis();
                    continue;
                }
                AndroidAutoSettingsState committed;
                synchronized (this) {
                    if (stopped) { break; }
                    if (save) { completed++; }
                    AndroidAutoSettingsState next = SettingsProtocol.parse(response, completed, save || lastSaveOK);
                    committed = next;
                    if (save) { lastSaveOK = true; saveError = ""; }
                    // A refresh that started before a save must not clear its busy flag.
                    state = new AndroidAutoSettingsState(next.ready, pending != null, next.revision,
                        pending == null ? next.status : "Saving...", next.rows(), completed, lastSaveOK, saveError);
                }
                if (save) { SettingsChangeLog.report(commandName, before, committed, job); }
                reportAnswer(job, save, committed);
            } catch (Exception e) {
                synchronized (this) {
                    // Parsing can fail after incrementing completed. Each operation
                    // only needs a monotonic completion marker, not a consecutive ID.
                    if (save) { completed++; lastSaveOK = false; saveError = e.getMessage(); }
                    state = copy(pending != null, (save ? "Not saved: " : "Settings unavailable: ") + e.getMessage(), completed, lastSaveOK);
                }
                Slog.write(" " + commandName + ": " + e);
                reportFirst("not ready, first helper call failed: " + e.getMessage());
            }
            lastRead = System.currentTimeMillis();
        }
    }

    /** One slog line per Store, the config-found line after an absence, and the first answer. */
    private void reportAnswer(String[] job, boolean save, AndroidAutoSettingsState now) {
        if (save) {
            HookInstaller.log(commandName + ": saved " + (job.length - 2) + " setting(s), revision "
                + job[1] + " -> " + now.revision);
        }
        if (absentReported) {
            absentReported = false;
            HookInstaller.log(commandName + ": " + systemName + " config found, " + now.size() + " settings; "
                + systemName + " settings shown");
        }
        reportFirst((now.ready ? "ready" : "not ready") + ", first helper answer: revision " + now.revision
            + ", " + now.size() + " settings");
    }

    /**
     * The helper found no config: this projection system is not installed on the unit. That is
     * a normal state: the state is not ready (the settings row stays hidden), and one plain line
     * is written when the state is entered, not on every refresh. False when stopped.
     */
    private boolean absent(String missing, boolean save) {
        synchronized (this) {
            if (stopped) { return false; }
            if (save) { completed++; lastSaveOK = false; saveError = "The settings config was removed. Reopen settings."; }
            state = new AndroidAutoSettingsState(false, pending != null, "", "No " + systemName + " config",
                new AndroidAutoSetting[0], completed, lastSaveOK, saveError);
        }
        if (!absentReported) {
            absentReported = true; firstReported = true;
            HookInstaller.log(commandName + ": no " + systemName + " config at " + missing + "; "
                + systemName + " settings hidden");
        }
        return true;
    }

    private String execute(String[] job) throws Exception {
        if (directory == null) { throw new Exception("settings card directory unavailable"); }
        String helper = new File(directory, helperName).getPath();
        // The shell program is constant. Every dynamic value is an argv element.
        String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
        String[] argv = helperArguments(shell, commandName, helper, argument, base, job);
        final Process p = Runtime.getRuntime().exec(argv);
        synchronized (this) { child = p; }
        final Capture out = new Capture(p.getInputStream(), 131072);
        final Capture err = new Capture(p.getErrorStream(), 4096);
        Thread stdout = out.start(), stderr = err.start();
        long began = System.currentTimeMillis();
        try {
            while (!stopped) {
                try {
                    int rc = p.exitValue();
                    stdout.join(500L); stderr.join(500L);
                    if (out.failed || stdout.isAlive()) { throw new Exception("incomplete or oversized helper output"); }
                    String text = out.text();
                    if (rc != 0 && !text.startsWith("error\t")) { throw new Exception("helper exit " + rc + ": " + err.text()); }
                    return text;
                } catch (IllegalThreadStateException running) {
                    long now = System.currentTimeMillis();
                    if (now < began || now - began > 8000L) { throw new Exception("settings helper timed out"); }
                    Thread.sleep(25L);
                }
            }
            throw new Exception("settings worker stopped");
        } finally {
            // Only the child created by this invocation is eligible for termination.
            try { p.exitValue(); } catch (IllegalThreadStateException running) { p.destroy(); }
            try { p.getOutputStream().close(); } catch (Exception ignored) { }
            synchronized (this) { if (child == p) { child = null; } }
        }
    }

    static String[] helperArguments(String shell, String name, String helper, String argument,
                                    String base, String[] job) {
        int offset = base == null ? 6 : 8;
        String[] argv = new String[job.length + offset];
        argv[0] = shell; argv[1] = "-c";
        argv[2] = "unset LD_PRELOAD; LSD_SETTINGS_HMI_PID=$PPID; export LSD_SETTINGS_HMI_PID; exec \"$@\"";
        argv[3] = name; argv[4] = helper;
        if (base != null) { argv[5] = "--base"; argv[6] = base; }
        argv[offset - 1] = argument;
        System.arraycopy(job, 0, argv, offset, job.length);
        return argv;
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final int limit;
        private final StringBuffer buffer = new StringBuffer();
        volatile boolean failed;
        Capture(InputStream input, int limit) { this.input = input; this.limit = limit; }
        Thread start() { Thread t = new Thread(this, "lsd-settings-output"); t.setDaemon(true); t.start(); return t; }
        public void run() {
            try {
                byte[] data = new byte[1024]; int count;
                while ((count = input.read(data)) >= 0) {
                    synchronized (buffer) {
                        if (buffer.length() + count > limit) { failed = true; }
                        else { buffer.append(new String(data, 0, count, "UTF-8")); }
                    }
                }
            } catch (Exception e) { failed = true; }
            finally { try { input.close(); } catch (Exception ignored) { } }
        }
        String text() { synchronized (buffer) { return buffer.toString(); } }
    }

    public String command(String line) {
        if (!line.equals(commandName) && !line.startsWith(commandName + " ")) { return null; }
        String arg = line.length() == commandName.length() ? "status" : line.substring(commandName.length() + 1).trim();
        if (arg.equals("status")) { refresh(); return commandName + " revision=" + state.revision + " busy=" + state.busy + " " + state.status; }
        if (arg.startsWith("get ")) {
            String key = arg.substring(4).trim();
            AndroidAutoSettingsState s = state;
            for (int i = 0; i < s.size(); i++) { if (s.row(i).key.equals(key)) { return key + "=" + s.row(i).value; } }
            return "err unknown setting";
        }
        if (arg.startsWith("set ")) {
            StringTokenizer tokens = new StringTokenizer(arg.substring(4));
            Vector keys = new Vector(), values = new Vector();
            while (tokens.hasMoreTokens()) {
                String token = tokens.nextToken(); int eq = token.indexOf('=');
                if (eq <= 0) { return "err expected key=value"; }
                keys.addElement(token.substring(0, eq)); values.addElement(token.substring(eq + 1));
            }
            String[] k = new String[keys.size()], v = new String[values.size()]; keys.copyInto(k); values.copyInto(v);
            return save(state.revision, k, v) ? "queued; use " + commandName + " status" : "err settings unavailable or busy";
        }
        return "err usage: " + commandName + " status|get KEY|set KEY=VALUE ...";
    }

    public void shutdown() {
        if (carPlay) { CarPlaySettingsBridge.unregister(this); } else { AndroidAutoSettingsBridge.unregister(this); }
        synchronized (this) { stopped = true; notifyAll(); }
        try { if (worker != null) { worker.join(9000L); } } catch (InterruptedException ignored) { }
    }
}
