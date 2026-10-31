/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.api.navbap;
public interface ASLNavBAPAPI {
 public void updateBapDistanceToNextManeuver(int arg0, int arg1, boolean arg2, int arg3);
 public void updateBapTurnToInfo(java.lang.String arg0, java.lang.String arg1);
 public void updateBapLaneGuidance(de.vw.mib.asl.api.navbap.INavLaneGuidanceDataNavBap[] arg0, boolean arg1);
}
