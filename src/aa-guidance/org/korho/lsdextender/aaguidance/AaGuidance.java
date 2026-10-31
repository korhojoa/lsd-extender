/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aaguidance;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.base.DSIListener;

/**
 * Android Auto's next-turn data to the instrument cluster. gal already forwards the
 * phone's NavigationNextTurnEvent / NavigationNextTurnDistanceEvent (protocol 1.2 pair,
 * AA message ids 0x8004/0x8005) to the HMI as DSIAndroidAuto2 attributes 7 and 8; the
 * stock HMI target never subscribes to them and its handlers are empty
 * (AndroidAutoTarget, DSI_ANDROIDAUTO2_ATTR = {1, 6, 2}). This class subscribes, maps the
 * turn to the BAP maneuver descriptor and writes the guidance datapools through
 * GuidanceWriter, i.e. what the stock nav does for its own route.
 *
 * DSI callbacks (DSIAndroidAuto2Listener):
 *   updateNavigationNextTurnEvent(String road, int turnSide, int event, int turnAngle, int turnNumber, int state)
 *   updateNavigationNextTurnDistance(int distanceMeters, int timeToTurnSeconds, int state)
 * The native DSI callback supplies angle before exit number. This differs from the
 * AA protobuf field order; the native producer and retained roundabout callbacks
 * establish the order. Both handlers log their first sample with all arguments.
 *
 * turn_side: 1 LEFT, 2 RIGHT, 3 UNSPECIFIED (AA), 0 unspecified (DSI Constants).
 * event (NextTurnEnum): 0 UNKNOWN, 1 DEPART, 2 NAME_CHANGE, 3 SLIGHT_TURN, 4 TURN,
 * 5 SHARP_TURN, 6 U_TURN, 7 ON_RAMP, 8 OFF_RAMP, 9 FORK, 10 MERGE, 11 ROUNDABOUT_ENTER,
 * 12 ROUNDABOUT_EXIT, 13 ROUNDABOUT_ENTER_AND_EXIT, 14 STRAIGHT, 16 FERRY_BOAT,
 * 17 FERRY_TRAIN, 19 DESTINATION. The mapping supplies BAP mainElement and direction.
 *
 * Guidance state: 732 goes to 1 on the first valid turn event and back to 0 when the
 * turn event becomes invalid, a DESTINATION turn is reached at 0 m, or no distance update
 * arrived for lsdextender.nav.aa.timeout_s (600). Config: lsdextender.nav.aa (false),
 * lsdextender.nav.aa.lefthand (false: right-hand traffic, roundabout symbols 21/26),
 * lsdextender.nav.aa.bargraph (true: bar graph from distance / first distance).
 */
public final class AaGuidance implements MflListener, Deferred, InvocationHandler {
    private static final String P = " navaa: ";
    private static final int MAX_ATTEMPTS = 300;
    // Left/right main elements, then left/right directions, indexed by AA event code.
    private static final int[][] MANEUVERS = {
        {11, 11, 0, 0}, // 0 UNKNOWN
        {11, 11, 0, 0}, // 1 DEPART
        {11, 11, 0, 0}, // 2 NAME_CHANGE
        {13, 13, 32, 224}, // 3 SLIGHT
        {13, 13, 64, 192}, // 4 TURN
        {13, 13, 96, 160}, // 5 SHARP
        {25, 25, 114, 146}, // 6 UTURN
        {14, 14, 64, 192}, // 7 ONRAMP
        {16, 15, 32, 224}, // 8 OFFRAMP
        {19, 19, 32, 224}, // 9 FORK
        {0, 0, 0, 0}, // 10 MERGE (dynamic)
        {29, 29, 0, 0}, // 11 ROUNDABOUTENTER
        {0, 0, 0, 0}, // 12 ROUNDABOUTEXIT (dynamic)
        {0, 0, 0, 0}, // 13 ROUNDABOUTBOTH (dynamic)
        {13, 13, 0, 0}, // 14 STRAIGHT
        {11, 11, 0, 0}, // 15 unknown
        {11, 11, 0, 0}, // 16 FERRY_BOAT
        {11, 11, 0, 0}, // 17 FERRY_TRAIN
        {11, 11, 0, 0}, // 18 unknown
        {0, 0, 0, 0}, // 19 DESTINATION (dynamic)
    };

    private int attempts;
    private boolean subscribed;
    private final java.util.Hashtable seen = new java.util.Hashtable();

    // last raw values
    private String road = "";
    private int side;
    private int event = -1;
    private int turnNumber;
    private int turnAngle;
    private int turnState;
    private int distM = -1;
    private int firstDist = -1;
    private long lastDistanceMs;
    private boolean guiding;
    private volatile boolean stopped;
    private Thread watchdogThread;

