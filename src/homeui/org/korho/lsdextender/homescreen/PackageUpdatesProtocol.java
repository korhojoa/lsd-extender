/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/** Versioned, bounded worker read model. No package planning or execution here. */
final class PackageUpdatesProtocol {
    static final int LIMIT = 98304, MAX_ROWS = 128;
    static final String LIST = "list", PREVIEW = "preview", APPLY = "apply", INSTALLED = "installed";
    private PackageUpdatesProtocol() { }

    static final class Request {
        final String id, operation, context, preview, desired, removed;
        final long generation;
        Request(String id, String operation, long generation, String context, String preview, String desired, String removed) {
            if (!requestToken(id) || !operation(operation) || generation < 0 || !digestOrEmpty(context)
                    || !digestOrEmpty(preview) || !ids(desired) || !ids(removed)) {
                throw new IllegalArgumentException("Invalid package request");
            }
            if (!"list".equals(operation) && !digest(context)) { throw new IllegalArgumentException("Package context required"); }
            if ("apply".equals(operation) && !digest(preview)) { throw new IllegalArgumentException("Reviewed plan required"); }
            String[] wanted = split(desired, ',');
            for (int i = 0; desired.length() > 0 && i < wanted.length; i++) {
                if (contains(removed, wanted[i])) { throw new IllegalArgumentException("Conflicting package selection"); }
            }
            this.id = id; this.operation = operation; this.generation = generation; this.context = context;
            this.preview = preview; this.desired = desired; this.removed = removed;
        }
        String attributes() {
            return "package_hmi::2\nrequest::" + id + "\naction::package-" + operation
                + "\ngeneration::" + generation + "\ncontext::" + context + "\npreview::" + preview
                + "\ndesired::" + desired + "\nremoved::" + removed + "\n";
        }
    }

    static final class Item {
        final String id, title, installed, available, reason, role;
        final boolean compatible, managed, desired, removed;
        Item(String record) throws IOException {
            String[] fields = split(record, '~');
            require(fields.length == 10, "Incomplete package row");
            id = fields[0]; title = fields[1]; installed = fields[2]; available = fields[3];
            compatible = bool(fields[4]); managed = bool(fields[5]); desired = bool(fields[6]); removed = bool(fields[7]);
            reason = fields[8]; role = fields[9];
            require(token(id) && text(title, 100) && title.length() > 0 && version(installed) && version(available)
                && text(reason, 256) && !(desired && removed), "Invalid package row");
            require(compatible || reason.length() > 0, "Unavailable package needs a reason");
            require(oneOf(role, "none|explicit|dependency|retained"), "Unknown package selection role");
        }
    }

    static final class Change {
        final String id, action, from, to, activation;
        Change(String record) throws IOException {
            String[] fields = split(record, '~');
            require(fields.length == 5, "Incomplete package change");
            id = fields[0]; action = fields[1]; from = fields[2]; to = fields[3]; activation = fields[4];
            require(token(id) && oneOf(action, "install|update|remove") && version(from) && version(to)
                && oneOf(activation, "live-reload|owned-process|restart"), "Invalid package change");
            require("remove".equals(action) ? !"-".equals(from) && "-".equals(to) : !"-".equals(to), "Invalid change versions");
        }
    }

    static final class Reply {
        final String request, operation, phase, context, preview, message, units;
        final long generation, done, total, download, staging, rollback, reserved;
        final boolean restart, cancelSupported;
        private final Item[] items;
        private final Change[] changes;
        Reply(Hashtable fields) throws IOException {
            require("2".equals(value(fields, "package_hmi")), "Package protocol unavailable");
            request = value(fields, "request"); operation = value(fields, "operation"); phase = value(fields, "phase");
            context = value(fields, "context"); preview = value(fields, "preview"); message = value(fields, "message");
            units = value(fields, "units"); generation = number(value(fields, "generation"));
            done = number(value(fields, "done")); total = number(value(fields, "total"));
            download = number(value(fields, "download")); staging = number(value(fields, "staging"));
            rollback = number(value(fields, "rollback")); reserved = number(value(fields, "reserved"));
            restart = bool(value(fields, "restart"));
            cancelSupported = bool(value(fields, "cancel_supported"));
            require(requestToken(request) && operation(operation) && oneOf(phase,
                "input|list|preview|checking|downloading|verifying|installing|installed|cancelled|error|recovery"), "Invalid package reply identity");
            require(digest(context) && digestOrEmpty(preview) && text(message, 512) && done <= total
                && oneOf(units, "none|bytes|groups"), "Invalid package reply state");
            require(!"none".equals(units) || total == 0, "Progress requires units");
            if (INSTALLED.equals(phase)) { require(done == total, "Incomplete installed result"); }
            if (PREVIEW.equals(phase)) { require(PREVIEW.equals(operation) && digest(preview), "Missing reviewed plan"); }
            if (LIST.equals(phase)) { require(LIST.equals(operation), "Wrong list operation"); }
            if (oneOf(phase, "checking|downloading|verifying|installing|installed|cancelled")) {
                require("apply".equals(operation), "Unexpected package execution state");
            }
            require(!cancelSupported || APPLY.equals(operation) && oneOf(phase, "checking|downloading|verifying|installing"),
                "Cancellation requires an active apply after input");
            int count = count(value(fields, "packages")); items = new Item[count];
            Hashtable seen = new Hashtable();
            for (int i = 0; i < count; i++) {
                items[i] = new Item(value(fields, "p" + i));
                require(seen.put(items[i].id, items[i]) == null, "Duplicate package");
            }
            count = count(value(fields, "changes")); changes = new Change[count];
            Hashtable changed = new Hashtable();
            for (int i = 0; i < count; i++) {
                changes[i] = new Change(value(fields, "c" + i));
                require(seen.containsKey(changes[i].id) && changed.put(changes[i].id, changes[i]) == null, "Unknown or duplicate change");
            }
            validateFields(fields, items.length, changes.length);
        }
        int size() { return items.length; }
        Item item(int index) { return items[index]; }
        int changeCount() { return changes.length; }
        Change change(int index) { return changes[index]; }
        boolean terminal() { return oneOf(phase, "list|preview|installed|cancelled|error|recovery"); }
    }

