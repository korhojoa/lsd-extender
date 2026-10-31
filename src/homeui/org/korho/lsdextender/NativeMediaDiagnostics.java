/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import org.dsi.ifc.media.AudioRoute;

/** Diagnostic details for native media acquisition. */
final class NativeMediaDiagnostics {
    private NativeMediaDiagnostics() { }

    /** The first stack frames, so the next occurrence names the throwing line. */
    static String frames(Throwable failure) {
        StackTraceElement[] trace = failure.getStackTrace();
        StringBuffer text = new StringBuffer();
        for (int i = 0; trace != null && i < trace.length && i < 8; i++) {
            if (i > 0) { text.append(" <- "); }
            text.append(trace[i]);
        }
        return text.toString();
    }

    static String routes(AudioRoute[] list) {
        if (list == null) { return "null"; }
        StringBuffer text = new StringBuffer("[");
        for (int i = 0; i < list.length; i++) {
            if (i > 0) { text.append(' '); }
            text.append(list[i].routingInput).append("->").append(list[i].routingOutput)
                .append('/').append(list[i].routeStatus);
        }
        return text.append(']').toString();
    }

    static boolean hasMediaRoute(AudioRoute[] routes, int validity) {
        if (validity != 1 || routes == null) { return false; }
        for (int i = 0; i < routes.length; i++) {
            if (routes[i].routingInput == 1 && routes[i].routingOutput == 1 && routes[i].routeStatus == 0) {
                return true;
            }
        }
        return false;
    }
}
