/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.event;
public interface TouchEvent extends de.vw.mib.event.Event {
 public static int PRESS = (int) Integer.parseInt("0");
 public static int CANCEL = (int) Integer.parseInt("0");
 public int getTimestamp();
 public int getType();
 public int getX();
 public int getY();
}
