/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.desktop.internal.common;
public class ServiceManager {
 public static de.vw.mib.desktop.internal.DesktopManagerImpl desktopManager = null;
 public static de.vw.mib.desktop.internal.popup.PopupStackManager popupStackManager = null;
 public static de.vw.mib.event.dispatcher.FrameworkEventDispatcher eventDispatcher = null;
 public static de.vw.mib.configuration.ConfigurationManager configurationManager = null;
 public static de.vw.mib.skin.SkinClassLoader skinClassLoader = null;
 public static de.vw.mib.desktop.internal.screenarea.ScreenTree screenAreaTree = null;
 private ServiceManager() { super(); throw new UnsupportedOperationException(); }
}
