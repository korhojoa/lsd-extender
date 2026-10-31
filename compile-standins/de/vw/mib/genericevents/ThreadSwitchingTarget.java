/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.genericevents;
public interface ThreadSwitchingTarget extends de.vw.mib.genericevents.Target, de.vw.mib.threads.MIBInvoker, de.vw.mib.timer.Invoker {
 public void enqueue(java.lang.Runnable arg0);
}
