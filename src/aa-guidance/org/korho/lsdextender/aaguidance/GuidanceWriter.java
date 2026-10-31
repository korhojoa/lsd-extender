/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aaguidance;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.LsdExtender;
import de.vw.mib.asl.api.navbap.ASLNavBAPFactory;
import de.vw.mib.asl.framework.internal.framework.GenericCollector;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import generated.de.vw.mib.asl.internal.ListManager;

/**
 * The writes that put route guidance on the instrument cluster, exactly as the stock
 * feeder (HsmTargetBap / ASLNavBAPAPIImpl) performs them. Callers run them on the ASL
 * thread through post().
 */
public final class GuidanceWriter {
    private static final String P = " nav: ";

    /** True after the gate has suppressed a write and until one goes through again. */
    private static boolean suspended;

    private GuidanceWriter() {
    }

    /** Never publish phone guidance over an active or unavailable native route. */
    private static synchronized boolean shouldYield() {
        boolean active = NativeGuidance.blocked();
        if (active) {
            if (!suspended) {
                suspended = true;
                LsdExtender.trace(P + "writes suspended, the built-in nav has an active route");
            }
            return true;
        }
        if (suspended) {
            suspended = false;
            LsdExtender.trace(P + "writes resumed");
        }
        return false;
    }

    /** Run r on the ASL thread now (framework hand-off); Deferred is the fallback. */
    public static boolean post(final String what, final Runnable r) {
        Runnable job = new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    LsdExtender.trace(P + what + " failed: " + t);
                }
            }
        };
        try {
            if (ServiceManager.aslThreadSwitch != null) {
                ServiceManager.aslThreadSwitch.enqueue(job);
                return true;
            }
        } catch (Throwable t) {
            LsdExtender.trace(P + what + ": aslThreadSwitch.enqueue failed, deferring: " + t);
        }
        final Runnable j = job;
        LsdExtender.defer(new Deferred() {
            public boolean run() {
                j.run();
                return true;
            }

            public String name() {
                return "nav " + what;
            }
        });
        return false;
    }

    /** Datapool 732: 0 off, 1 guiding, 2 calculating. Native operation state is preserved. */
    public static void routeGuidanceState(int rg) {
        if (shouldYield()) {
            return;
        }
        ServiceManager.aslPropertyManager.valueChangedInteger(732, rg);
        LsdExtender.trace(P + "route guidance state 732 = " + rg);
    }

    /** Lists 751 and 838, one row per maneuver: {mainElement, direction, zLevel}, sidestreets. */
    public static void maneuvers(int[][] turns, String[] sides) {
        if (shouldYield()) {
            return;
        }
        if (!Config.getBool("lsdextender.nav.arrows", true)) {
            turns = null; // the no-maneuver row, as at guidance off
            sides = null;
        }
        int n =turns == null || turns.length == 0 ? 1 : turns.length;
        GenericCollector[] rows = new GenericCollector[n];
        for (int i = 0; i < n; i++) {
            GenericCollector c = new GenericCollector();
            int[] t = turns == null || turns.length == 0 ? new int[]{0, 0, 0} : turns[i];
            c.setIntItem(0, t[0]);
            c.setIntItem(1, t[1]);
            c.setIntItem(2, t.length > 2 ? t[2] : 0);
            c.setStringItem(3, sides == null || sides.length <= i || sides[i] == null ? "" : sides[i]);
            rows[i] = c;
        }
        ListManager.getGenericASLList(751).updateList(rows);
        ListManager.getGenericASLList(838).updateList(rows);
        StringBuffer b = new StringBuffer(P + "maneuver list 751/838 =");
        for (int i = 0; i < n; i++) {
            b.append(' ').append(rows[i].getIntItem(0)).append('/').append(rows[i].getIntItem(1))
                .append('/').append(rows[i].getIntItem(2));
        }
        LsdExtender.trace(b.toString());
    }

    /** List 741: metres (the cluster function formats and caps them); bargraph -1 = off. */
    public static void distance(int metres, int bargraphPct) {
        if (shouldYield()) {
            return;
        }
        ASLNavBAPFactory.getNavBAPApi()
            .updateBapDistanceToNextManeuver(metres, 0, bargraphPct >= 0, bargraphPct >= 0 ? bargraphPct : 0);
        LsdExtender.trace(P + "distance 741 = " + metres + " m, bargraph " + bargraphPct);
    }

    /**
     * Datapool 734, GPS signal available (stock: HsmTargetBap from the position quality of
     * every position update). InfoStates (38) reports 3 (no GPS) instead of 0 while it is
     * false, and nothing writes it while the built-in nav is idle.
     */
    public static void gpsAvailable(boolean on) {
        if (shouldYield()) {
            return;
        }
        ServiceManager.aslPropertyManager.valueChangedBoolean(734, on);
        LsdExtender.trace(P + "gps signal available 734 = " + on);
    }

    /** 744: road to turn into and sign post text. */
    public static void road(String road, String sign) {
        if (shouldYield()) {
            return;
        }
        road = road == null ? "" : road;
        sign = sign == null ? "" : sign;
        // the clear write (both empty) stays empty whatever the templates say
        if (road.length() > 0 || sign.length() > 0) {
            String r = road;
            String s = sign;
            road = fill(Config.get("lsdextender.nav.turnto", "{road}"), r, s);
            sign = fill(Config.get("lsdextender.nav.turnto.sign", "{sign}"), r, s);
        }
        ASLNavBAPFactory.getNavBAPApi().updateBapTurnToInfo(road, sign);
        LsdExtender.trace(P + "turn-to info 744 = '" + road + "' sign '" + sign + "'");
    }

    /** Max length of a 744 string; the AA feed cuts the road to this too. */
    private static final int TURN_TO_MAX = 75;

    /**
     * A turn-to template with {road} and {sign} replaced by the feed's values, cut to
     * TURN_TO_MAX. Any other text is sent as written; an empty template sends nothing.
     */
    static String fill(String template, String road, String sign) {
        StringBuffer b = new StringBuffer();
        int i = 0;
        while (i < template.length()) {
            if (template.startsWith("{road}", i)) {
                b.append(road);
                i += 6;
            } else if (template.startsWith("{sign}", i)) {
                b.append(sign);
                i += 6;
            } else {
                b.append(template.charAt(i));
                i++;
            }
        }
        return b.length() > TURN_TO_MAX ? b.toString().substring(0, TURN_TO_MAX) : b.toString();
    }

    /** Clear lane data when the phone guidance ends. */
    public static void clearLanes() {
        if (shouldYield()) {
            return;
        }
        ASLNavBAPFactory.getNavBAPApi().updateBapLaneGuidance(null, false);
        LsdExtender.trace(P + "lanes 742 off");
    }
}
