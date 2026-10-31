/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.Slog;
import org.korho.lsdextender.Spawn;

/** Catalog arguments stay literal. Quit is restricted to our active launch. */
final class GamesMenuEntry implements SettingsMenuEntry.Gate {
    private static final long MAX_CATALOG_BYTES = 131072L;
    private static final String[] SCRIPTS = { "run-love.sh", "run-scummvm.sh", "run-retroarch.sh",
        "run-openttd.sh", "run-xash.sh", "run-shadertoy.sh" };
    private static final String[] BINARIES = { "love", "scummvm", "retroarch", "openttd", "xash3d", "shadertoy-run" };
    private static final String[] ENGINE_PATHS = { "love", "scummvm", "retroarch", "openttd/openttd", "xash/xash3d", "shadertoy-run" };
    private static final long ENGINE_CACHE_MS = 1000L;

    interface Launcher { String run(String[] arguments, String[] environment, Runnable finished); }
    interface Display {
        boolean available();
        boolean select(Game game, boolean controls, Runnable ready);
        boolean restore(Game game);
    }
    static final class Game {
        final String id, title, binary;
        final String[] arguments;
        final GameAudioSession audio;
        volatile boolean started;
        Thread worker;
        Game(String id, String title, String binary, String[] arguments, GameAudioSession audio) {
            this.id = id; this.title = title; this.binary = binary; this.arguments = arguments;
            this.audio = audio;
        }
    }
    private final File directory;
    private final Launcher launcher;
    private final Display display;
    private final GameAudioSession.Backend audioBackend;
    private String lastDecision;
    private final long[] engineSize = new long[BINARIES.length];
    private final long[] engineModified = new long[BINARIES.length];
    private final long[] engineChecked = new long[BINARIES.length];
    private final boolean[] engineExecutable = new boolean[BINARIES.length];

