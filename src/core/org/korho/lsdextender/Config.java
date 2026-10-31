/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Runtime configuration from lsd-extender.conf, normally beside the core jar.
 * The installation adds this jar to the HMI startup command. Lines:
 *
 *   # comment
 *   key=value           a property (also accepted as "prop key=value")
 *   jar <file>          a feature-only jar (no stock overrides) core loads at runtime,
 *                       relative to the conf directory unless absolute
 *   feature <class>     a feature class to load and register (the known ones are
 *                       tried anyway)
 *   spawn <command>     a command core runs once, on its own thread, after the
 *                       extension is up (see Spawn); whitespace-split argv, first
 *                       word looked up in /bin and /armle/bin when it has no slash
 *
 * A Java system property of the same name wins over the file, so -D still works.
 * The conf path itself can be overridden with -Dlsdextender.conf=<path> (harness).
 */
public final class Config {
    static final String[] CONF_CANDIDATES = new String[]{
        "/fs/sdb0/lsd-extender/lsd-extender.conf",
        "/fs/sda0/lsd-extender/lsd-extender.conf",
    };

    private static Hashtable props;
    private static Vector jars = new Vector();
    private static Vector features = new Vector();
    private static Vector spawns = new Vector();
    private static String confDir;
    private static String confUsed;

    private Config() {
    }

    /** In-memory reload checkpoint; disk bytes and runtime overrides stay untouched. */
    static final class Snapshot {
        private final Hashtable values;
        private final Vector jarLines, featureLines, spawnCommands;
        private final String directory, path;
        private Snapshot() {
            values = props; jarLines = jars; featureLines = features; spawnCommands = spawns;
            directory = confDir; path = confUsed;
        }
    }

    static synchronized Snapshot snapshot() {
        load();
        return new Snapshot();
    }

    static synchronized void restore(Snapshot snapshot) {
        props = snapshot.values; jars = snapshot.jarLines; features = snapshot.featureLines;
        spawns = snapshot.spawnCommands; confDir = snapshot.directory; confUsed = snapshot.path;
    }

    /**
     * Forget the file and read it again on the next access (the "reload" verb): new
     * keys, jar and spawn lines take effect; runtime overrides ("set") are kept.
     */
    public static synchronized void reload() {
        props = null;
        jars = new Vector();
        features = new Vector();
        spawns = new Vector();
        confDir = null;
        confUsed = null;
        load();
    }

