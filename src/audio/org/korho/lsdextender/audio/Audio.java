/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.audio;

import org.korho.lsdextender.AudioSource;
import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.EncoderListener;
import org.korho.lsdextender.KeyListener;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.boot.HookInstaller;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.dsi.ifc.audio.DSISound;

/**
 * Volume keys while the HMI declines them, and the entertainment source hold for
 * native programs, on the feed and on the hardkeys.
 *
 * Manual source hold for native programs. Games use NativeMediaSession, which
 * clears the dummy connection's explicit volume lock and uses the stock key
 * path. A source hold alone does not confirm a change to the volume.
 *
 * When. lsdextender.audio.keys = held (default): only while AudioSource holds a source
 * for a manual hold. Managed games do not set that flag. Always: on every volume key
 * (the HMI will step too when it has a source); off.
 *
 * Keys. Wheel: keyboard any, key 42 up / 43 down on press (state 1), configurable.
 * Knob: raw DSI encoder events on keyboard 17 / key 17 (KEY_VOLUME) with a
 * signed step count; the keys feature logs every event as "keys: ..." so the real ids
 * can be read off the feed and set here.
 *
 *   lsdextender.audio.keys           held | always | off
 *   lsdextender.audio.key_up         42
 *   lsdextender.audio.key_down       43
 *   lsdextender.audio.knob_keyboard  17
 *   lsdextender.audio.knob_key       17
 *   lsdextender.audio.volume_conn    2      (the connection id the HMI uses for the knob)
 *
 * Feed: audio hold <SRC> | audio release | audio status | audio vol up|down [n]
 */
public final class Audio implements MflListener, KeyListener, EncoderListener, CommandHandler {
    private static final int STEP_ARGUMENT = 1;

    private static final String P = " audio: ";
    private int steps;

    static {
        try {
            Audio a = new Audio();
            LsdExtender.register(a);
            LsdExtender.registerKeys(a);
            LsdExtender.registerEncoder(a);
            LsdExtender.registerCommands(a);
            HookInstaller.log("audio: key, knob and feed command handlers registered (keys="
                + Config.get("lsdextender.audio.keys", "held").trim() + ", up " + Config.getInt("lsdextender.audio.key_up", 42)
                + ", down " + Config.getInt("lsdextender.audio.key_down", 43) + ", knob "
                + Config.getInt("lsdextender.audio.knob_keyboard", 17) + "/" + Config.getInt("lsdextender.audio.knob_key", 17)
                + ", connection " + Config.getInt("lsdextender.audio.volume_conn", 2) + ")");
        } catch (Throwable t) {
            // fail open, with one line
            HookInstaller.log("audio: handlers not registered: " + t);
        }
    }

    private Audio() {
    }

    public String name() {
        return "audio [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    // ---- keys ------------------------------------------------------------------------

    private boolean active() {
        String mode = Config.get("lsdextender.audio.keys", "held").trim();
        return mode.equals("always") || (mode.equals("held") && AudioSource.held() != null);
    }

    public boolean onKey(int keyboard, int key, int state) {
        if (state != 1 || !active()) {
            return false;
        }
        if (key == Config.getInt("lsdextender.audio.key_up", 42)) {
            step(1, "key " + key);
        } else if (key == Config.getInt("lsdextender.audio.key_down", 43)) {
            step(-1, "key " + key);
        }
        return false;
    }

    public boolean onEncoder(int keyboard, int key, int inc) {
        if (inc == 0 || !active()) {
            return false;
        }
        if (keyboard == Config.getInt("lsdextender.audio.knob_keyboard", 17)
            && key == Config.getInt("lsdextender.audio.knob_key", 17)) {
            step(inc, "knob");
        }
        return false;
    }

    /** One DSISound volume step of |n| in the direction of n, the way the HMI does it. */
    private String step(int n, String why) {
        if (n == 0) {
            return "nothing to do";
        }
        try {
            DSISound sound = (DSISound) DSIProxyFactory.getDSIProxyAPI().getDSIProxy()
                .getService(LsdExtender.siTarget(), DSISound.class);
            int conn = Config.getInt("lsdextender.audio.volume_conn", 2);
            short s = (short) (n < 0 ? -n : n);
            if (n < 0) {
                sound.decreaseVolume(conn, 1, s);
            } else {
                sound.increaseVolume(conn, 1, s);
            }
            steps++;
            String line = P + "volume " + (n < 0 ? "-" : "+") + s + " on connection " + conn + " (" + why + ")";
            LsdExtender.trace(line);
            return line.trim();
        } catch (Throwable t) {
            String line = P + "volume step failed (" + why + "): " + t;
            LsdExtender.trace(line);
            return line.trim();
        }
    }

    // ---- feed commands ---------------------------------------------------------------

    public String command(String line) {
        if (!line.startsWith("audio")) {
            return null;
        }
        String rest = line.length() > 5 ? line.substring(5).trim() : "";
        if (rest.startsWith("hold ")) {
            String src = rest.substring(5).trim();
            AudioSource.hold(src);
            return "ok audio hold " + src + " requested (see the 'audio: source ->' lines)";
        }
        if (rest.equals("release")) {
            String was = AudioSource.held();
            AudioSource.release();
            return "ok audio release" + (was == null ? " (nothing was held)" : " of " + was);
        }
        if (rest.equals("status")) {
            String held = AudioSource.held();
            return "ok audio held=" + (held == null ? "none" : held) + " keys=" + Config.get("lsdextender.audio.keys", "held")
                + " active=" + active() + " steps=" + steps;
        }
        if (rest.startsWith("vol ")) {
            String[] a = split(rest.substring(4).trim());
            if (a.length == 0) {
                return "err usage: audio vol up|down [n]";
            }
            int n = 1;
            if (a.length > STEP_ARGUMENT) {
                try {
                    n = Integer.parseInt(a[STEP_ARGUMENT]);
                } catch (Throwable t) {
                    return "err usage: audio vol up|down [n]";
                }
            }
            if (a[0].equals("up")) {
                return "ok " + step(n, "feed");
            }
            if (a[0].equals("down")) {
                return "ok " + step(-n, "feed");
            }
            return "err usage: audio vol up|down [n]";
        }
        return "err usage: audio hold <SRC> | audio release | audio status | audio vol up|down [n]";
    }

    private static String[] split(String s) {
        java.util.Vector v = new java.util.Vector();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < s.length() && s.charAt(i) != ' ') {
                i++;
            }
            if (i > start) {
                v.addElement(s.substring(start, i));
            }
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
