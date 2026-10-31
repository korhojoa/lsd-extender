/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.skin;
public interface SkinImagePool {
 public static int NONE = (int) Integer.parseInt("0");
 public de.vw.mib.graphics.image.Image getImage(int arg0);
 public de.vw.mib.graphics.image.Image[] getImageArray(int arg0);
}
