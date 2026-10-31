/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.Pps;
import org.korho.lsdextender.ProjectUpdates;

/** Off-event-thread adapter over the existing CLI and atomic local PPS path. */
final class PackageUpdatesClient implements PackageUpdatesModel.Transport {
    private static final int CAPABILITY_LIMIT = 64;
    private String activeWorker, activeConfig, activeStatus, activeRequest;
    public void run(PackageUpdatesProtocol.Request request, PackageUpdatesModel.Receiver receiver) throws Exception {
        if (ProjectUpdates.snapshot().busy) { throw new IOException("Another updater operation is active"); }
        String directory = Config.directory();
        if (directory == null || ProjectUpdates.inventoryOnly()) { throw new IOException("Package enrollment unavailable"); }
        String worker = Config.get("lsdextender.updates.worker", directory + "/hu-agent");
        String config = Config.get("lsdextender.updates.agent.config", directory + "/hu-agent.json");
        String status = Config.get("lsdextender.updates.status", "/ramdisk/pps/project-updates/status") + ".packages";
        if (!new File(worker).isAbsolute() || !new File(config).isAbsolute() || !new File(status).isAbsolute()) {
            throw new IOException("Absolute updater paths required");
        }
        if (!supports(worker, config)) { throw new IOException("Package protocol unavailable"); }
        Process child = Runtime.getRuntime().exec(arguments(worker, config, status, request));
        synchronized (this) { activeWorker = worker; activeConfig = config; activeStatus = status; activeRequest = request.id; }
        Thread output = drain(child.getInputStream()), errors = drain(child.getErrorStream());
        Exchange exchange = new Exchange(status, request, receiver);
        try {
            // The worker has its own transfer/transaction deadlines. Bound this
            // owned client's wait too; timeout is uncertain, never installed.
            for (int attempt = 0; attempt < 9000; attempt++) {
                exchange.poll();
                try {
                    int exit = child.exitValue();
                    exchange.poll();
                    completed(status, request, exit);
                    return;
                } catch (IllegalThreadStateException active) { Thread.sleep(100L); }
            }
            throw new IOException("Package worker result is not confirmed");
        } finally {
            synchronized (this) {
                if (request.id.equals(activeRequest)) { activeWorker = null; activeConfig = null; activeStatus = null; activeRequest = null; }
            }
            // Only the child started above is retired. Page closure never
            // interrupts this operation, and no PID from PPS is signalled.
            child.destroy();
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
            output.join(200L); errors.join(200L);
        }
    }
    public void abort(String request) throws Exception {
        final String[] command;
        synchronized (this) {
            if (request == null || !request.equals(activeRequest)) { throw new IOException("No matching owned package operation"); }
            command = command(activeWorker, activeConfig, activeStatus, "cancel", request);
        }
        Process child = Runtime.getRuntime().exec(command);
        Thread output = drain(child.getInputStream()), errors = drain(child.getErrorStream());
        try {
            for (int attempt = 0; attempt < 50; attempt++) {
                try {
                    if (child.exitValue() != 0) { throw new IOException("Package cancellation refused"); }
                    return;
                } catch (IllegalThreadStateException running) { Thread.sleep(100L); }
            }
            throw new IOException("Package cancellation not acknowledged");
        } finally {
            child.destroy();
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
            output.join(200L); errors.join(200L);
        }
    }
    private static void completed(String status, PackageUpdatesProtocol.Request request, int exit) throws IOException {
        PackageUpdatesProtocol.Reply reply = PackageUpdatesProtocol.parse(read(status), request.id, request.operation);
        if (!reply.terminal() || exit != 0 && !"error".equals(reply.phase) && !"recovery".equals(reply.phase)) {
            throw new IOException("Package worker did not finish with an authoritative result");
        }
    }
    static String[] arguments(String worker, String config, String status, PackageUpdatesProtocol.Request request) {
        return command(worker, config, status, request.operation, request.id);
    }
    private static String[] command(String worker, String config, String status, String operation, String request) {
        String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
        return new String[] { shell, "-c", "unset LD_PRELOAD; exec \"$@\"", "package-updates",
            worker, "hmi", config, "package-" + operation, status, request };
    }
    private static boolean supports(String worker, String config) throws Exception {
        String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
        final Process child = Runtime.getRuntime().exec(new String[] { shell, "-c", "unset LD_PRELOAD; exec \"$@\"", "package-capabilities",
            worker, "package-capabilities", config });
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final boolean[] complete = { false };
        Thread reader = new Thread(new Runnable() {
            public void run() {
                InputStream input = child.getInputStream();
                try {
                    byte[] buffer = new byte[CAPABILITY_LIMIT + 1]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        synchronized (bytes) {
                            if (bytes.size() + count > CAPABILITY_LIMIT) { bytes.reset(); bytes.write(0); return; }
                            bytes.write(buffer, 0, count);
                        }
                    }
                    synchronized (bytes) { complete[0] = true; }
                } catch (IOException closed) { /* No capability on read failure. */ }
                finally { try { input.close(); } catch (IOException ignored) { } }
            }
        }, "owned-package-capability-output");
        reader.setDaemon(true); reader.start();
        Thread errors = drain(child.getErrorStream());
        try {
            for (int attempt = 0; attempt < 50; attempt++) {
                try {
                    int exit = child.exitValue(); reader.join(200L);
                    synchronized (bytes) {
                        return exit == 0 && !reader.isAlive() && complete[0] && "package-hmi-v2\n".equals(bytes.toString("UTF-8"));
                    }
                } catch (IllegalThreadStateException active) { Thread.sleep(100L); }
            }
            return false;
        } finally {
            child.destroy(); child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
            reader.join(200L); errors.join(200L);
        }
    }
    private static final class Exchange {
        private static final String INPUT = "input";
        private final String status;
        private final PackageUpdatesProtocol.Request request;
        private final PackageUpdatesModel.Receiver receiver;
        private String previous = "";
        private boolean submitted;
        Exchange(String status, PackageUpdatesProtocol.Request request, PackageUpdatesModel.Receiver receiver) {
            this.status = status; this.request = request; this.receiver = receiver;
            submitted = PackageUpdatesProtocol.LIST.equals(request.operation);
        }
        void poll() throws Exception {
            if (!new File(status).exists()) { return; }
            String raw = read(status);
            if (raw.equals(previous) || ("\n" + raw).indexOf("\nrequest::" + request.id + "\n") < 0) { return; }
            PackageUpdatesProtocol.Reply reply = PackageUpdatesProtocol.parse(raw, request.id, request.operation);
            if (INPUT.equals(reply.phase)) {
                if (submitted || reply.generation != request.generation || !reply.context.equals(request.context)) {
                    throw new IOException("Invalid package input ownership prompt");
                }
                // Only the matching lock-owning worker requests selection.
                // Competing/refused commands never write the shared control.
                Pps.write(status + ".control", request.attributes()); submitted = true;
            } else {
                if (!submitted && !"error".equals(reply.phase) && !"recovery".equals(reply.phase)) {
                    throw new IOException("Package worker skipped input ownership");
                }
                receiver.reply(raw);
            }
            previous = raw;
        }
    }
    static String read(String path) throws IOException {
        FileInputStream stream = new FileInputStream(path);
        try {
            byte[] bytes = new byte[PackageUpdatesProtocol.LIMIT + 1];
            // PPS snapshots are indivisible. Never issue a small initial read.
            int count = stream.read(bytes);
            if (count <= 0) { return ""; }
            if (count > PackageUpdatesProtocol.LIMIT) { throw new IOException("Package snapshot outside bound"); }
            return new String(bytes, 0, count, "UTF-8");
        } finally { stream.close(); }
    }
    private static Thread drain(final InputStream stream) {
        Thread result = new Thread(new Runnable() {
            public void run() {
                try { byte[] bytes = new byte[512]; while (stream.read(bytes) >= 0) { /* Worker diagnostics go to slog. */ } }
                catch (IOException closed) { /* Owned worker closed. */ }
                finally { try { stream.close(); } catch (IOException ignored) { } }
            }
        }, "owned-package-worker-output");
        result.setDaemon(true); result.start(); return result;
    }

    /** Own-core capability lookup: no modern method reference links on core 422. */
    static boolean requestCancelCapable(Class core) {
        try {
            return method(core, "cancellableRequest", new Class[0], String.class)
                && method(core, "cancel", new Class[] { String.class }, Boolean.TYPE)
                && method(core, "cancelling", new Class[0], Boolean.TYPE);
        } catch (Exception absent) { return false; }
    }
    private static boolean method(Class core, String name, Class[] arguments, Class result) throws NoSuchMethodException {
        java.lang.reflect.Method method = core.getMethod(name, arguments);
        return method.getReturnType() == result && java.lang.reflect.Modifier.isStatic(method.getModifiers());
    }
    static String cancellableRequest() { return cancellableRequest(ProjectUpdates.class); }
    static String cancellableRequest(Class core) {
        if (!requestCancelCapable(core)) { return ""; }
        try { return (String)core.getMethod("cancellableRequest", new Class[0]).invoke(null, new Object[0]); }
        catch (Exception unavailable) { return ""; }
    }
    static boolean cancelling() {
        if (!requestCancelCapable(ProjectUpdates.class)) { return false; }
        try { return ((Boolean)ProjectUpdates.class.getMethod("cancelling", new Class[0]).invoke(null, new Object[0])).booleanValue(); }
        catch (Exception unavailable) { return false; }
    }
    static boolean cancel(String request) { return cancel(ProjectUpdates.class, request); }
    static boolean cancel(Class core, String request) {
        if (request == null || request.length() == 0 || !requestCancelCapable(core)) { return false; }
        try { return ((Boolean)core.getMethod("cancel", new Class[] { String.class }).invoke(null, new Object[] { request })).booleanValue(); }
        catch (Exception unavailable) { return false; }
    }
}
