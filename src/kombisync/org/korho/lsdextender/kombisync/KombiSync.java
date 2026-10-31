/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.kombisync;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.dsi.ifc.komogfxstreamsink.DSIKOMOGfxStreamSink;
import org.dsi.ifc.komogfxstreamsink.DSIKOMOGfxStreamSinkListener;

public final class KombiSync implements MflListener, DSIKOMOGfxStreamSinkListener, Deferred {
    private static final int MAX_ATTEMPTS = 300;

    private boolean subscribed;
    private int attempts;

    // the last valid values for the state object; -1 = unknown/invalid (key deleted)
    private int gfxState = -1;
    private int syncNeeded = -1;
    private int dataRate = -1;
    private final SinkCycle cycle = new SinkCycle(SinkCycle.newEpoch(
        System.currentTimeMillis(), org.korho.lsdextender.EventClock.millis(), System.identityHashCode(this)));
    /** The PPS object GAL reads, with sequence and timestamp fields from PpsObject. */
    public static final String DEFAULT_FILE = "/ramdisk/pps/gal/cluster";
    private final org.korho.lsdextender.PpsObject file = new org.korho.lsdextender.PpsObject(
        "kombisync", Config.get("lsdextender.kombisync.file", DEFAULT_FILE));

    private synchronized void publish() {
        if (Config.getInt("lsdextender.kombisync.file_enabled", 1) == 0) {
            return;
        }
        file.setPath(Config.get("lsdextender.kombisync.file", DEFAULT_FILE));
        file.publish(body(gfxState, syncNeeded, dataRate) + cycle.body());
    }

    /** The attribute lines: a value, or its deletion while invalid (-1). */
    static String body(int gfxState, int syncNeeded, int dataRate) {
        StringBuffer b = new StringBuffer();
        b.append(gfxState >= 0 ? "gfx_state=" + gfxState : "-gfx_state").append('\n');
        b.append(syncNeeded >= 0 ? "sync_needed=" + syncNeeded : "-sync_needed").append('\n');
        b.append(dataRate >= 0 ? "data_rate=" + dataRate : "-data_rate").append('\n');
        return b.toString();
    }

    static {
        try {
            KombiSync k = new KombiSync();
            LsdExtender.register(k);
            LsdExtender.defer(k);
        } catch (Throwable ignored) {
            // fail open
        }
    }

    private KombiSync() {
    }

    // MflListener: no gestures wanted; registration only gives core a name to log
    public String name() {
        return "kombisync [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    // Deferred: subscribe once the HMI's DSI proxy for the sink can be obtained
    public boolean run() {
        if (subscribed) {
            return true;
        }
        if (!Config.getBool("lsdextender.kombisync.enabled", true)) {
            LsdExtender.trace(" kombisync: disabled by config");
            return true;
        }
        attempts++;
        HsmTarget t = LsdExtender.siTarget();
        if (t == null) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            int[] attrs = parseAttrs(Config.get("lsdextender.kombisync.attrs", "1,2,3"));
            // Subscribe from the sink registration callback so notification setup
            // runs only after the service is registered.
            DsiSubscribe.whenRegistered("kombisync", DSIKOMOGfxStreamSink.class, attrs, this);
            subscribed = true;
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(" kombisync: subscribe attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    private static int[] parseAttrs(String s) {
        int n = 0;
        int[] tmp = new int[16];
        int start = 0;
        while (start <= s.length() && n < tmp.length) {
            int comma = s.indexOf(',', start);
            if (comma < 0) {
                comma = s.length();
            }
            String part = s.substring(start, comma).trim();
            if (part.length() > 0) {
                tmp[n++] = Integer.parseInt(part);
            }
            start = comma + 1;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static String valid(int flag) {
        return flag == (1) ? "1" : flag == (2) ? "0" : "?";
    }

    // DSIKOMOGfxStreamSinkListener
    public synchronized void updateGfxState(int state, int validFlag) {
        LsdExtender.trace(" kombisync: GFXSTATE=" + state
            + (state == (1) ? " (AVAILABLE)" : " (UNAVAILABLE)")
            + " valid=" + valid(validFlag));
        boolean known = state == (1)
            || state == (0);
        if (validFlag == (1) && known) {
            cycle.observe(state, true);
        } else {
            cycle.invalidate(SinkCycle.newEpoch(System.currentTimeMillis(),
                org.korho.lsdextender.EventClock.millis(), System.identityHashCode(this)));
        }
        gfxState = validFlag == (1) && known ? state : -1;
        publish();
    }

    public synchronized void updateRequestSync(int request, int validFlag) {
        LsdExtender.trace(" kombisync: REQUESTSYNC=" + request
            + (request == (1) ? " (SYNCNEEDED)" : " (SYNCSATISFIED)")
            + " valid=" + valid(validFlag));
        syncNeeded = validFlag == (1) ? (request == (1) ? 1 : 0) : -1;
        publish();
    }

    public synchronized void updateDataRate(int rate, int validFlag) {
        LsdExtender.trace(" kombisync: DATARATE=" + rate + " valid=" + valid(validFlag));
        dataRate = validFlag == (1) && rate >= 0 && rate <= 2 ? rate : -1;
        publish();
    }

    public void setFGLayerResult(int result) {
        LsdExtender.trace(" kombisync: setFGLayerResult(" + result + ")");
    }

    public void fadeInResult() {
        LsdExtender.trace(" kombisync: fadeInResult");
    }

    public void fadeOutResult() {
        LsdExtender.trace(" kombisync: fadeOutResult");
    }

    // DSIListener
    public void asyncException(int i, String s, int j) {
        LsdExtender.trace(" kombisync: asyncException(" + i + ", " + s + ", " + j + ")");
    }
}
