/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package org.osgi.framework;
public interface BundleContext {
 public org.osgi.framework.Bundle[] getBundles();
 public void addBundleListener(org.osgi.framework.BundleListener arg0);
 public void removeBundleListener(org.osgi.framework.BundleListener arg0);
 public org.osgi.framework.ServiceReference getServiceReference(java.lang.String arg0);
 public java.lang.Object getService(org.osgi.framework.ServiceReference arg0);
 public boolean ungetService(org.osgi.framework.ServiceReference arg0);
}
