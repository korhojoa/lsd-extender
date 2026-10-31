/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.framework.api.dsiproxy;
public interface DSIProxy {
 public org.dsi.ifc.base.DSIBase getService(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1);
 public org.dsi.ifc.base.DSIBase getService(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1, int arg2);
 public void addResponseListener(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1, org.dsi.ifc.base.DSIListener arg2);
 public void addResponseListener(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1, int arg2, org.dsi.ifc.base.DSIListener arg3);
 public void removeResponseListener(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1, org.dsi.ifc.base.DSIListener arg2);
 public void removeResponseListener(de.vw.mib.genericevents.Target arg0, java.lang.Class arg1, int arg2, org.dsi.ifc.base.DSIListener arg3);
 public void addServiceStateListener(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener arg1);
 public void addServiceStateListener(java.lang.Class arg0, int arg1, de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener arg2);
 public void removeServiceStateListener(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener arg1);
 public void removeServiceStateListener(java.lang.Class arg0, int arg1, de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener arg2);
 public void addServiceInterceptor(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg1);
 public void removeServiceInterceptor(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg1);
 public void addListenerInterceptor(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg1);
 public void addListenerInterceptor(java.lang.Class arg0, int arg1, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg2);
 public void removeListenerInterceptor(java.lang.Class arg0, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg1);
 public void removeListenerInterceptor(java.lang.Class arg0, int arg1, de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor arg2);
}
