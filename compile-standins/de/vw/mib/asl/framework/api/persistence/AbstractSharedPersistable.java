/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.framework.api.persistence;
public abstract class AbstractSharedPersistable implements de.vw.mib.asl.framework.api.persistence.SharedPersistable {
 public AbstractSharedPersistable(int arg0, long arg1) { super(); throw new UnsupportedOperationException(); }
 public long getKey() { throw new UnsupportedOperationException(); }
 public int getNamespace() { throw new UnsupportedOperationException(); }
 public void markDirty(boolean arg0) { throw new UnsupportedOperationException(); }
 public void fromStream(de.vw.mib.asl.framework.api.persistence.PersistenceInputStream arg0) throws java.io.IOException { throw new UnsupportedOperationException(); }
}
