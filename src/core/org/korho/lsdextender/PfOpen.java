/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Vector;

/** One core-owned firewall ruleset and monitor for every registered TCP port. */
public final class PfOpen {
    private static final String CONF = "/etc/pf.conf";
    private static final String GEN = "/dev/shmem/lsd-extender-pf.conf";

    private static final String PFCTL = "/armle/sbin/pfctl";
    private static final Vector ports = new Vector();
    private static Thread monitor;
    private static long revision;

    private PfOpen() { }

    /** Registration does no process or filesystem I/O and survives feature reload. */
    public static synchronized void keepOpen(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid TCP port: " + port);
        }
        Integer value = new Integer(port);
        if (!ports.contains(value)) {
            ports.addElement(value);
            revision++;
        }
        if (monitor == null) {
            monitor = new Thread(new Runnable() {
                public void run() { watch(); }
            }, "lsd-extender-pf");
            monitor.setDaemon(true);
            monitor.start();
        }
        PfOpen.class.notifyAll();
    }

    /** Compatibility for an older runtime feature: all ports still share one owner. */
    public static void open(int port) { keepOpen(port); }

    public static boolean stockRulesLoaded() {
        String info = command(new String[]{PFCTL, "-s", "info"});
        String rules = command(new String[]{PFCTL, "-sr"});
        return info != null && info.indexOf("Status: Enabled") >= 0
            && rules != null && rules.indexOf("EXLAP") >= 0;
    }

    public static boolean isLoaded(int port) {
        return hasPass(command(new String[]{PFCTL, "-sr"}), port);
    }

    private static void watch() {

        if (!new File(PFCTL).exists()) {
            org.korho.lsdextender.boot.HookInstaller.log("pf: " + PFCTL
                + " not found; pf publisher stopped, registered ports stay unloaded");
            return;
        }
        int failures = 0;
        while (true) {
            int[] wanted;
            long observed;
            synchronized (PfOpen.class) {
                observed = revision;
                wanted = new int[ports.size()];
                for (int i = 0; i < wanted.length; i++) {
                    wanted[i] = ((Integer) ports.elementAt(i)).intValue();
                }
            }
            boolean ready = reconcile(wanted);
            failures = ready ? 0 : failures + 1;
            if (failures == 1 || failures % 150 == 0 && failures != 0) {
                Slog.write(" pf: waiting for enabled stock rules and all registered ports; retrying");
            }
            synchronized (PfOpen.class) {
                if (observed == revision) {
                    try { PfOpen.class.wait(ready ? 10000L : 2000L); }
                    catch (InterruptedException ignored) { /* Registration remains active. */ }
                }
            }
        }
    }

    private static boolean reconcile(int[] wanted) {
        try {
            String info = command(new String[]{PFCTL, "-s", "info"});
            if (info == null || info.indexOf("Status: Enabled") < 0) { return false; }
            String active = command(new String[]{PFCTL, "-sr"});
            if (active == null || active.indexOf("EXLAP") < 0) { return false; }
            if (hasAll(active, wanted)) { return true; }
            Vector lines = readLines(CONF);
            String[] ifaces = split(Config.get("lsdextender.publish.pf_ifaces", "$dbg_if $wlan_if $ppp_if"));
            if (ifaces.length == 0) { return false; }
            addRules(lines, wanted, ifaces);
            writeLines(GEN, lines);
            if (command(new String[]{PFCTL, "-n", "-f", GEN}) == null
                || command(new String[]{PFCTL, "-f", GEN}) == null) { return false; }
            if (!hasAll(command(new String[]{PFCTL, "-sr"}), wanted)) {
                Slog.write(" pf: registered pass rules missing after load; retrying");
                return false;
            }
            StringBuffer registered = new StringBuffer();
            for (int i = 0; i < wanted.length; i++) {
                registered.append(i == 0 ? "" : ",").append(wanted[i]);
            }
            Slog.write(" pf: verified TCP ports " + registered + " in one ruleset");
            return true;
        } catch (Exception e) {
            Slog.write(" pf: ruleset update failed; retrying: " + e);
            return false;
        }
    }

    private static boolean hasAll(String rules, int[] wanted) {
        for (int i = 0; i < wanted.length; i++) {
            if (!hasPass(rules, wanted[i])) { return false; }
        }
        return true;
    }

    /** Match a destination port on a TCP input pass rule, not a block or prefix. */
    private static boolean hasPass(String rules, int port) {
        if (rules == null) { return false; }
        java.util.StringTokenizer lines = new java.util.StringTokenizer(rules, "\n");
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken().trim();
            int to = line.indexOf(" to ");
            if (!line.startsWith("pass in ") || line.indexOf(" proto tcp ") < 0 || to < 0) { continue; }
            String destination = line.substring(to) + " ";
            if (destination.indexOf(" port = " + port + " ") >= 0
                || destination.indexOf(" port " + port + " ") >= 0) { return true; }
        }
        return false;
    }

    private static void addRules(Vector lines, int[] wanted, String[] ifaces) {
        int at = insertionPoint(lines);
        lines.insertElementAt("# lsd-extender TCP ports (generated, /etc untouched)", at++);
        for (int p = 0; p < wanted.length; p++) {
            for (int i = 0; i < ifaces.length; i++) {
                lines.insertElementAt("pass in quick on " + ifaces[i] + " proto tcp from any to (" + ifaces[i]
                    + ") port " + wanted[p] + " keep state allow-opts", at++);
            }
        }
        lines.insertElementAt("# end lsd-extender", at);
    }

    private static Vector readLines(String path) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
        try {
            Vector lines = new Vector();
            String line;
            while ((line = in.readLine()) != null) { lines.addElement(line); }
            return lines;
        } finally { in.close(); }
    }

    private static void writeLines(String path, Vector lines) throws IOException {
        FileOutputStream file = new FileOutputStream(path);
        Writer out = new OutputStreamWriter(file);
        try {
            for (int i = 0; i < lines.size(); i++) {
                out.write((String) lines.elementAt(i));
                out.write("\n");
            }
        } finally {
            // A failed buffered flush must not leave the underlying file open.
            try { out.close(); } finally { file.close(); }
        }
    }

    /** After the firmware's SSH pass rules, else before the first block. */
    private static int insertionPoint(Vector lines) {
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = ((String) lines.elementAt(i)).trim();
            if (line.startsWith("pass") && line.indexOf(" port 22 ") >= 0) { at = i + 1; }
        }
        if (at >= 0) { return at; }
        for (int i = 0; i < lines.size(); i++) {
            if (((String) lines.elementAt(i)).trim().startsWith("block")) { return i; }
        }
        return lines.size();
    }

    private static String command(String[] argv) { return PfCommand.run(argv); }

    private static String[] split(String text) {
        java.util.StringTokenizer tokens = new java.util.StringTokenizer(text);
        String[] values = new String[tokens.countTokens()];
        for (int i = 0; i < values.length; i++) { values[i] = tokens.nextToken(); }
        return values;
    }
}
