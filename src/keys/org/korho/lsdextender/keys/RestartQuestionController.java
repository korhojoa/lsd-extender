/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.event.TouchEvent;
import de.vw.mib.widgets.controller.DefaultViewController;

/** Touch authority on our own widget, the actual desktop dispatch route. */
class RestartQuestionController extends DefaultViewController {
    interface Authority {
        boolean touchAllowed();
        int hit(int x, int y);
        void touched(int activation);
    }
    private final Authority authority;
    private final RestartTouch touch = new RestartTouch();
    RestartQuestionController(Authority source) { authority = source; }
    final void resetTouch() { touch.reset(); }
    public final boolean consumeTouchEvent(TouchEvent event) {
        if (!authority.touchAllowed()) { touch.reset(); return false; }
        // Delegation can transform the event coordinates. Capture first.
        int activation = touch.accept(event.getType(), authority.hit(event.getX(), event.getY()), event.getTimestamp());
        boolean consumed = forwardTouch(event);
        // Native callbacks can retire or cover this question during delegation.
        if (!authority.touchAllowed()) { touch.reset(); return consumed; }
        if (activation != RestartTouch.NONE) { authority.touched(activation); }
        return consumed || activation != RestartTouch.NONE;
    }
    protected boolean forwardTouch(TouchEvent event) { return super.consumeTouchEvent(event); }
}
