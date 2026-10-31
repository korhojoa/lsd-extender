/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.genericevents;
public abstract class AbstractTarget implements de.vw.mib.genericevents.Target {
 public AbstractTarget() { super(); throw new UnsupportedOperationException(); }
 public de.vw.mib.genericevents.GenericEvents getMainObject() { throw new UnsupportedOperationException(); }
 public int getTargetId() { throw new UnsupportedOperationException(); }
 public void sendSafe(de.vw.mib.genericevents.EventGeneric arg0) { throw new UnsupportedOperationException(); }
 public boolean isTraceEnabled() { throw new UnsupportedOperationException(); }
 public de.vw.mib.log4mib.LogMessage trace() { throw new UnsupportedOperationException(); }
}
