/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.framework.internal.framework;
public class ServiceManager {
 public static de.vw.mib.timer.TimerManager timerManager = null;
 public static de.vw.mib.genericevents.GenericEvents eventMain = null;
 public static de.vw.mib.genericevents.EventFactory mGenericEventFactory = null;
 public static de.vw.mib.asl.framework.internal.framework.reflection.ContainerReflectionFactory reflectionFactory = null;
 public static de.vw.mib.asl.ASLPropertyManager aslPropertyManager = null;
 public static org.osgi.framework.BundleContext bundleContext = null;
 public static de.vw.mib.genericevents.ThreadSwitchingTarget aslThreadSwitch = null;
 private ServiceManager() { super(); throw new UnsupportedOperationException(); }
}
