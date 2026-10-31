/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import de.vw.mib.genericevents.EventGeneric;

/**
 * A consumer of a published ASL service. Core registers an independent target,
 * then delivers its event alongside native observers. This cannot intercept
 * private SI events or suppress stock delivery. Callbacks must return quickly.
 */
public interface AslEventListener {
    /** The receiver event id this listener wants. */
    int aslEventId();

    void onAslEvent(HsmTarget target, EventGeneric event);
}
