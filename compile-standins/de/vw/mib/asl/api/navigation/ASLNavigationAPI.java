/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.api.navigation;
public interface ASLNavigationAPI {
 public de.vw.mib.asl.api.navigation.ASLNavigationServices getASLNavigationServices(de.vw.mib.asl.api.navigation.ASLNavigationServicesListener arg0);
 public de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceService getNavigationGuidanceService(de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceListener arg0) throws de.vw.mib.asl.api.navigation.NavigationServiceException;
}
