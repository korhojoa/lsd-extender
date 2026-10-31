/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.internal.media.controller.commands;
public abstract class AbstractCommand implements de.vw.mib.asl.internal.media.controller.commands.Command {
 public de.vw.mib.asl.internal.media.common.ResponseHandler NEXT_STEP = null;
 public AbstractCommand(int arg0, int arg1, int arg2, int arg3) { super(); throw new UnsupportedOperationException(); }
 public void setCommandCallback(de.vw.mib.asl.internal.media.controller.commands.CommandCallback arg0) { throw new UnsupportedOperationException(); }
 public boolean isCompleted() { throw new UnsupportedOperationException(); }
 public boolean isTerminated() { throw new UnsupportedOperationException(); }
 public void abort(java.lang.String arg0) { throw new UnsupportedOperationException(); }
}
