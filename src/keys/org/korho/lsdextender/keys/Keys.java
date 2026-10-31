/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.KeyListener;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.dsi.ifc.keypanel.DSIKeyPanel;
import org.dsi.ifc.keypanel.DSIKeyPanelListener;

/**
 * Every hardkey and every touch sample the HMI receives, independent of the active
 * source: subscribes to the key panel DSI service (KEY2 = 25, ENCODER2 = 23,
 * GESTURE2 = 20) on the HMI's shared proxy and reports each event to the TCP feed
 * (and slog), and to KeyListener features through LsdExtender.onKey().
 *
 *   lsd-extender keys: kbd=3 key=42 state=1 (press)
 *   lsd-extender keys: encoder kbd=3 key=16 inc=-1
 *   lsd-extender touch: kbd=13 g=4 (press) x=512 y=300 p1=0 p2=0 t=123456
 *   lsd-extender touch: kbd=13 g=5 (drag) x=520 y=302 p1=8 p2=2 t=123472
 *
 * Touch gesture codes (org.dsi.ifc.keypanel.Constants): 1 tap, 2 doubletap,
 * 3 release, 4 press, 5 drag, 6 flick, 7 rotate, 8 press2, 9 drag2, 10 flick2,
 * 11 zoom, 12 rightclick, 13 coordinates, 14/15 raw2. x/y are screen pixels; p1/p2
 * are the gesture's parameters (click count for tap, dx/dy for drag and flick, the
 * two-finger value for rotate/zoom); t is the key panel's timestamp. Drag samples
 * go to the feed only unless lsdextender.touch.slog=true (they come at the panel's
 * sample rate).
 *
 * Config: lsdextender.keys.enabled (default true),
 *         lsdextender.keys.attrs (default "25,23,20"),
 *         lsdextender.touch.slog (default false: drag/coordinates lines feed-only).
 * The HMI's own handling of keys and touch is untouched (an added listener).
 * Shared touch interception and the former global grab are unavailable.
 */
public final class Keys implements MflListener, DSIKeyPanelListener, Deferred, KeyListener {
    private static final int MAX_ATTEMPTS = 300;
    interface Setup {
        boolean targetReady();
        RestartCapability capability();
        void subscribe(Keys listener);
    }
    private final Setup setup;

    private boolean subscribed;
    private int attempts;
    private volatile RestartCapability restartCapability;
    private volatile boolean retired;

    static {
        try {
            Keys k = new Keys();
            LsdExtender.register(k);
            LsdExtender.registerKeys(k);
            LsdExtender.defer(k);
        } catch (Throwable ignored) {
            // fail open
        }
    }

    private Keys() {
        this(new NativeSetup());
    }

    Keys(Setup selected) { setup = selected; }

