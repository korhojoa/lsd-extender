/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.dsispy;

import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Slog;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.Target;
import java.lang.reflect.Field;
import java.util.StringTokenizer;
import org.dsi.ifc.displaymanagement.DSIDisplayManagement;

final class DisplayableCommands implements CommandHandler {
    private static final String PREFIX = "displayable ";
    private static final int CROP_ARGS = 9;
    private static final int MAX_ALPHA = 255;

    public String command(String line) {
        if (!line.startsWith(PREFIX)) { return null; }
        StringTokenizer t = new StringTokenizer(line.substring(PREFIX.length()));
        if (!t.hasMoreTokens()) { return usage(); }
        String verb = t.nextToken();
        final int[] a = new int[t.countTokens()];
        try {
            for (int i = 0; i < a.length; i++) { a[i] = Integer.parseInt(t.nextToken()); }
        } catch (NumberFormatException e) {
            return usage();
        }
        if (verb.equals("crop") && (a.length == CROP_ARGS || a.length == CROP_ARGS + 1)) {
            final int display = a.length > CROP_ARGS ? a[CROP_ARGS] : 0;
            enqueue(line, new Call() {
                public void run(DSIDisplayManagement dsi) {
                    dsi.setCropping(display, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]);
                }
            });
            return "ok queued " + line;
        }
        if (verb.equals("opacity") && (a.length == 2 || a.length == 3) && a[1] >= 0 && a[1] <= MAX_ALPHA) {
            final int display = a.length > 2 ? a[2] : 0;
            enqueue(line, new Call() {
                public void run(DSIDisplayManagement dsi) { dsi.setOpacity(a[0], display, a[1]); }
            });
            return "ok queued " + line;
        }
        return usage();
    }

    private interface Call { void run(DSIDisplayManagement dsi); }

    private static String usage() {
        return "err usage: displayable crop <id> <srcX> <srcY> <srcW> <srcH> <dstX> <dstY> <dstW> <dstH> [display]"
            + " | displayable opacity <id> <alpha 0..255> [display]";
    }

    /** On the ASL thread, like the stock display target's own DSI calls. */
    private static void enqueue(final String line, final Call call) {
        ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
            public void run() {
                try {
                    Target actual = ServiceManager.eventMain.getEventDispatcher().getTarget(0x111700);
                    if (actual == null) { throw new IllegalStateException("display target unavailable"); }
                    Field f = actual.getClass().getDeclaredField("dsiDisplayManagement");
                    f.setAccessible(true);
                    DSIDisplayManagement dsi = (DSIDisplayManagement)f.get(actual);
                    if (dsi == null) { throw new IllegalStateException("display service not ready"); }
                    call.run(dsi);
                    log("done " + line);
                } catch (Throwable failed) {
                    log("failed " + line + ": " + failed);
                }
            }
        });
    }

    private static void log(String text) { Slog.write("displayable: " + EventClock.stamp() + " " + text); }
}
