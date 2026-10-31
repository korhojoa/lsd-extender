/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

/** A current restart requirement grants one explicit touch, never a poll action. */
final class UpdaterRestart {
    private final NativeRestartAction action;
    private boolean offered, requested;

    UpdaterRestart(NativeRestartAction action) {
        if (action == null) { throw new IllegalArgumentException("restart action required"); }
        this.action = action;
    }
    static boolean required(boolean busy, String action) {
        return !busy && "restart".equals(action);
    }
    boolean refresh(boolean busy, String stateAction) {
        boolean next = required(busy, stateAction) && !requested;
        boolean changed = offered != next;
        offered = next;
        return changed;
    }
    boolean visible() { return offered; }
    boolean userNow(boolean busy, String stateAction) {
        if (!offered || requested || !required(busy, stateAction)) { return false; }
        requested = true; offered = false;
        action.userNow();
        return true;
    }
}
