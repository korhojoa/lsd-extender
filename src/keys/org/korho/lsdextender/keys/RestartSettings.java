/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import org.korho.lsdextender.Config;

/** Runtime admission remains false unless explicitly enabled. */
final class RestartSettings {
    private RestartSettings() { }
    static boolean enabled() { return Config.getBool("lsdextender.restart.enabled", false); }
    static boolean acceptsRaw(RestartPrompt.Owner owner, int keyboard, int key, int state) {
        if (enabled()) { return true; }
        if (keyboard == 4 && key == 39 && state == 1) { owner.cancel("restart-disabled"); }
        return false;
    }
    static boolean current(RestartWindow window, long ticket, long now) {
        return enabled() && window.current(ticket, now);
    }
    static boolean active(RestartWindow window, long ticket, long now) {
        return enabled() && window.active(ticket, now);
    }
}
