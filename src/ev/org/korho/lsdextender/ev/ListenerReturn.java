/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.ev;

/** The value a DSI listener proxy returns from a callback: false, zero or null by type. */
final class ListenerReturn {
    private ListenerReturn() {
    }

    static Object empty(Class r) {
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
}