    private static synchronized void load() {
        if (props != null) {
            return;
        }
        props = new Hashtable();
        String path = null;
        try {
            path = System.getProperty("lsdextender.conf");
        } catch (Throwable ignored) {
            // no system properties: fall through to the candidates
        }
        if (path == null || path.length() == 0) {
            path = defaultConfPath(jarDirectory(), CONF_CANDIDATES);
        }
        if (path == null) {
            Slog.write(" config: no lsd-extender.conf found, defaults only");
            return;
        }
        BufferedReader in = null;
        int n = 0;
        try {
            File f = new File(path);
            confDir = f.getParent();
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            String line;
            while ((line = in.readLine()) != null) {
                if (parseLine(line)) { n++; }
            }
            confUsed = path;
            Slog.write(" config: " + path + ": " + n + " lines, " + props.size() + " props, "
                + jars.size() + " jars, " + features.size() + " features, " + spawns.size() + " spawns");
        } catch (Throwable t) {
            Slog.write(" config: failed reading " + path + ": " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static boolean parseLine(String line) {
        line = line.trim();
        if (line.length() == 0 || line.charAt(0) == '#') {
            return false;
        }
        // trailing comment: "key=value   # note"
        int hash = line.indexOf(" #");
        if (hash < 0) {
            hash = line.indexOf("\t#");
        }
        if (hash > 0) {
            line = line.substring(0, hash).trim();
        }
        if (line.startsWith("jar ")) {
            jars.addElement(line.substring(4).trim());
        } else if (line.startsWith("feature ")) {
            features.addElement(line.substring(8).trim());
        } else if (line.startsWith("spawn ")) {
            spawns.addElement(line.substring(6).trim());
        } else {
            if (line.startsWith("prop ")) {
                line = line.substring(5).trim();
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            } else {
                Slog.write(" config: ignored line: " + line);
            }
        }
        return true;
    }

    /** Runtime overrides from the command channel ("set key=value"); highest priority. */
    private static final Hashtable overrides = new Hashtable();

    public static void override(String key, String value) {
        overrides.put(key, value);
        Slog.write(" config: runtime override " + key + "=" + value);
    }

    public static String get(String key, String dflt) {
        Object o = overrides.get(key);
        if (o != null) {
            return (String) o;
        }
        try {
            String v = System.getProperty(key);
            if (v != null && v.length() > 0) {
                return v;
            }
        } catch (Throwable ignored) {
            // fall through to the file
        }
        load();
        Object v = props.get(key);
        return v == null ? dflt : (String) v;
    }

    public static int getInt(String key, int dflt) {
        String v = get(key, null);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return dflt;
        }
    }

    public static boolean getBool(String key, boolean dflt) {
        String v = get(key, null);
        if (v == null) {
            return dflt;
        }
        v = v.trim().toLowerCase();
        return "true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    /** Absolute paths of the "jar" lines, in file order. */
    static String[] jarPaths() {
        load();
        String[] out = new String[jars.size()];
        for (int i = 0; i < out.length; i++) {
            String j = (String) jars.elementAt(i);
            out[i] = j.startsWith("/") || confDir == null ? j : confDir + "/" + j;
        }
        return out;
    }

    /** The "spawn" lines (command text), in file order. */
    static String[] spawnLines() {
        load();
        String[] out = new String[spawns.size()];
        spawns.copyInto(out);
        return out;
    }

    /** Class names of the "feature" lines, in file order. */
    static String[] featureNames() {
        load();
        String[] out = new String[features.size()];
        features.copyInto(out);
        return out;
    }

    static String confPath() {
        load();
        return confUsed;
    }

    /**
     * The card directory: where the loaded conf is, else (no conf on the card) where
     * the lsd-extender jar the VM loaded is, so a card in another slot with no conf still
     * finds its own files. Null only when neither is known.
     */
    public static String directory() {
        load();
        if (confUsed != null) {
            return confDir;
        }
        return jarDirectory();
    }

    /** A known loaded jar selects its own card, even when another card has a conf. */
    static String defaultConfPath(String loadedDir, String[] candidates) {
        if (loadedDir != null) {
            File local = new File(loadedDir, "lsd-extender.conf");
            return local.isFile() ? local.getPath() : null;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (new File(candidates[i]).isFile()) {
                return candidates[i];
            }
        }
        return null;
    }

    private static String jarDir;
    private static boolean jarDirLooked;

    /** Directory of the jar this class came from (the boot class path entry), once. */
    static synchronized String jarDirectory() {
        if (jarDirLooked) {
            return jarDir;
        }
        jarDirLooked = true;
        String how = null;
        try {
            java.net.URL u = Config.class.getResource("Config.class");
            jarDir = jarDirOf(u == null ? null : u.toString());
            how = "class resource";
        } catch (Throwable t) {
            Slog.write(" config: jar location lookup failed: " + t);
        }
        if (jarDir == null) {
            for (int i = 0; i < CONF_CANDIDATES.length && jarDir == null; i++) {
                String dir = new File(CONF_CANDIDATES[i]).getParent();
                try {
                    if (new File(dir, "lsd-extender.jar").isFile()) {
                        jarDir = dir;
                        how = "lsd-extender.jar present";
                    }
                } catch (Throwable ignored) {
                    // try the next one
                }
            }
        }
        Slog.write(jarDir == null ? " config: no jar directory found"
            : " config: card directory " + jarDir + " (" + how + ")");
        return jarDir;
    }

    /** "jar:file:/fs/sda0/lsd-extender/lsd-extender.jar!/..." to "/fs/sda0/lsd-extender". */
    static String jarDirOf(String url) {
        if (url == null || !url.startsWith("jar:")) {
            return null;
        }
        int bang = url.indexOf("!/");
        if (bang < 0) {
            return null;
        }
        String jar = url.substring(4, bang);
        if (jar.startsWith("file:")) {
            jar = jar.substring(5);
        }
        while (jar.startsWith("//")) {
            jar = jar.substring(1);
        }
        if (!jar.startsWith("/")) {
            return null;
        }
        return new File(jar).getParent();
    }
}
