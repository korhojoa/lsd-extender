/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.dsispy;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.framework.internal.framework.dsi.util.Utils;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import de.vw.mib.reflection.MultipleMethodAccessibleObject;
import java.lang.reflect.Method;
import java.util.Hashtable;

/**
 * Generic DSI call spy. Installs the HMI proxy API's interceptors on the DSI
 * service interfaces (outgoing calls the HMI makes, e.g. DSIDisplayManagement
 * .switchContext) and listener interfaces (incoming updates/responses) named in
 * the config, and writes one slog line per call with the method name resolved
 * and the arguments printed. Calls are never blocked (before() returns true).
 *
 *   lsdextender.dsispy.services=org.dsi.ifc.displaymanagement.DSIDisplayManagement,...
 *   lsdextender.dsispy.listeners=org.dsi.ifc.displaymanagement.DSIDisplayManagementListener,...
 *   lsdextender.dsispy.enabled=true
 *
 * Output: "lsd-extender dsispy: > DSIDisplayManagement.switchContext(12, 0, 0)" for
 * service calls, {@code < DSIDisplayManagementListener.updateXxx(...)} for listener calls.
 */
public final class DsiSpy implements MflListener, Deferred, DSIProxyInterceptor, org.korho.lsdextender.CommandHandler {
    private static final String SERVICE_KIND = "service";
    private static final String DM_LISTENER = "org.dsi.ifc.displaymanagement.DSIDisplayManagementListener";
    private static final int MAX_ARRAY_ITEMS = 16, MAX_OBJECT_CHARS = 16000;

    private static final int MAX_ATTEMPTS = 300;

    private boolean installed;
    private int attempts;
    /** interface class name -> Hashtable(Integer index -> String method name) */
    private final Hashtable names = new Hashtable();
    /** interface class name -> "service" | "listener" */
    private final Hashtable kinds = new Hashtable();
    private static final ClusterObserver CLUSTER = createObserver();

    private static ClusterObserver createObserver() {
        try { return new ClusterObserver(); }
        catch (Throwable unavailable) {
            LsdExtender.trace(" cluster-observer wall_ms=" + System.currentTimeMillis()
                + " unavailable; original spy preserved: " + unavailable);
            return null;
        }
    }

    static {
        try {
            DsiSpy s = new DsiSpy();
            LsdExtender.register(s);
            LsdExtender.defer(s);
            LsdExtender.registerCommands(s);
            LsdExtender.registerCommands(new DisplayableCommands());
        } catch (Throwable ignored) {
            // fail open
        }
    }

    /**
     * Live commands over the TCP feed (no rebuild, no reboot):
     *   {@code subscribe <Service>:<attr,attr>} dynamic listener, logs every update ("=")
     *   {@code unsubscribe <Service>:<attr,attr>} clearNotification for that listener
     *   {@code intercept service <Service>} log every call the HMI makes on it (">")
     *   {@code intercept listener <Listener>} log every update the HMI receives on it ("&lt;")
     */
    public String command(String line) {
        try {
            if (line.startsWith("subscribe ")) {
                return subscribe(line.substring(10).trim(), false);
            }
            if (line.startsWith("unsubscribe ")) {
                return unsubscribe(line.substring(12).trim());
            }
            if (line.startsWith("intercept service ")) {
                Class c = Class.forName(line.substring(18).trim());
                DSIProxyFactory.getDSIProxyAPI().getDSIProxy().addServiceInterceptor(c, this);
                kinds.put(c.getName(), "service");
                return "ok service interceptor on " + c.getName();
            }
            if (line.startsWith("spy filter")) {
                String spec = line.length() > 10 ? line.substring(10).trim() : "";
                if (spec.equals("off")) {
                    spec = "";
                }
                Config.override("lsdextender.dsispy.filter", spec);
                return "ok spy filter " + (spec.length() == 0 ? "off (everything logged)" : "= " + spec);
            }
            if (line.startsWith("intercept listener ")) {
                Class c = Class.forName(line.substring(19).trim());
                DSIProxyFactory.getDSIProxyAPI().getDSIProxy().addListenerInterceptor(c, this);
                kinds.put(c.getName(), "listener");
                return "ok listener interceptor on " + c.getName();
            }
            return null;
        } catch (Throwable t) {
            return "err " + t;
        }
    }

    /** service class name -> dynamic listener, so unsubscribe can find it */
    private final Hashtable dynListeners = new Hashtable();

