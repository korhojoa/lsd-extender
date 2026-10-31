/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.genericevents;
public interface EventDispatcherHSM extends de.vw.mib.genericevents.EventSender {
 public de.vw.mib.genericevents.Target getTarget(int arg0);
 public de.vw.mib.genericevents.TaskInterface getTask(java.lang.String arg0);
 public java.lang.String getTaskId(int arg0);
 public int registerTarget(de.vw.mib.genericevents.Target arg0, java.lang.String arg1) throws java.lang.Exception;
 public void unregisterTarget(int arg0) throws java.lang.Exception;
}
