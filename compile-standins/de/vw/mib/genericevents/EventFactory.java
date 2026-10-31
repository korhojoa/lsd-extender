/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.genericevents;
public interface EventFactory {
 public de.vw.mib.genericevents.EventGeneric newEvent(int arg0);
 public de.vw.mib.genericevents.EventGeneric newEvent(int arg0, int arg1, int arg2);
}
