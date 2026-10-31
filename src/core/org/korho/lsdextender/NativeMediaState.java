/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.api.media.services.sources.medialist.Source;

/** Data retained while the runtime media implementation is replaced. */
public final class NativeMediaState {
    public Source previousSource;
    public int previousContext;
    public boolean switched, selectionComplete, granted, faded, unlocked;
    public volatile boolean ready, closed;
    public String error;
}
