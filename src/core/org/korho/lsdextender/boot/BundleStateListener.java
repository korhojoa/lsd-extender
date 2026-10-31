/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.boot;

import org.osgi.framework.Bundle;

/**
 * An owned consumer of post-activator ACTIVE and STOPPED lifecycle updates.
 * Registration includes an idempotent already-ACTIVE sweep. STARTING is not
 * available; callbacks must return quickly and failures are contained.
 */
public interface BundleStateListener {
    void bundleStateChanged(int state, Bundle bundle);
}