    static Reply parse(String raw, String request, String operation) throws IOException {
        require(raw != null && raw.length() <= LIMIT && raw.endsWith("\n"), "Incomplete package snapshot");
        Hashtable fields = new Hashtable();
        String[] lines = split(raw, '\n');
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            if (i == 0 && line.startsWith("@") && line.indexOf("::") < 0) { continue; }
            int colon = line.indexOf("::");
            require(colon > 0, "Malformed package attribute");
            String key = line.substring(0, colon);
            require(fields.put(key, line.substring(colon + 2)) == null, "Duplicate package attribute");
        }
        Reply result = new Reply(fields);
        require(request.equals(result.request) && operation.equals(result.operation), "Stale package reply");
        return result;
    }
    private static void validateFields(Hashtable fields, int packages, int changes) throws IOException {
        String common = "package_hmi|request|operation|phase|generation|context|preview|message|done|total|units|restart|cancel_supported|download|staging|rollback|reserved|packages|changes";
        java.util.Enumeration keys = fields.keys();
        while (keys.hasMoreElements()) {
            String key = (String)keys.nextElement();
            if (oneOf(key, common)) { continue; }
            require(key.length() > 1 && (key.charAt(0) == 'p' || key.charAt(0) == 'c'), "Unknown package attribute");
            int index = count(key.substring(1));
            require(index < MAX_ROWS, "Package row outside bound");
            int used = key.charAt(0) == 'p' ? packages : changes;
            require(index < used || "".equals(fields.get(key)), "Retained extra package row");
        }
    }
    static boolean token(String value) {
        if (value == null || value.length() == 0 || value.length() > 96 || ".".equals(value) || "..".equals(value)) { return false; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && "._-".indexOf(c) < 0) { return false; }
        }
        return true;
    }
    private static boolean requestToken(String value) { return token(value) && value.length() <= 64 && value.indexOf('.') < 0; }
    private static boolean version(String value) { return "-".equals(value) || text(value, 96) && value.length() > 0; }
    private static boolean text(String value, int limit) {
        if (value.length() > limit) { return false; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c == '~' || c == '\ufffd') { return false; }
        }
        return true;
    }
    static boolean digest(String value) {
        if (value == null || value.length() != 64) { return false; }
        for (int i = 0; i < value.length(); i++) { if ("0123456789abcdef".indexOf(value.charAt(i)) < 0) { return false; } }
        return true;
    }
    private static boolean digestOrEmpty(String value) { return "".equals(value) || digest(value); }
    private static boolean operation(String value) { return oneOf(value, "list|preview|apply"); }
    static boolean contains(String ids, String id) { return ("," + ids + ",").indexOf("," + id + ",") >= 0; }
    static boolean oneOf(String value, String choices) { return value != null && ("|" + choices + "|").indexOf("|" + value + "|") >= 0; }
    private static boolean ids(String value) {
        if (value == null) { return false; }
        if (value.length() == 0) { return true; }
        String[] parts = split(value, ',');
        if (parts.length > MAX_ROWS) { return false; }
        for (int i = 0; i < parts.length; i++) { if (!token(parts[i]) || i > 0 && parts[i - 1].compareTo(parts[i]) >= 0) { return false; } }
        return true;
    }
    private static long number(String value) throws IOException {
        try {
            long number = Long.parseLong(value);
            require(number >= 0 && String.valueOf(number).equals(value), "Invalid package number");
            return number;
        } catch (NumberFormatException failure) { throw new IOException("Invalid package number"); }
    }
    private static int count(String value) throws IOException {
        long result = number(value); require(result <= MAX_ROWS, "Too many package rows"); return (int)result;
    }
    private static boolean bool(String value) throws IOException {
        require("true".equals(value) || "false".equals(value), "Invalid package boolean"); return "true".equals(value);
    }
    private static String value(Hashtable fields, String name) throws IOException {
        String result = (String)fields.get(name); require(result != null, "Missing package attribute: " + name); return result;
    }
    static String[] split(String value, char separator) {
        Vector parts = new Vector(); int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == separator) { parts.addElement(value.substring(start, i)); start = i + 1; }
        }
        parts.addElement(value.substring(start)); String[] result = new String[parts.size()]; parts.copyInto(result); return result;
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
