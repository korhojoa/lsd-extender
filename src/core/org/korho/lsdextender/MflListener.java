/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;

/**
 * A feature that wants steering-wheel (MFL) gestures. Register from the feature
 * class's static initializer via LsdExtender.register(); core loads the known
 * feature classes by name after boot and silently skips the ones whose jar is not
 * on the card.
 */
public interface MflListener {
    /** Short name plus the jar's build stamp, for the log. */
    String name();

    /**
     * @param target  lsd's smartphone-integration target (bound DSI proxies, tracing)
     * @param gesture native steering-wheel gesture code supplied by the event
     * @return legacy result, ignored; this observer cannot consume stock input
     */
    boolean onMflEvent(HsmTarget target, int gesture);
}
