/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.widgets.internal;
public class ServiceManager {
 public static de.vw.mib.configuration.ConfigurationManager configurationManager = null;
 public static de.vw.mib.graphics.font.FontManager fontManager = null;
 public static de.vw.mib.timer.TimerManager timerManager = null;
 public static org.osgi.framework.BundleContext bundleContext = null;
 private ServiceManager() { super(); throw new UnsupportedOperationException(); }
}
