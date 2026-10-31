/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.event.TouchEvent;

/** Fresh touch authority. Native button output events are deliberately not used. */
final class RestartTouch {
    static final int NONE = 0, RESTART = 1, CANCEL = 2;
    private int pressed;
    void reset() { pressed = NONE; }
    int accept(int type, int hit, int time) {
        if (type == (1)) { pressed = hit; }
        else if (type == (3) && hit != pressed) { reset(); }
        else if (type != (0) && type != (2) && type != (3)) { reset(); }
        else if (type == (0) || type == (2)) {
            int candidate = pressed; reset();
            // The panel supplies per-event durations, not a monotonic timestamp.
            return candidate != NONE && hit == candidate ? candidate : NONE;
        }
        return NONE;
    }
}
