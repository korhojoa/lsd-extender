/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.hmi.internal;
public class ServiceManagerCommon {
 public static de.vw.mib.datapool.I18nDatapool i18nDatapool = null;
 public static de.vw.mib.skin.SkinAnimationPool skinAnimationPool = null;
 public static de.vw.mib.skin.SkinImagePool skinImagePool = null;
 private ServiceManagerCommon() { super(); throw new UnsupportedOperationException(); }
}
