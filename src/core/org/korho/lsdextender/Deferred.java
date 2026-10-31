/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/**
 * Work that could not be done yet (some stock object did not exist at the time).
 * Registered with LsdExtender.defer(); core runs it on the ASL thread.
 * False results receive bounded native-timer retries without needing another
 * published event. New registrations and reloads queue prompt passes. Work ends
 * on success, after 300 attempts, or after its 30-second readiness deadline.
 */
public interface Deferred {
    /** @return true when done (or given up); false to be called again later */
    boolean run();

    /** Short name for the log. */
    String name();
}