    /**
     * @param atBoot true for the config-file subscriptions: issued from the service's
     *               registration callback (DsiSubscribe), because a setNotification
     *               made before the service is up is silently lost. Live commands
     *               subscribe directly, so "unsubscribe" can undo exactly what they did.
     */
    private String subscribe(String spec, boolean atBoot) throws Exception {
        int colon = spec.indexOf(':');
        if (colon <= 0) {
            return "err usage: subscribe <ServiceClass>:<attr,attr>";
        }
        String svcName = spec.substring(0, colon).trim();
        int[] attrs = parseInts(spec.substring(colon + 1));
        Class svc = Class.forName(svcName);
        Object listener = dynListeners.get(svc.getName());
        if (listener == null) {
            Class lst = Class.forName(svcName + "Listener");
            listener = java.lang.reflect.Proxy.newProxyInstance(
                lst.getClassLoader(), new Class[]{lst}, new Logger(svc.getName()));
            dynListeners.put(svc.getName(), listener);
        }
        if (atBoot) {
            DsiSubscribe.whenRegistered("dsispy", svc, attrs, (org.dsi.ifc.base.DSIListener) listener);
            return "ok armed " + svc.getName() + " attrs " + joinInts(attrs);
        }
        org.dsi.ifc.base.DSIBase service = DSIProxyFactory.getDSIProxyAPI().getDSIProxy()
            .getService(LsdExtender.siTarget(), svc);
        service.setNotification(attrs, (org.dsi.ifc.base.DSIListener) listener);
        return "ok subscribed " + svc.getName() + " attrs " + joinInts(attrs);
    }

    /**
     * Reload contract (core calls this reflectively before dropping the registration):
     * take the interceptors off the proxy and clear the live dynamic listeners; the
     * boot-time subscriptions made through DsiSubscribe are removed by core itself.
     */
    public void shutdown() {
        if (CLUSTER != null && !CLUSTER.stop()) { LsdExtender.trace(" dsispy: cluster observer worker stop NOT_PROVEN"); }
        int n = 0;
        try {
            DSIProxy proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
            java.util.Enumeration e = kinds.keys();
            while (e.hasMoreElements()) {
                String cn = (String) e.nextElement();
                try {
                    Class c = Class.forName(cn);
                    if (SERVICE_KIND.equals(kinds.get(cn))) {
                        proxy.removeServiceInterceptor(c, this);
                    } else {
                        proxy.removeListenerInterceptor(c, this);
                    }
                    n++;
                } catch (Throwable t) {
                    LsdExtender.trace(" dsispy: interceptor on " + cn + " not removed: " + t);
                }
            }
            e = dynListeners.keys();
            while (e.hasMoreElements()) {
                String cn = (String) e.nextElement();
                try {
                    org.dsi.ifc.base.DSIBase service = proxy.getService(LsdExtender.siTarget(), Class.forName(cn));
                    service.clearNotification((org.dsi.ifc.base.DSIListener) dynListeners.get(cn));
                    n++;
                } catch (Throwable t) {
                    LsdExtender.trace(" dsispy: listener on " + cn + " not cleared: " + t);
                }
            }
        } catch (Throwable t) {
            LsdExtender.trace(" dsispy: shutdown failed: " + t);
        }
        kinds.clear();
        dynListeners.clear();
        installed = false;
        LsdExtender.trace(" dsispy: shutdown, " + n + " interceptors/listeners removed (reload)");
    }

    private String unsubscribe(String spec) throws Exception {
        int colon = spec.indexOf(':');
        if (colon <= 0) {
            return "err usage: unsubscribe <ServiceClass>:<attr,attr>";
        }
        String svcName = spec.substring(0, colon).trim();
        int[] attrs = parseInts(spec.substring(colon + 1));
        Class svc = Class.forName(svcName);
        Object listener = dynListeners.get(svc.getName());
        if (listener == null) {
            return "err not subscribed: " + svc.getName();
        }
        org.dsi.ifc.base.DSIBase service = DSIProxyFactory.getDSIProxyAPI().getDSIProxy()
            .getService(LsdExtender.siTarget(), svc);
        service.clearNotification(attrs, (org.dsi.ifc.base.DSIListener) listener);
        return "ok unsubscribed " + svc.getName() + " attrs " + joinInts(attrs);
    }

    private DsiSpy() {
    }

