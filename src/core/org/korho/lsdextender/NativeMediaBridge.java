/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.api.media.services.sources.current.CurrentMediaSourceListener;
import de.vw.mib.asl.api.media.services.sources.current.CurrentMediaSourceService;
import de.vw.mib.asl.api.media.services.sources.medialist.Source;

/** Stable source listener and session data; media policy belongs to the runtime. */
public final class NativeMediaBridge {
    public interface Service {
        void sourceChanged(Source source);
        void release(NativeMediaState state);
    }

    public static volatile NativeMediaState state;
    public static volatile Source currentSource;
    public static volatile boolean sourceKnown;
    private static volatile Service service;
    private static boolean observing;

    private NativeMediaBridge() { }

    public static void setService(Service value) { service = value; }

    /** The stock API has no unregister operation. Register this forwarding object once. */
    public static void observe(CurrentMediaSourceService sources) {
        if (observing) { return; }
        sources.registerListener(new CurrentMediaSourceListener() {
            public void updateCurrentMediaSource(Source source) {
                currentSource = source; sourceKnown = true;
                Service target = service;
                if (target != null) { target.sourceChanged(source); }
            }
        });
        observing = true;
    }

    public static void release() {
        NativeMediaState current = state;
        if (current == null) { return; }
        current.closed = true;
        Service target = service;
        if (target != null) { target.release(current); }
    }

    public static boolean changing() {
        NativeMediaState current = state;
        return current != null && (!current.ready || current.closed);
    }
}
