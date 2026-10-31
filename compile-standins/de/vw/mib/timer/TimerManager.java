/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.timer;
public interface TimerManager {
 public de.vw.mib.timer.Timer createTimer(java.lang.String arg0, long arg1, boolean arg2, java.lang.Runnable arg3, de.vw.mib.timer.Invoker arg4);
 public long getSystemTimeMillis();
}