    public String name() {
        return "dsispy [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    public boolean run() {
        if (installed) {
            return true;
        }
        if (!Config.getBool("lsdextender.dsispy.enabled", true)) {
            LsdExtender.trace(" dsispy: disabled by config");
            return true;
        }
        attempts++;
        if (LsdExtender.siTarget() == null) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            DSIProxy proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
            int n = 0;
            String[] services = split(Config.get("lsdextender.dsispy.services", ""));
            for (int i = 0; i < services.length; i++) {
                Class c = Class.forName(services[i]);
                proxy.addServiceInterceptor(c, this);
                kinds.put(c.getName(), "service");
                n++;
                LsdExtender.trace(" dsispy: service interceptor on " + c.getName());
            }
            String[] listeners = split(Config.get("lsdextender.dsispy.listeners", ""));
            for (int i = 0; i < listeners.length; i++) {
                Class c = Class.forName(listeners[i]);
                proxy.addListenerInterceptor(c, this);
                kinds.put(c.getName(), "listener");
                n++;
                LsdExtender.trace(" dsispy: listener interceptor on " + c.getName());
            }
            // subscriptions: "<ServiceClass>:<attr,attr>;<ServiceClass>:<attr>" - a dynamic
            // proxy implementing <ServiceClass>Listener logs every update the service sends
            String[] subs = splitOn(Config.get("lsdextender.dsispy.subscribe", ""), ';');
            for (int i = 0; i < subs.length; i++) {
                LsdExtender.trace(" dsispy: " + subscribe(subs[i], true));
                n++;
            }
            installed = true;
            if (CLUSTER != null) { CLUSTER.install(proxy); CLUSTER.start(); }
            LsdExtender.trace(" dsispy: " + n + " interceptors/subscriptions installed (attempt " + attempts + ")");
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(" dsispy: install attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    // Callback arguments are type name, instance, method index and arguments.
    // Preserve this order when formatting the event.
    public boolean before(String type, int instance, int methodIndex, Object[] args) {
        try {
            // Same epoch-millisecond wall clock as GAL's CLOCK_REALTIME stamp.
            // The HMI timer remains separately labelled, never equated to GAL uptime.
            String stamp = DM_LISTENER.equals(type)
                ? " hmi_ms=" + EventClock.millis() + " wall_ms=" + System.currentTimeMillis() : "";
            String kind = (String) kinds.get(type);
            String arrow = DM_LISTENER.equals(type) || "listener".equals(kind) ? "< " : "> ";
            String shortName = type.substring(type.lastIndexOf('.') + 1);
            // Reflection supplies a method name that may not match the native API.
            // The numeric method index identifies the callback.
            String line = " dsispy: " + arrow + shortName + ".#" + methodIndex + "~" + methodName(type, methodIndex, kind)
                + "(" + format(args) + ")" + (instance != 0 ? " inst=" + instance : "") + stamp;
            if (passesFilter(line)) {
                LsdExtender.trace(line);
            }
        } catch (Throwable ignored) {
            // never interfere with the call
        }
        return true;
    }

    /**
     * lsdextender.dsispy.filter: comma-separated substrings; when set, a spy line is
     * logged only if it contains one of them; a "!" prefix excludes instead
     * ("switchContext,!Brightness"). Empty = log everything. Changeable live with
     * "set lsdextender.dsispy.filter=..." or "spy filter ...".
     */
    static boolean passesFilter(String line) {
        String spec = Config.get("lsdextender.dsispy.filter", "");
        if (spec.length() == 0) {
            return true;
        }
        String[] parts = split(spec);
        boolean anyInclude = false;
        boolean included = false;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("!")) {
                if (line.indexOf(parts[i].substring(1)) >= 0) {
                    return false;
                }
            } else {
                anyInclude = true;
                if (line.indexOf(parts[i]) >= 0) {
                    included = true;
                }
            }
        }
        return !anyInclude || included;
    }

    public void after(String type, int instance, int methodIndex, Object[] args) {
        // nothing: the call was already logged by before()
    }

    /** Resolve a method index to a name by asking the framework's reflection map for
     *  every method of the interface once; falls back to "#index". */
    private String methodName(String type, int index, String kind) {
        if (DM_LISTENER.equals(type)) {
            // The original response dispatcher stores the actual Method name
            // before calling interceptors. Never guess a DM reply from MMAO.
            int typeIndex = Utils.getTypeIndex(type);
            String actual = typeIndex < 0 || index < 0 ? null : Utils.getRuntimeListenerMethodName(typeIndex, index);
            return actual == null || actual.equals("unknown") ? "#" + index : actual;
        }
        Hashtable map = (Hashtable) names.get(type);
        if (map == null) {
            map = new Hashtable();
            names.put(type, map);
            try {
                Class c = Class.forName(type);
                int typeIndex = Utils.getTypeIndex(c);
                MultipleMethodAccessibleObject mmao = "listener".equals(kind)
                    ? ServiceManager.reflectionFactory.getListenersMmao(typeIndex)
                    : ServiceManager.reflectionFactory.getServicesMmao(typeIndex);
                Method[] ms = c.getMethods();
                for (int i = 0; i < ms.length; i++) {
                    try {
                        int idx = mmao.getIndex(type, ms[i].getName(), ms[i].getParameterTypes());
                        if (idx >= 0 && map.get(new Integer(idx)) == null) {
                            map.put(new Integer(idx), ms[i].getName());
                        }
                    } catch (Throwable ignored) {
                        // not every method is a DSI method
                    }
                }
            } catch (Throwable t) {
                LsdExtender.trace(" dsispy: cannot map method names of " + type + ": " + t);
            }
        }
        Object n = map.get(new Integer(index));
        return n == null ? "#" + index : (String) n;
    }

