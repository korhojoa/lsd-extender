/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.internal.homescreen;
public interface HomeScreenServices {
 public de.vw.mib.asl.internal.impl.homescreen.model.Tile[] getTiles();
 public de.vw.mib.asl.internal.impl.homescreen.tileselection.TileInstaller getTileInstaller();
}
