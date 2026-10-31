/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/**
 * A feature that wants hardkeys (steering wheel, panel, rotary) as the HMI receives
 * them from the key panel DSI service, independent of which source owns audio.
 * Delivered by the keys feature through LsdExtender.onKey().
 */
public interface KeyListener {
    /**
     * @param keyboard keyboard id (which device: wheel, panel, ...; observed at runtime)
     * @param key      key id (observed: 42/43 = wheel volume, 16 = rotary press)
     * @param state    1 press, 0 release, 2 double, 3/4/5 long, longer, ultra long
     * @return true to stop delivery to later listeners (the HMI still gets the key)
     */
    boolean onKey(int keyboard, int key, int state);
}
