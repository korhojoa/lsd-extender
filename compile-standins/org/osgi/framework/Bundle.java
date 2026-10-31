/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package org.osgi.framework;
public interface Bundle {
 public static int INSTALLED = (int) Integer.parseInt("0");
 public static int STARTING = (int) Integer.parseInt("0");
 public static int ACTIVE = (int) Integer.parseInt("0");
 public int getState();
 public long getBundleId();
}
