/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/**
 * A feature that wants rotary encoder steps (volume knob, tuning knob) as the HMI
 * receives them from the key panel DSI service. Delivered by the keys feature through
 * LsdExtender.onEncoder(). The HMI still gets the step.
 */
public interface EncoderListener {
    /**
     * @param keyboard keyboard id (17 = volume control per org.dsi.ifc.keypanel.Constants)
     * @param key      key id (13 = KEY_VOL)
     * @param inc      signed step count
     * @return true to stop delivery to later listeners
     */
    boolean onEncoder(int keyboard, int key, int inc);
}
