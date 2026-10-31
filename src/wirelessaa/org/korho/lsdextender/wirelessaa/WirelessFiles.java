/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** No shell interpolation, partial-write fallback, or I/O on a widget callback. */
final class WirelessFiles {
    private static final int MAX_FILE_BYTES = 65536;

    static String read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_FILE_BYTES) { throw new IOException("file too large"); }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    static void write(String path, String value, boolean durable) throws IOException {
        File dest = new File(path);
        File dir = dest.getParentFile();
        if (dir == null || !dir.isDirectory()) { throw new IOException("settings directory unavailable: " + dir); }
        File temp = new File(path + ".tmp");
        try {
            writeWhole(temp, value, durable);
            // The RAM filesystem may refuse to replace an existing file by rename.
            // Write the complete record to the temporary file first, then try
            // the rename. If it fails, write the whole value to the destination.
            // This fallback can expose an incomplete read while the existing
            // file is truncated and rewritten. Readers must validate the whole
            // record before using it, and can retain a prior valid lease within
            // its grace period. The temporary file is removed on both paths.
            // No temporary name is published to readers.
            if (!temp.renameTo(dest)) { writeWhole(dest, value, durable); }
        } finally { if (temp.exists()) { temp.delete(); } }
    }

    private static void writeWhole(File file, String value, boolean durable) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(value.getBytes("UTF-8"));
            out.flush();
            if (durable) { out.getFD().sync(); }
        } finally { out.close(); }
    }

    static void remove(String path) throws IOException {
        File f = new File(path);
        if (f.exists() && !f.delete()) { throw new IOException("cannot remove " + path); }
    }

    static void writePps(String path, String value) throws IOException {
        File parent = new File(path).getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) { throw new IOException("PPS directory unavailable"); }
        RandomAccessFile out = new RandomAccessFile(path, "rw");
        try { out.write(value.getBytes("UTF-8")); } finally { out.close(); }
    }

    static void writeControl(String path, String flat) throws IOException {
        StringBuffer attributes = new StringBuffer();
        StringBuffer allow = new StringBuffer();
        int from = 0;
        boolean policy = false;
        while (from < flat.length()) {
            int end = flat.indexOf('\n', from);
            if (end < 0) { throw new IOException("incomplete control record"); }
            String line = flat.substring(from, end);
            int equal = line.indexOf('=');
            if (equal <= 0) { throw new IOException("invalid control record"); }
            String name = line.substring(0, equal);
            String value = line.substring(equal + 1);
            if (name.equals("allow")) {
                if (allow.length() > 0) { allow.append(','); }
                allow.append(value);
            } else {
                if (name.equals("selected")) { policy = true; }
                attributes.append(name).append("::").append(value).append('\n');
            }
            from = end + 1;
        }
        if (policy) { attributes.append("allow::").append(allow).append('\n'); }
        writePps(path, attributes.toString());
    }

    static String readControl(String path) throws IOException {
        String value = read(path);
        String[] keys = { "version", "revision", "epoch", "updated_ms", "enabled", "selected", "allow",
            "peer", "session", "stream", "held", "accepted", "nosession", "badpeer", "lost", "restarts", "end" };
        StringBuffer flat = new StringBuffer();
        for (int k = 0; k < keys.length; k++) {
            String prefix = keys[k] + "::";
            int from = 0;
            boolean found = false;
            while (from < value.length()) {
                int end = value.indexOf('\n', from);
                if (end < 0) { end = value.length(); }
                String line = value.substring(from, end);
                if (line.startsWith(prefix)) {
                    if (found) { throw new IOException("duplicate PPS attribute"); }
                    found = true;
                    String data = line.substring(prefix.length());
                    if (keys[k].equals("allow")) {
                        int start = 0;
                        while (start < data.length()) {
                            int comma = data.indexOf(',', start);
                            if (comma < 0) { comma = data.length(); }
                            flat.append("allow=").append(data.substring(start, comma)).append('\n');
                            start = comma + 1;
                        }
                    } else { flat.append(keys[k]).append('=').append(data).append('\n'); }
                }
                from = end + 1;
            }
        }
        return flat.toString();
    }
}
