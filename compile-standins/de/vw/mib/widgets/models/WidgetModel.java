/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.widgets.models;
public interface WidgetModel {
 public java.lang.String get_name();
 public void set_name(java.lang.String arg0);
 public boolean is_activated();
 public boolean is_enabled();
 public boolean is_availableInVariant();
 public boolean is_visible();
 public void set_visible(boolean arg0);
 public void set_x(int arg0);
 public void set_y(int arg0);
 public int get_width();
 public void set_width(int arg0);
 public int get_height();
 public void set_height(int arg0);
 public int get_alpha();
 public de.vw.mib.widgets.models.WidgetModel getParent();
 public int getAbsX();
 public int getAbsY();
 public boolean isPaintable();
 public void invalidate();
 public void repaint();
}
