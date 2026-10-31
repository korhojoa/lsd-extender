/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.api.media.services.ResultCode;
import de.vw.mib.asl.api.media.services.SourceCallback;
import de.vw.mib.asl.api.media.services.sources.SourceId;
import de.vw.mib.asl.api.media.services.sources.medialist.Source;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.media.accessor.MediaObjectAccessor;

public final class AudioSource {
    private static String heldSource;

    private AudioSource() {
    }

    /** The source currently held for a native program, or null. */
    public static synchronized String held() {
        return heldSource;
    }

    /** Select src as the entertainment source and remember it. */
    public static void hold(String src) {
        if (src == null || src.trim().length() == 0) {
            return;
        }
        src = src.trim();
        synchronized (AudioSource.class) {
            heldSource = src;
        }
        switchTo(src, "hold for a native program");
    }

    /** Put the release source back, if a hold is active. */
    public static void release() {
        String was;
        synchronized (AudioSource.class) {
            was = heldSource;
            heldSource = null;
        }
        if (was == null) {
            return;
        }
        String back = Config.get("lsdextender.audio.release_source", "HDD").trim();
        if (back.length() == 0) {
            Slog.write(" audio: hold of " + was + " released, no release source configured, leaving it");
            return;
        }
        switchTo(back, "release of " + was);
    }

    /** Source name (as audioctl lists them) to SourceId device type, -1 when unknown. */
    static int deviceType(String name) {
        String n = name.toUpperCase();
        if (n.equals("HDD") || n.equals("JUKEBOX")) {
            return (0);
        }
        if (n.equals("SD") || n.equals("SDCARD")) {
            return (1);
        }
        if (n.equals("USB")) {
            return (2);
        }
        if (n.equals("AUX")) {
            return (8);
        }
        if (n.equals("WLAN")) {
            return (9);
        }
        if (n.equals("BT") || n.equals("BTAUDIO") || n.equals("BLUETOOTH")) {
            return (10);
        }
        if (n.equals("FILEPLAYER")) {
            return (12);
        }
        if (n.equals("CD") || n.equals("CDINT")) {
            return (4);
        }
        if (n.equals("DVD") || n.equals("DVDINT")) {
            return (3);
        }
        try {
            return Integer.parseInt(n);
        } catch (Throwable notANumber) {
            return -1;
        }
    }

    private static void switchTo(final String src, final String why) {
        String mode = Config.get("lsdextender.audio.hold_mode", "auto").trim();
        if (!mode.equals("cmd")) {
            String r = switchInProcess(src, why);
            if (r == null) {
                return;
            }
            Slog.write(" audio: in-process switch to " + src + " not possible (" + r + ")"
                + (mode.equals("inprocess") ? ", giving up" : ", using the command"));
            if (mode.equals("inprocess")) {
                return;
            }
        }
        String cmd = Config.get("lsdextender.audio.hold_cmd", "sh /fs/sda0/headunit/run-audioctl.sh hold").trim()
            + " " + src;
        Slog.write(" audio: source -> " + src + " (" + why + "): " + cmd);
        Slog.write(" audio:" + Spawn.run(cmd));
    }

    /** null when the request was queued, else the reason it could not be. */
    private static String switchInProcess(final String src, final String why) {
        final int type = deviceType(src);
        if (type < 0) {
            return "unknown source name " + src;
        }
        try {
            if (MediaObjectAccessor.mMediaController == null) {
                return "media controller not up yet";
            }
            if (ServiceManager.aslThreadSwitch == null) {
                return "no ASL thread switch yet";
            }
            Runnable job = new Runnable() {
                public void run() {
                    try {
                        SourceCallback cb = new SourceCallback() {
                            public void response(ResultCode result, Source source) {
                                Slog.write(" audio: source -> " + src + " (" + why + "): activateMediaSource result "
                                    + result + (source != null ? " " + source : ""));
                            }
                        };
                        MediaObjectAccessor.mMediaController.activateMediaSource(new SourceId(type, 0), cb);
                    } catch (Throwable t) {
                        Slog.write(" audio: source -> " + src + " (" + why + "): activateMediaSource failed: " + t);
                    }
                }
            };
            ServiceManager.aslThreadSwitch.enqueue(job);
            Slog.write(" audio: source -> " + src + " (" + why + "): activateMediaSource(" + type + ", 0) queued on the ASL thread");
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }
}
