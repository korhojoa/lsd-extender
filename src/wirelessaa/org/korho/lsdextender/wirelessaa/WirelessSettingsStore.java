/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Two checked generations beside lsd-extender on the card. Card deployment has
 * a documented rename problem, so durable writes do not depend on rename().
 * Only the older slot is overwritten; a torn write leaves the last good slot.
 */
final class WirelessSettingsStore {
    private static final class Slot {
        String path;
        String policy;
        long sequence;
    }

    static String read(String path) throws IOException {
        Slot slot = latest(path);
        return slot == null ? null : slot.policy;
    }

    static void write(String path, String policy) throws IOException {
        File parent = new File(path).getParentFile();
        if (parent == null || !parent.isDirectory()) { throw new IOException("lsd-extender card directory unavailable"); }
        // Validate before touching either slot.
        WirelessPolicy.decode(policy);
        Slot old = latest(path);
        long sequence = old == null ? 1 : old.sequence + 1;
        if (sequence <= 0) { throw new IOException("settings sequence overflow"); }
        String dest = old != null && path.equals(old.path) ? path + ".backup" : path;
        String body = "sequence=" + sequence + "\n" + policy;
        String data = "wireless-store=1\nchecksum=" + checksum(body) + "\n" + body;
        FileOutputStream out = new FileOutputStream(dest);
        try {
            out.write(data.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally { out.close(); }
        Slot written = readSlot(dest);
        if (written == null || written.sequence != sequence || !policy.equals(written.policy)) {
            throw new IOException("card settings verification failed");
        }
    }

    private static Slot latest(String path) throws IOException {
        Slot a = readSlot(path);
        Slot b = readSlot(path + ".backup");
        if (a == null && b == null) {
            if (new File(path).exists() || new File(path + ".backup").exists()) {
                throw new IOException("no valid card settings generation");
            }
            return null;
        }
        if (a != null && b != null && a.sequence == b.sequence && !a.policy.equals(b.policy)) {
            throw new IOException("conflicting card settings generations");
        }
        return a == null ? b : b == null || a.sequence >= b.sequence ? a : b;
    }

    private static Slot readSlot(String path) {
        try {
            String data = WirelessFiles.read(path);
            String header = "wireless-store=1\nchecksum=";
            if (!data.startsWith(header)) { return null; }
            int first = data.indexOf('\n', header.length());
            if (first < 0) { return null; }
            String sum = data.substring(header.length(), first);
            String body = data.substring(first + 1);
            if (!sum.equals(checksum(body)) || !body.startsWith("sequence=")) { return null; }
            int second = body.indexOf('\n');
            if (second < 0) { return null; }
            long sequence = Long.parseLong(body.substring(9, second));
            if (sequence <= 0) { return null; }
            String policy = body.substring(second + 1);
            WirelessPolicy.decode(policy);
            Slot slot = new Slot();
            slot.path = path; slot.sequence = sequence; slot.policy = policy;
            return slot;
        } catch (Exception e) { return null; }
    }

    private static String checksum(String text) throws IOException {
        CRC32 crc = new CRC32();
        byte[] bytes = text.getBytes("UTF-8");
        crc.update(bytes, 0, bytes.length);
        return Long.toHexString(crc.getValue());
    }
}
