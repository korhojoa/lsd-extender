/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.Spawn;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;

/** Tracks the authorized phone and starts configured wireless connections. */
public final class WirelessDevices {
    private static String phoneName;
    private static String phoneAddress;
    private static boolean autoConnected;

    private WirelessDevices() {
    }

    /** Starts configured autoconnect once for each authorized phone change. */
    static synchronized void setPhone(String name, String address) {
        boolean changed = !eq(name, phoneName) || !eq(address, phoneAddress);
        phoneName = name;
        phoneAddress = address;
        if (changed) {
            LsdExtender.trace(" wireless: phone present: " + name + " " + address);
            autoConnected = false;
            autoConnectIfConfigured();
        }
    }

    static synchronized void clearPhone() {
        if (phoneName != null || phoneAddress != null) {
            LsdExtender.trace(" wireless: phone gone: " + phoneName);
            phoneName = null;
            phoneAddress = null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static void connect(HsmTarget target, int method, String why) {
        if (!WirelessSettingsService.canConnect()) {
            LsdExtender.trace(" wireless: connect denied by settings");
            return;
        }
        triggerAawg();
    }

    /**
     * On a connect intent, poke the native Bluetooth bootstrap to run
     * the wireless handshake now, by touching its on-demand trigger file
     * (default {@code /tmp/aawg_nudge}). Run through Spawn, never java.io.File:
     * the J9 runtime's file APIs are unreliable. An empty command disables it.
     */
    private static void triggerAawg() {
        String cmd = Config.get("lsdextender.wireless.aawg_nudge_cmd", "touch /tmp/aawg_nudge");
        if (cmd == null || cmd.trim().length() == 0) {
            return;
        }
        try {
            LsdExtender.trace(" wireless: AAWG nudge on connect intent ->" + Spawn.run(cmd.trim()));
        } catch (Throwable t) {
            LsdExtender.trace(" wireless: AAWG nudge failed: " + t);
        }
    }

    /** Auto-connect once when the authorized phone and settings permit it. */
    private static synchronized void autoConnectIfConfigured() {
        if (phoneName != null && !autoConnected && Config.getBool("lsdextender.wireless.autoconnect", false)) {
            HsmTarget t = LsdExtender.siTarget();
            if (t != null) {
                autoConnected = true;
                try {
                    connect(t, 8, "autoconnect");
                } catch (Throwable e) {
                    LsdExtender.trace(" wireless: autoconnect failed: " + e);
                }
            }
        }
    }
}