    public String name() {
        return "keys [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    public synchronized boolean run() {
        if (retired) { return true; }
        if (subscribed) {
            return true;
        }
        if (!Config.getBool("lsdextender.keys.enabled", true)) {
            LsdExtender.trace(" keys: disabled by config");
            return true;
        }
        attempts++;
        if (!setup.targetReady()) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            if (restartCapability == null) {
                restartCapability = setup.capability();
                restartCapability.start();
            }
            // subscribed from the service's registration callback (DsiSubscribe): a
            // setNotification made before the service is up is silently lost
            setup.subscribe(this);
            subscribed = true;
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(" keys: subscribe attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    private static String stateName(int s) {
        switch (s) {
            case 0: return "release";
            case 1: return "press";
            case 2: return "double";
            case 3: return "long";
            case 4: return "longer";
            case 5: return "ultralong";
            default: return "state" + s;
        }
    }

    // The callback supplies keyboard, key, state and two additional native arguments.
    public void updateKey2(int keyboard, int key, int state, int l, int i1) {
        LsdExtender.trace(" keys: kbd=" + keyboard + " key=" + key + " state=" + state
            + " (" + stateName(state) + ")" + (l != 0 || i1 != 0 ? " x=" + l + "," + i1 : ""));
        try {
            RestartCapability capability = restartCapability;
            if (capability != null) { capability.onRawKey(keyboard, key, state, i1); }
        } finally { LsdExtender.onKey(keyboard, key, state); }
    }

    /** Generic fanout omits validity and is never a restart authorization. */
    public boolean onKey(int keyboard, int key, int state) { return false; }

    public void shutdown() {
        RestartCapability capability;
        synchronized (this) { retired = true; capability = restartCapability; }
        if (capability != null) { capability.shutdown(); }
    }

    private static final class NativeSetup implements Setup {
        public boolean targetReady() { return LsdExtender.siTarget() != null; }
        public RestartCapability capability() { return new RestartCapability(); }
        public void subscribe(Keys listener) {
            int[] attrs = parseAttrs(Config.get("lsdextender.keys.attrs", "25,23,20"));
            DsiSubscribe.whenRegistered("keys", DSIKeyPanel.class, attrs, listener);
        }
    }

    public void updateEncoder2(int keyboard, int key, int inc, int l, int i1) {
        LsdExtender.trace(" keys: encoder kbd=" + keyboard + " key=" + key + " inc=" + inc
            + (l != 0 || i1 != 0 ? " x=" + l + "," + i1 : ""));
        LsdExtender.onEncoder(keyboard, key, inc);
    }

    public void updateDisplayTurnMechStatus(int i, int j) {
    }

    public void updateRecognizerLanguage2(int i, String s, int j, int k) {
    }

    public void updateRecognizerMode(int i, int j, int k) {
    }

    public void updateCharacterEvent2(int i, String[] astring, int[] aint, int j) {
    }

    private static String gestureName(int g) {
        switch (g) {
            case 1: return "tap";
            case 2: return "doubletap";
            case 3: return "release";
            case 4: return "press";
            case 5: return "drag";
            case 6: return "flick";
            case 7: return "rotate";
            case 8: return "press2";
            case 9: return "drag2";
            case 10: return "flick2";
            case 11: return "zoom";
            case 12: return "rightclick";
            case 13: return "coordinates";
            case 14: return "raw2";
            case 15: return "raw2plus";
            default: return "gesture" + g;
        }
    }

    private float scaleX = -1, scaleY = -1;

    // the launcher's -Dde.vw.mib.asl.keypanel.touch.scaleX/Y (2.0 on this unit: the
    // panel reports half the 1280x640 pixel grid); same factor the HMI applies
    private float scale(String prop) {
        try {
            return Float.parseFloat(Config.get(prop, "1.0"));
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    // DSIKeyPanelListener GESTURE2, argument order as the HMI's TouchInputDeviceImpl
    // reads it: (keyboard, gesture, k, flag, x, y, param1, param2, time, valid).

    // rings from it): pixel coordinates of the 1280x640 panel, raw gesture code,
    // f=1 for the two-finger codes 7-12, t in ms (panel time x the HMI's factor).
    public void updateGesture2(int keyboard, int gesture, int k, boolean flag, int x, int y, int p1, int p2, int time, int valid) {
        if (scaleX < 0) {
            scaleX = scale("de.vw.mib.asl.keypanel.touch.scaleX");
            scaleY = scale("de.vw.mib.asl.keypanel.touch.scaleY");
        }
        int px = (int) (x * scaleX);
        int py = (int) (y * scaleY);
        int finger = gesture >= 7 && gesture <= 12 ? 1 : 0;
        long ms = (long) time * de.vw.mib.asl.internal.system.AslTargetSystemKeyPanelHandling.UPDATE_GESTURE2_TIME_SCALE_FACTOR;
        String line = " touch: g=" + gesture + " f=" + finger + " x=" + px + " y=" + py
            + " p1=" + p1 + " p2=" + p2 + " t=" + ms + " kbd=" + keyboard + " (" + gestureName(gesture) + ")"
            + (k != 0 || flag ? " k=" + k + " flag=" + (flag ? 1 : 0) : "");
        LsdExtender.onTouch(gesture, px, py, org.korho.lsdextender.EventClock.millis());
        boolean continuous = gesture == 5 || gesture == 9 || gesture == 13 || gesture == 14 || gesture == 15;
        if (continuous && !Config.getBool("lsdextender.touch.slog", false)) {
            LsdExtender.feed(line);
        } else {
            LsdExtender.trace(line);
        }
    }

    public void genericSettingResponse(int i, int j, int k) {
    }

    public void updateProximity(int i, int j, int k) {
    }

    public void updateAdvancedProximity(int i, int j, int k, int l, int i1, int j1, int k1, int l1, int i2, int j2) {
    }

    public void lastKey(int i, int j, int k) {
    }

    public void updateKeyboardType(int i, int j) {
    }

    public void updateTouchSensitiveArea(int i, int j, int k, int l, int i1, int j1) {
    }

    public void getVersionInfo(int i, int j, String s) {
    }

    public void updateInputPanelReady(int i, int j, int k) {
    }

    public void getProperty(int i, int j, int k, int l, byte[] abyte) {
    }

    // DSIListener
    public void asyncException(int i, String s, int j) {
        LsdExtender.trace(" keys: asyncException(" + i + ", " + s + ", " + j + ")");
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

}
