/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.widgets;
public abstract class AbstractWidget implements de.vw.mib.widgets.models.WidgetModel, de.vw.mib.pool.PooledObject {
 public AbstractWidget() { super(); throw new UnsupportedOperationException(); }
 public int get_targetId() { throw new UnsupportedOperationException(); }
 public void set_targetId(int arg0) { throw new UnsupportedOperationException(); }
 public java.lang.String get_name() { throw new UnsupportedOperationException(); }
 public void set_name(java.lang.String arg0) { throw new UnsupportedOperationException(); }
 public boolean is_activated() { throw new UnsupportedOperationException(); }
 public void set_activated(boolean arg0) { throw new UnsupportedOperationException(); }
 public boolean is_enabled() { throw new UnsupportedOperationException(); }
 public void set_enabled(boolean arg0) { throw new UnsupportedOperationException(); }
 public boolean is_availableInVariant() { throw new UnsupportedOperationException(); }
 public void set_availableInVariant(boolean arg0) { throw new UnsupportedOperationException(); }
 public boolean is_visible() { throw new UnsupportedOperationException(); }
 public void set_visible(boolean arg0) { throw new UnsupportedOperationException(); }
 public void set_layoutable(boolean arg0) { throw new UnsupportedOperationException(); }
 public int get_x() { throw new UnsupportedOperationException(); }
 public void set_x(int arg0) { throw new UnsupportedOperationException(); }
 public int get_y() { throw new UnsupportedOperationException(); }
 public void set_y(int arg0) { throw new UnsupportedOperationException(); }
 public int get_width() { throw new UnsupportedOperationException(); }
 public void set_width(int arg0) { throw new UnsupportedOperationException(); }
 public int get_height() { throw new UnsupportedOperationException(); }
 public void set_height(int arg0) { throw new UnsupportedOperationException(); }
 public int get_alpha() { throw new UnsupportedOperationException(); }
 public void set_alpha(int arg0) { throw new UnsupportedOperationException(); }
 public void set_easing(de.vw.mib.animation.easing.EasingParams[] arg0) { throw new UnsupportedOperationException(); }
 public void set_touchInsets(de.vw.mib.graphics.Insets arg0) { throw new UnsupportedOperationException(); }
 public de.vw.mib.widgets.controller.WidgetController getController() { throw new UnsupportedOperationException(); }
 public void setController(de.vw.mib.widgets.controller.WidgetController arg0) { throw new UnsupportedOperationException(); }
 public void setUI(de.vw.mib.widgets.ui.WidgetUI arg0) { throw new UnsupportedOperationException(); }
 public de.vw.mib.widgets.models.WidgetModel getParent() { throw new UnsupportedOperationException(); }
 public de.vw.mib.widgets.models.ViewModel getView() { throw new UnsupportedOperationException(); }
 public int getAbsX() { throw new UnsupportedOperationException(); }
 public int getAbsY() { throw new UnsupportedOperationException(); }
 public boolean areParentsVisible() { throw new UnsupportedOperationException(); }
 public void invalidate() { throw new UnsupportedOperationException(); }
 public boolean isPaintable() { throw new UnsupportedOperationException(); }
 public void repaint() { throw new UnsupportedOperationException(); }
 public boolean consumeHMIEvent(de.vw.mib.event.HMIEvent arg0, java.lang.String arg1) { throw new UnsupportedOperationException(); }
 public boolean consumeTouchEvent(de.vw.mib.event.TouchEvent arg0) { throw new UnsupportedOperationException(); }
}
