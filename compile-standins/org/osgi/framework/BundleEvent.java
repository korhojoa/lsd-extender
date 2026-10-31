/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package org.osgi.framework;
public class BundleEvent extends java.util.EventObject {
 public static int INSTALLED = 0;
 public BundleEvent(int arg0, org.osgi.framework.Bundle arg1) { super((java.lang.Object) null); throw new UnsupportedOperationException(); }
 public org.osgi.framework.Bundle getBundle() { throw new UnsupportedOperationException(); }
 public int getType() { throw new UnsupportedOperationException(); }
}