    private static String format(Object[] args) {
        if (args == null) {
            return "";
        }
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            Object a = args[i];
            if (a == null) {
                b.append("null");
            } else if (a instanceof int[]) {
                int[] v = (int[]) a;
                b.append('[');
                for (int k = 0; k < v.length && k < MAX_ARRAY_ITEMS; k++) {
                    if (k > 0) {
                        b.append(' ');
                    }
                    b.append(v[k]);
                }
                if (v.length > MAX_ARRAY_ITEMS) {
                    b.append(" ...");
                }
                b.append(']');
            } else if (a instanceof Object[]) {
                b.append(a.getClass().getName()).append('[').append(((Object[]) a).length).append(']');
            } else {
                // Include complete structures and their trailing validity fields.
                // Object text is bounded to 16000 characters so its UTF-8 bytes fit
                // in the 64K feed message limit. Larger values receive an explicit
                // omission marker.
                String s = String.valueOf(a);
                if (s.length() > MAX_OBJECT_CHARS) {
                    s = "<omitted: DSI object exceeds 16000 characters>";
                }
                b.append(s);
            }
        }
        return b.toString();
    }

    /** Invocation handler of a dynamic listener: logs every callback with its arguments. */
    private static final class Logger implements java.lang.reflect.InvocationHandler {
        private final String shortName;
        private final String service;

        Logger(String type) {
            this.service = type;
            this.shortName = type.substring(type.lastIndexOf('.') + 1);
        }

        public Object invoke(Object proxy, Method m, Object[] args) {
            String name = m.getName();
            if (name.equals("hashCode")) {
                return new Integer(System.identityHashCode(proxy));
            }
            if (name.equals("equals")) {
                return new Boolean(proxy == (args == null ? null : args[0]));
            }
            if (name.equals("toString")) {
                return "dsispy listener for " + shortName;
            }
            try { if (CLUSTER != null) { CLUSTER.observe(service, m, args); } }
            catch (Throwable ignored) { /* observer failure must not suppress the original spy line */ }
            try {
                String line = " dsispy: = " + shortName + "." + name + "(" + format(args) + ")";
                if (passesFilter(line)) {
                    LsdExtender.trace(line);
                } else {
                    // Diagnostic selection must not discard subscribed data.
                    LsdExtender.feed(line);
                }
            } catch (Throwable ignored) {
                // never interfere with the framework
            }
            Class r = m.getReturnType();
            if (r == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (r == Integer.TYPE || r == Short.TYPE || r == Byte.TYPE) {
                return new Integer(0);
            }
            if (r == Long.TYPE) {
                return new Long(0L);
            }
            if (r == Float.TYPE) {
                return new Float(0f);
            }
            if (r == Double.TYPE) {
                return new Double(0d);
            }
            if (r == Character.TYPE) {
                return new Character('\0');
            }
            return null;
        }
    }

    private static int[] parseInts(String s) {
        String[] parts = split(s);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    private static String joinInts(int[] a) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(a[i]);
        }
        return b.toString();
    }

    private static String[] splitOn(String s, char sep) {
        java.util.Vector v = new java.util.Vector();
        int start = 0;
        while (start <= s.length()) {
            int at = s.indexOf(sep, start);
            if (at < 0) {
                at = s.length();
            }
            String part = s.substring(start, at).trim();
            if (part.length() > 0) {
                v.addElement(part);
            }
            start = at + 1;
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private static String[] split(String s) {
        java.util.Vector v = new java.util.Vector();
        int start = 0;
        while (start <= s.length()) {
            int comma = s.indexOf(',', start);
            if (comma < 0) {
                comma = s.length();
            }
            String part = s.substring(start, comma).trim();
            if (part.length() > 0) {
                v.addElement(part);
            }
            start = comma + 1;
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
