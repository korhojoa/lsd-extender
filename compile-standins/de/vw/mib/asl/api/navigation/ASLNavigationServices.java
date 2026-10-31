/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.api.navigation;
public interface ASLNavigationServices {
 public boolean isServiceAvailable();
 public int startGuidance(de.vw.mib.asl.api.navigation.NavAddress[] arg0, boolean arg1, int arg2);
 public de.vw.mib.asl.api.navigation.GuidanceInfo[] getGuidanceInfo();
}