    private volatile Game running;
    private Game completed;
    private volatile boolean stopped, quitting;
    private Runnable returnAction;
    GamesMenuEntry() {
        this(new File(Config.get("lsdextender.games.directory", "/fs/sda0/headunit")), new Launcher() {
            public String run(String[] arguments, String[] environment, Runnable finished) {
                return finished == null ? Spawn.runArguments(arguments, environment, null)
                    : Spawn.runGameArguments(noCoreArguments(arguments), environment, finished);
            }
        });
    }
    GamesMenuEntry(File directory, Launcher launcher) { this(directory, launcher, null, null); }
    GamesMenuEntry(File directory, Launcher launcher, Display display, GameAudioSession.Backend audioBackend) {
        this.directory = directory; this.launcher = launcher;
        this.display = display == null ? new GameDisplay(this) : display;
        this.audioBackend = audioBackend;
    }
    /** Set the child limit before the run script; refuse launch if that fails. */
    static String[] noCoreArguments(String[] arguments) {
        String[] result = new String[arguments.length + 4];
        result[0] = "/bin/sh"; result[1] = "-c";
        result[2] = "ulimit -c 0 || exit; exec \"$@\"";
        result[3] = "lsd-game";
        System.arraycopy(arguments, 0, result, 4, arguments.length);
        return result;
    }
    static String[] environment(String id) {
        return "balatro".equals(id) ? new String[] { "SDL_NVAUDIO_DEVICE=MPL1", "SDL_QNXDI_DISPLAYABLE=80", "BALATRO_RES_SCALE=0.5" }
            : new String[] { "SDL_NVAUDIO_DEVICE=MPL1", "SDL_QNXDI_DISPLAYABLE=80" };
    }
    public String title() { return "Games"; }
    public String unavailable() { return "no installed known game entries"; }
    public boolean live() { return !stopped && catalog().length != 0; }
    synchronized void returned(Runnable action) { returnAction = action; }
    synchronized Game[] catalog() {
        Properties properties = new Properties();
        Vector found = new Vector();
        StringBuffer decision = new StringBuffer();
        File file = new File(directory, "games.properties");
        try {
            if (!directory.isAbsolute()) { throw new java.io.IOException("games directory must be absolute"); }
            if (file.length() > MAX_CATALOG_BYTES) { throw new java.io.IOException("catalog too large"); }
            FileInputStream input = new FileInputStream(file);
            try { properties.load(input); } finally { input.close(); }
            StringTokenizer ids = new StringTokenizer(properties.getProperty("games", ""));
            while (ids.hasMoreTokens() && found.size() < 128) {
                String id = ids.nextToken();
                Game game = read(properties, id);
                decision.append(id).append('=').append(game != null).append(' ');
                if (game != null && !stopped) { found.addElement(game); }
            }
        } catch (java.io.IOException failure) { decision.append("catalog unavailable"); }
        String next = decision.toString();
        if (!next.equals(lastDecision)) { lastDecision = next; Slog.write(" games catalog " + next + EventClock.stamp()); }
        Game[] result = new Game[found.size()]; found.copyInto(result); return result;
    }
    private Game read(Properties properties, String id) {
        if (!"/bin/sh".equals(properties.getProperty(id + ".exec"))
                || "false".equals(properties.getProperty(id + ".enabled"))) { return null; }
        int count;
        try { count = Integer.parseInt(properties.getProperty(id + ".argc", "0")); }
        catch (NumberFormatException failure) { return null; }
        if (count < 1 || count > 16) { return null; }
        String[] args = new String[count + 1]; args[0] = "/bin/sh";
        for (int i = 0; i < count; i++) {
            args[i + 1] = properties.getProperty(id + ".arg." + i);
            if (args[i + 1] == null || args[i + 1].indexOf('\n') >= 0) { return null; }
        }
        File script = new File(args[1]);
        String binary = binary(script.getName());
        if (binary == null || !script.isFile() || !directory.equals(script.getParentFile())
                || !executable(binary, false)) { return null; }
        String requires = properties.getProperty(id + ".requires", "");
        if (requires.length() != 0 && !new File(requires).exists()) { return null; }
        if ("love".equals(binary) && (count < 2 || !new File(args[2]).isFile())) { return null; }
        return new Game(id, properties.getProperty(id + ".title", id), binary, args,
            audioBackend == null ? new GameAudioSession() : new GameAudioSession(audioBackend));
    }
    private static String binary(String script) {
        int index = java.util.Arrays.asList(SCRIPTS).indexOf(script);
        return index < 0 ? null : BINARIES[index];
    }
    /** J9 has no File.canExecute: use the native shell predicate with literal argv. */
    private boolean executable(String binary, boolean fresh) {
        int i = java.util.Arrays.asList(BINARIES).indexOf(binary);
        if (i < 0) { return false; }
        File file = new File(directory, ENGINE_PATHS[i]);
        if (!file.isFile() || file.length() == 0L) {
            engineChecked[i] = 0L; engineExecutable[i] = false; return false;
        }
        long now = EventClock.millis();
        if (now < 0) { now = System.currentTimeMillis(); }
        long age = now - engineChecked[i];
        if (!fresh && Math.abs(age) < ENGINE_CACHE_MS
                && engineSize[i] == file.length() && engineModified[i] == file.lastModified()) {
            return engineExecutable[i];
        }
        String reply = Spawn.runArguments(new String[] { "/bin/sh", "-c",
            "test -f \"$1\" && test -x \"$1\"", "lsd-game-executable", file.getPath() }, null, null);
        int status = reply.lastIndexOf(" rc=");
        engineExecutable[i] = status >= 0 && reply.indexOf(" rc=0 in ", status) == status;
        engineChecked[i] = now; engineSize[i] = file.length(); engineModified[i] = file.lastModified();
        return engineExecutable[i];
    }
    synchronized boolean launch(final String id) {
        if (stopped || quitting || LsdExtender.isReloading()) { return false; }
        if (running != null) {
            if (running.id.equals(id)) { return show(running); }
            Slog.write(" games launch refused; " + running.title + " is running " + EventClock.stamp());
            return false;
        }
        Game[] installed = catalog();
        Game selected = null;
        for (int i = 0; i < installed.length; i++) { if (installed[i].id.equals(id)) { selected = installed[i]; } }
        if (selected == null) { return false; }
        if (!executable(selected.binary, true)) { catalog(); return false; }
        final Game game = selected; running = game;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                boolean watched = false;
                try {
                    Slog.write(" games launch id=" + game.id + " binary=" + game.binary
                        + " SDL_NVAUDIO_DEVICE=MPL1 SDL_QNXDI_DISPLAYABLE=80 core_dump_limit=0"
                        + ("balatro".equals(game.id) ? " BALATRO_RES_SCALE=0.5 " : " ") + EventClock.stamp());
                    game.audio.acquire();
                    synchronized (GamesMenuEntry.this) {
                        if (stopped || running != game) { return; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
                        game.started = true;
                    }
                    Slog.write(" games audio acquired id=" + game.id + " " + EventClock.stamp());
                    String result = launcher.run(game.arguments, environment(game.id), new Runnable() { public void run() { finished(game); } });
                    watched = result.indexOf(" (watched)") >= 0; Slog.write(result);
                } catch (java.io.IOException refused) {
                    Slog.write(" games audio refused id=" + game.id + " reason=" + refused + " " + EventClock.stamp());
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    Slog.write(" games audio cancelled id=" + game.id + " " + EventClock.stamp());
                } finally { if (!watched) { finished(game); } }
            }
        }, "lsd-games-launch");
        game.worker = worker;
        worker.setDaemon(true);
        try { worker.start(); } catch (RuntimeException failure) { running = null; throw failure; }
        return true;
    }
    private synchronized void finished(Game game) {
        if (running != game) { return; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
        game.audio.release();
        Slog.write(" games audio released id=" + game.id + " " + EventClock.stamp());
        running = null; completed = game; notifyAll();
        Slog.write(" games script ended id=" + game.id + " " + EventClock.stamp());
        display.restore(game);
        if (returnAction != null && !stopped) { returnAction.run(); }
    }
    synchronized boolean canRestore(Game game) {
        return !stopped && running == null && completed == game; // NOPMD CompareObjectsWithEquals - Exact completed launch.
    }
    static void prepareReload(GamesMenuEntry games) {
        if (games == null) { return; }
        synchronized (games) {
            if (games.running != null) {
                String reason = "HomeUI reload refused; " + games.running.title + " is running";
                Slog.write(" games " + reason + " " + EventClock.stamp());
                throw new IllegalStateException(reason);
            }
        }
    }
    boolean isBusy() { return running != null; }
    synchronized boolean selectable(String id) { return running == null || running.id.equals(id); }
    synchronized String runningTitle() { return running == null ? "" : running.title; }
    synchronized boolean canReturn() { return !stopped && currentGame() != null && display.available(); }
    synchronized boolean show(Game game) {
        if (!canReturn() || running != game) { return false; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
        return display.select(game, false, null);
    }
    synchronized boolean controls(Game game, Runnable ready) {
        if (!canReturn() || running != game) { return false; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
        return display.select(game, true, ready);
    }
    synchronized Game currentGame() { return running != null && running.started ? running : null; }
    synchronized boolean quit(Game expected) {
        if (running != expected) { return false; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
        return quit();
    }
    synchronized boolean quit() {
        if (stopped || running == null || !running.started || quitting) { return false; }
        final Game game = running; quitting = true;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    if (running != game) { return; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
                    Slog.write(" games quit id=" + game.id + " binary=" + game.binary + " " + EventClock.stamp());
                    Slog.write(launcher.run(new String[] { "/bin/slay", game.binary }, null, null));
                    synchronized (GamesMenuEntry.this) {
                        long deadline = System.currentTimeMillis() + 5000L;
                        while (running == game && System.currentTimeMillis() < deadline) { // NOPMD CompareObjectsWithEquals - Exact owned launch.
                            GamesMenuEntry.this.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                        }
                        Slog.write(" games quit script_ended=" + (running != game) + " " + EventClock.stamp()); // NOPMD CompareObjectsWithEquals - Exact owned launch.
                    }
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                finally { quitting = false; }
            }
        }, "lsd-games-quit");
        worker.setDaemon(true); worker.start(); return true;
    }
    synchronized void stop() {
        stopped = true;
        if (running != null && !running.started) {
            running.audio.release();
            if (running.worker != null) { running.worker.interrupt(); }
        }
    }
}