    /**
     * Called reflectively by core on "reload" before the registration is dropped; core
     * removes the DSI subscription itself. The guidance datapools are left as they are,
     * the fresh instance takes over on the next callback.
     */
    public synchronized void shutdown() {
        stopped = true;
        NativeGuidance.close();
        Thread t = watchdogThread;
        if (t != null) {
            t.interrupt();
        }
        LsdExtender.trace(P + "shut down (watchdog stopped)");
    }

    static {
        try {
            AaGuidance a = new AaGuidance();
            LsdExtender.register(a);
            LsdExtender.defer(a);
        } catch (Throwable ignored) {
            // fail open
        }
    }

    private AaGuidance() {
    }

    public String name() {
        return "aa-guidance [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    // Deferred: subscribe once the DSI proxy API is reachable (same shape as EvFile)
    public synchronized boolean run() {
        if (stopped) { return true; }
        if (subscribed) {
            return true;
        }
        // This legacy AA event pair can stop updating under protocol 1.6.
        // Enable only when the phone provides turn and distance updates.
        if (!Config.getBool("lsdextender.nav.aa", false)) {
            LsdExtender.trace(P + "disabled (lsdextender.nav.aa=false; the 1.6 session sends the 1.2 pair once)");
            return true;
        }
        attempts++;
        if (LsdExtender.siTarget() == null) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            if (!NativeGuidance.open()) {
                throw new IllegalStateException("native guidance service unavailable");
            }
            Class lst = Class.forName("org.dsi.ifc.androidauto2.DSIAndroidAuto2Listener");
            Object listener = Proxy.newProxyInstance(lst.getClassLoader(), new Class[]{lst}, this);
            DsiSubscribe.whenRegistered("navaa", "org.dsi.ifc.androidauto2.DSIAndroidAuto2", new int[]{7, 8},
                (DSIListener)listener);
            subscribed = true;
            Thread w = new Thread(new Runnable() {
                public void run() {
                    watchdog();
                }
            }, "lsd-extender-navaa");
            w.setDaemon(true);
            watchdogThread = w;
            w.start();
            LsdExtender.trace(P + "subscription armed (DSIAndroidAuto2 attrs 7,8)");
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(P + "subscribe attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    // InvocationHandler for the listener proxy
    public Object invoke(Object proxy, Method m, Object[] args) {
        String n = m.getName();
        if (n.equals("hashCode")) {
            return new Integer(System.identityHashCode(proxy));
        }
        if (n.equals("equals")) {
            return new Boolean(proxy == (args == null ? null : args[0]));
        }
        if (n.equals("toString")) {
            return "lsd-extender navaa listener";
        }
        try {
            handle(n, args);
        } catch (Throwable t) {
            LsdExtender.trace(P + n + " failed: " + t);
        }
        Class r = m.getReturnType();
        if (r == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (r == Integer.TYPE || r == Short.TYPE || r == Byte.TYPE) {
            return new Integer(0);
        }
        if (r == Long.TYPE) {
            return new Long(0L);
        }
        if (r == Float.TYPE) {
            return new Float(0f);
        }
        if (r == Double.TYPE) {
            return new Double(0d);
        }
        return null;
    }

    private void firstSample(String n, Object[] args) {
        if (seen.get(n) == null) {
            seen.put(n, n);
            StringBuffer b = new StringBuffer(P + "first ").append(n).append('(');
            for (int i = 0; args != null && i < args.length; i++) {
                b.append(i > 0 ? ", " : "").append(args[i]);
            }
            LsdExtender.trace(b.append(')').toString());
        }
    }

    private static int i(Object o) {
        return o instanceof Number ? ((Number)o).intValue() : 0;
    }

    private synchronized void handle(String n, Object[] args) {
        if (stopped) {
            return; // a late callback after reload belongs to the fresh instance
        }
        if (n.equals("updateNavigationNextTurnEvent")) {
            firstSample(n, args);
            road = args[0] == null ? "" : String.valueOf(args[0]);
            side = i(args[1]);
            event = i(args[2]);
            turnAngle = i(args[3]);
            turnNumber = i(args[4]);
            turnState = args.length > 5 ? i(args[5]) : 1;
            lastDistanceMs = System.currentTimeMillis(); // a turn event keeps the guidance alive too
            LsdExtender.trace(P + "turn event=" + event + " side=" + side + " angle=" + turnAngle + " n=" + turnNumber
                + " state=" + turnState + " road='" + road + "'");
            if (turnState == 1 || args.length <= 5) {
                startOrUpdate();
            } else {
                stop("turn event state " + turnState);
            }
        } else if (n.equals("updateNavigationNextTurnDistance")) {
            firstSample(n, args);
            distM = i(args[0]);
            lastDistanceMs = System.currentTimeMillis();
            if (firstDist < 0 || distM > firstDist) {
                firstDist = distM;
            }
            if (guiding) {
                final int m = distM;
                final int bar = bargraph();
                final boolean arrived = event == 19 && distM <= 0;
                GuidanceWriter.post("aa distance", new Runnable() {
                    public void run() {
                        GuidanceWriter.distance(m, bar);
                        if (arrived) {
                            GuidanceWriter.maneuvers(new int[][]{{3, 0, 0}}, new String[]{""});
                        }
                    }
                });
            }
        } else {
            firstSample(n, args);
        }
    }

    private int bargraph() {
        if (!Config.getBool("lsdextender.nav.aa.bargraph", true) || firstDist <= 0 || distM < 0) {
            return -1;
        }
        int pct = 100 - distM * 100 / firstDist;
        return pct < 0 ? 0 : (pct > 100 ? 100 : pct);
    }

    private void startOrUpdate() {
        final int[] me = map(event, side, turnAngle, distM);
        final String r = road.length() > 75 ? road.substring(0, 75) : road;
        final boolean first = !guiding;
        guiding = true;
        if (first) {
            firstDist = distM;
        }
        final int m = distM;
        final int bar = bargraph();
        GuidanceWriter.post("aa turn", new Runnable() {
            public void run() {
                // guidance state first: ManeuverDescriptor and DistanceToNextManeuver re-send
                // on a 732 change, TurnToInfo (744) does not, so text written while 732 was

                if (first) {
                    if (Config.getBool("lsdextender.nav.aa.gps", true)) {
                        GuidanceWriter.gpsAvailable(true); // the phone has a fix, the idle nav never says so
                    }
                    GuidanceWriter.routeGuidanceState(1);
                }
                GuidanceWriter.maneuvers(new int[][]{me}, new String[]{""});
                if (m >= 0) {
                    GuidanceWriter.distance(m, bar);
                }
                GuidanceWriter.road(r, "");
            }
        });
    }

    private void stop(String why) {
        if (!guiding) {
            return;
        }
        guiding = false;
        firstDist = -1;
        LsdExtender.trace(P + "guidance off: " + why);
        GuidanceWriter.post("aa off", new Runnable() {
            public void run() {
                GuidanceWriter.clearLanes();
                GuidanceWriter.distance(-1, -1);
                GuidanceWriter.maneuvers(null, null);
                GuidanceWriter.road("", "");
                GuidanceWriter.routeGuidanceState(0);
            }
        });
    }

    private void watchdog() {
        while (!stopped) {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                return;
            }
            if (stopped) {
                return;
            }
            try {
                long timeout = (long)Config.getInt("lsdextender.nav.aa.timeout_s", 600) * 1000L;
                synchronized (this) {
                    if (guiding && lastDistanceMs > 0 && System.currentTimeMillis() - lastDistanceMs > timeout) {
                        stop("no distance update for " + timeout / 1000L + " s");
                    }
                }
            } catch (Throwable t) {
                LsdExtender.trace(P + "watchdog: " + t);
            }
        }
    }

    /**
     * NextTurnEnum + side + angle -> {mainElement, direction, zLevel} (BAP values, run through
     * the stock validateManeuverData by the ManeuverDescriptor function). Direction is
     * 0..255 counter-clockwise from straight.
     */
    static int[] map(int ev, int side, int angle, int distM) {
        boolean left = side == 1;
        boolean lefthand = Config.getBool("lsdextender.nav.aa.lefthand", false);
        switch (ev) {
            case 10: // MERGE also has an unspecified side.
                return new int[]{12, side == 1 ? 32 : (side == 2 ? 224 : 0), 0};
            case 12:
                return new int[]{lefthand ? 27 : 26, angleToDirection(angle), 0};
            case 13:
                return new int[]{lefthand ? 22 : 21, angleToDirection(angle), 0};
            case 19:
                return new int[]{distM == 0 ? 3 : 4, 0, 0};
            default:
                if (ev < 0 || ev >= MANEUVERS.length) { return new int[]{11, 0, 0}; }
                int[] row = MANEUVERS[ev];
                return new int[]{row[left ? 0 : 1], row[left ? 2 : 3], 0};
        }
    }

    /** Convert clockwise AA turn_angle degrees to a BAP direction byte with a nonnegative modulo. */
    static int angleToDirection(int angle) {
        int a = ((180 + 11 + angle) % 360 + 360) % 360;
        return (a * 16 / 360) * 16;
    }
}
