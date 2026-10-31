/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.widgets.models;
public interface ListItemModel extends de.vw.mib.widgets.models.TransformContainerModel, de.vw.mib.widgets.interfaces.WidgetCloneable, de.vw.mib.list.ListItemControlListener {
 public int getAbsoluteIndex();
 public boolean isAssigned();
}
