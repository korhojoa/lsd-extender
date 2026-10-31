/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.widgets.models;
public interface ContainerModel extends de.vw.mib.widgets.models.WidgetModel, de.vw.mib.widgets.layout.Layoutable, de.vw.mib.widgets.animation.Animatable {
 public de.vw.mib.widgets.models.WidgetModel[] getChildren();
}
