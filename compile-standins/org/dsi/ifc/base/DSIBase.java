/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package org.dsi.ifc.base;
public interface DSIBase {
 public void setNotification(int[] arg0, org.dsi.ifc.base.DSIListener arg1);
 public void clearNotification(int[] arg0, org.dsi.ifc.base.DSIListener arg1);
 public void clearNotification(org.dsi.ifc.base.DSIListener arg0);
}
