/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Vector;

/**
 * Line feed over TCP: every line the extension writes to slog is also sent to
 * every connected client, newline-terminated, so native programs (a game reading
 * wheel keys, the dio shim reading the cluster sync signal) can consume them
 * without parsing slog. Clients can also send commands. Off when the port is 0.
 *
 *   lsdextender.publish.port   TCP port (default 0 = off)
 *
 * Each client has one writer and a bounded queue shared by feed lines and command
 * replies. HMI/DSI callers never perform socket I/O. A client that cannot keep up
 * is disconnected rather than delaying other clients or silently losing lines.
 */
public final class Publisher {
    private static ServerSocket server;
    private static final Vector clients = new Vector();
    private static boolean started;

    private Publisher() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        final int port = Config.getInt("lsdextender.publish.port", 0);
        if (port <= 0) {
            Slog.write(" publish: off (lsdextender.publish.port=0)");
            return;
        }
        try {
            server = new ServerSocket(port);
        } catch (Throwable t) {
            Slog.write(" publish: cannot listen on " + port + ": " + t);
            return;
        }
        if (Config.getBool("lsdextender.publish.pf", true)) {
            PfOpen.keepOpen(port);
        }
        Thread acceptor = new Thread(new Runnable() {
            public void run() {
                int failures = 0;
                while (true) {
                    Socket s; // NOPMD CloseResource - addClient owns it; failed setup closes it below.
                    try {
                        s = server.accept();
                    } catch (Throwable t) {
                        // the listening socket itself is broken: nothing to serve any more
                        Slog.write(" publish: accept failed, feed closed: " + t);
                        return;
                    }
                    try {
                        s.setTcpNoDelay(true);
                        addClient(s);
                        Slog.write(" publish: client " + s.getInetAddress().getHostAddress() + " connected (" + clients.size() + ")");
                        failures = 0;
                    } catch (Throwable t) {
                        // A client's setup failed.
                        // Remove that client and keep the accept loop running
                        // so later clients can connect.
                        failures++;
                        synchronized (clients) {
                            for (int i = clients.size() - 1; i >= 0; i--) {
                                Client c = (Client) clients.elementAt(i);
                                if (c.socket == s) {
                                    c.disconnect("setup failed");
                                }
                            }
                        }
                        try {
                            s.close();
                        } catch (Throwable ignored) {
                            // gone anyway
                        }
                        if (failures <= 3 || failures % 100 == 0) {
                            Slog.write(" publish: client setup failed (" + failures + "), dropped, still accepting: " + t);
                        }
                    }
                }
            }
        }, "lsd-extender-publish");
        acceptor.setDaemon(true);
        acceptor.start();
        Slog.write(" publish: listening on tcp port " + port);
    }

    /** socket -> filter spec (comma list of substrings, "!x" excludes); absent = everything */
    private static final java.util.Hashtable filters = new java.util.Hashtable();

    private static final int MAX_PENDING_LINES = 128;
    private static final int MAX_PENDING_BYTES = 65536;

    /** Package-visible so the host test can attach deterministic sockets. */
    static void addClient(Socket s) throws Exception {
        Client client = new Client(s);
        synchronized (clients) {
            clients.addElement(client);
        }
        try {
            client.start();
        } catch (Throwable t) {
            client.disconnect("setup failed: " + t);
            throw new Exception("client setup failed: " + t);
        }
    }

    private static final class Client implements Runnable {
        final Socket socket;
        private final byte[][] pending = new byte[MAX_PENDING_LINES][];
        private final long[] queuedAt = new long[MAX_PENDING_LINES];
        private int head;
        private int count;
        private int bytes;
        private boolean closed;
        private boolean nativeStream;
        private NativeAppSession nativeApp;
        private long traceSequence;

        Client(Socket socket) {
            this.socket = socket;
        }

        void start() {
            Thread writer = new Thread(this, "lsd-extender-publish-writer");
            writer.setDaemon(true);
            writer.start();
            startReader(this);
        }

        void offer(byte[] data) {
            offer(data, false);
        }

        private void offer(byte[] data, boolean feed) {
            synchronized (this) {
                if (closed || (feed && nativeStream)) {
                    return;
                }
                if (count < pending.length && data.length <= MAX_PENDING_BYTES - bytes) {
                    int tail = (head + count) % pending.length;
                    pending[tail] = data;
                    queuedAt[tail] = nativeStream && Config.getBool("lsdextender.input.trace", false)
                        ? EventClock.millis() : -1;
                    count++;
                    bytes += data.length;
                    notifyAll();
                    return;
                }
            }
            // Closing a socket and logging can block too. The publishing thread
            // only retires the queue; a separate cleanup thread does that I/O.
            disconnect("output queue full");
        }

        void offerFeed(byte[] data) {
            offer(data, true);
        }

        synchronized void privateStream() {
            nativeStream = true;
            for (int i = 0; i < pending.length; i++) { pending[i] = null; }
            head = 0;
            count = 0;
            bytes = 0;
        }

        public void run() {
            try {
                OutputStream out = socket.getOutputStream(); // NOPMD CloseResource - disconnect closes the socket and both streams.
                while (true) {
                    byte[] data;
                    long queued;
                    synchronized (this) {
                        while (!closed && count == 0) {
                            wait();
                        }
                        if (closed) {
                            return;
                        }
                        data = pending[head];
                        queued = queuedAt[head];
                        pending[head] = null;
                        head = (head + 1) % pending.length;
                        count--;
                        bytes -= data.length;
                    }
                    long start = queued >= 0 ? EventClock.millis() : -1;
                    out.write(data);
                    out.flush();
                    if (queued >= 0) {
                        NativeInputTrace.written(data, queued, start, EventClock.millis(),
                            System.identityHashCode(socket), ++traceSequence);
                    }
                }
            } catch (Throwable t) {
                disconnect("write failed: " + t);
            }
        }

        void disconnect(final String reason) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                for (int i = 0; i < pending.length; i++) {
                    pending[i] = null;
                }
                count = 0;
                bytes = 0;
                notifyAll();
            }
            synchronized (clients) {
                clients.removeElement(this);
            }
            Thread cleanup = new Thread(new Runnable() {
                public void run() {
                    synchronized (Publisher.class) {
                        NativeApps.closed(nativeApp);
                        connectionClosed(socket);
                    }
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                        // Logical retirement already happened.
                    }
                    Slog.write(" publish: " + EventClock.stamp()
                        + " client disconnected: " + reason);
                }
            }, "lsd-extender-publish-close");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    /**
     * Commands that belong to the connection rather than to the extension:
     *   filter <spec>   this client receives only lines matching spec ("filter" alone
     *                   or "filter off" = everything again)
     *   grab on|off     refused: shared stock touch interception was removed
     * Returns null when the line is not one of these.
     */
    private static synchronized String connectionCommand(Socket s, String line) {
        Client client = null;
        synchronized (clients) {
            for (int i = 0; i < clients.size(); i++) {
                if (((Client) clients.elementAt(i)).socket == s) {
                    client = (Client) clients.elementAt(i);
                    break;
                }
            }
        }
        if (client == null) {
            return "err connection closed";
        }
        if (line.startsWith("app register ")) {
            if (s.getInetAddress() == null || !"127.0.0.1".equals(s.getInetAddress().getHostAddress())) {
                return "err app registration requires a local connection";
            }
            if (client.nativeApp != null) { return "err app already registered on this connection"; }
            final Client output = client;
            client.privateStream();
            // The registration and retirement paths serialize on Publisher.class.
            client.nativeApp = NativeApps.register(line.substring(13), new NativeAppSession.Output() {
                public void send(String value) { output.offer((value + "\n").getBytes()); }
            });
            filters.put(s, "__native_app_private_stream__");
            return "ok app registered 1";
        }
        if (line.equals("filter") || line.equals("filter off")) {
            filters.remove(s);
            return "ok filter off (every line)";
        }
        if (line.startsWith("filter ")) {
            String spec = line.substring(7).trim();
            filters.put(s, spec);
            return "ok filter " + spec;
        }
        if (line.equals("grab on") || line.equals("grab off")) {
            return "err touch grab unavailable";
        }
        return null;
    }

    private static void connectionClosed(Socket s) {
        filters.remove(s);
    }

    /** Comma-separated substrings; "!x" excludes; empty = everything. */
    static boolean passesFilter(String spec, String line) {
        if (spec == null || spec.length() == 0) {
            return true;
        }
        boolean anyInclude = false;
        boolean included = false;
        int start = 0;
        while (start <= spec.length()) {
            int comma = spec.indexOf(',', start);
            if (comma < 0) {
                comma = spec.length();
            }
            String part = spec.substring(start, comma).trim();
            start = comma + 1;
            if (part.length() == 0) {
                continue;
            }
            if (part.startsWith("!")) {
                if (line.indexOf(part.substring(1)) >= 0) {
                    return false;
                }
            } else {
                anyInclude = true;
                if (line.indexOf(part) >= 0) {
                    included = true;
                }
            }
        }
        return !anyInclude || included;
    }

    /** Per-client reader: every line the client sends is a command; the reply goes back
     *  to that client only. A client that only listens never sends and costs nothing. */
    private static void startReader(final Client client) {
        final Socket s = client.socket; // NOPMD CloseResource - borrowed; the reader's finally block calls disconnect.
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())); // NOPMD CloseResource - finally disconnects the socket and both streams.
                    String line;
                    while ((line = in.readLine()) != null) {
                        String reply;
                        try {
                            reply = connectionCommand(s, line.trim());
                            if (reply == null) {
                                reply = LsdExtender.command(line);
                            }
                        } catch (Throwable e) {
                            reply = "err " + e;
                        }
                        if (reply != null) {
                            client.offer((reply + "\n").getBytes());
                        }
                    }
                } catch (Throwable ignored) {
                    // client gone
                } finally {
                    client.disconnect("reader closed");
                }
            }
        }, "lsd-extender-cmd");
        t.setDaemon(true);
        t.start();
    }

    /** Queue one line per matching client without doing network or file I/O. */
    static void publish(String line) {
        if (server == null) {
            return;
        }
        Object[] cs;
        synchronized (clients) {
            if (clients.isEmpty()) {
                return;
            }
            cs = new Object[clients.size()];
            clients.copyInto(cs);
        }
        byte[] data = (line + "\n").getBytes();
        for (int i = 0; i < cs.length; i++) {
            Client client = (Client) cs[i];
            String spec = (String) filters.get(client.socket);
            if (spec != null && !passesFilter(spec, line)) {
                continue;
            }
            client.offerFeed(data);
        }
    }
}
