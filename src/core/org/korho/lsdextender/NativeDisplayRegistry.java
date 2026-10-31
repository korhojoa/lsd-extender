/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.genericevents.Target;
import java.util.Hashtable;

/** Ownership data for native contexts already declared to the stock display target. */
public final class NativeDisplayRegistry {
    public static Target target;
    public static final Hashtable registered = new Hashtable();
    private NativeDisplayRegistry() { }
}
