/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.nativedestination;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Hashtable;

/** Bounded complete PPS snapshots, not a stream of partially accepted fields. */
final class DestinationWire {
    private static final int MAX_OBJECT = 8192;
    final String epoch;
    final long session;
    final long destination;
    final long serial;
    final String challenge;
    final boolean available;
    final String address;

    private DestinationWire(Hashtable values, boolean reply) {
        if (!"1".equals(values.get("schema"))) { throw new IllegalArgumentException("schema"); }
        epoch = (String)values.get("producer_epoch");
        challenge = reply ? (String)values.get("challenge") : null;
        if (!DestinationState.token(epoch) || (reply && !DestinationState.token(challenge))) {
            throw new IllegalArgumentException("token");
        }
        session = number((String)values.get("session_gen"), false);
        destination = number((String)values.get("destination_gen"), false);
        serial = number((String)values.get(reply ? "reply_serial" : "offer_serial"), false);
        String state = (String)values.get("state");
        if (!"available".equals(state) && !"unavailable".equals(state)) {
            throw new IllegalArgumentException("state");
        }
        available = "available".equals(state);
        address = (String)values.get("address_utf8_hex");
        if (available ? !DestinationState.address(address) : address != null) {
            throw new IllegalArgumentException("address");
        }
    }

    static DestinationWire parse(String body, boolean reply) {
        if (body == null || body.length() > MAX_OBJECT || !body.endsWith("\n")) {
            throw new IllegalArgumentException("incomplete object");
        }
        Hashtable values = new Hashtable();
        int start = 0;
        while (start < body.length()) {
            int end = body.indexOf('\n', start);
            String line = body.substring(start, end);
            start = end + 1;
            if (line.startsWith("@") && values.isEmpty()) { continue; }
            int colon = line.indexOf("::");
            if (colon <= 0) { throw new IllegalArgumentException("attribute"); }
            String key = line.substring(0, colon);
            String value = line.substring(colon + 2);
            if (values.containsKey(key)) { throw new IllegalArgumentException("duplicate attribute"); }
            if (!allowed(key, reply)) {
                throw new IllegalArgumentException("unexpected attribute");
            }
            values.put(key, value);
        }
        return new DestinationWire(values, reply);
    }

    private static boolean allowed(String key, boolean reply) {
        return key.equals("schema") || key.equals("producer_epoch") || key.equals("session_gen")
                || key.equals("destination_gen") || key.equals(reply ? "reply_serial" : "offer_serial")
                || (reply && key.equals("challenge")) || key.equals("state")
                || key.equals("address_utf8_hex") || key.equals("seq") || key.equals("updated_ms");
    }

    static DestinationWire read(String path, boolean reply) throws IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] buffer = new byte[MAX_OBJECT + 1];
            int count = in.read(buffer);
            if (count <= 0 || count > MAX_OBJECT) { throw new IOException("object size"); }
            // PPS reads return one complete object or fail for an insufficient buffer.
            String body = new String(buffer, 0, count, "US-ASCII");
            for (int i = 0; i < count; i++) {
                if (buffer[i] < 0) { throw new IOException("non-ASCII wire object"); }
            }
            return parse(body, reply);
        } finally { in.close(); }
    }

    static long number(String text, boolean zero) {
        if (text == null) { throw new IllegalArgumentException("missing number"); }
        long value = Long.parseLong(text);
        if (value < (zero ? 0 : 1) || !Long.toString(value).equals(text)) {
            throw new IllegalArgumentException("noncanonical number");
        }
        return value;
    }

    String identity() { return epoch + ":" + session + ":" + destination; }

    boolean sameOffer(DestinationWire other) {
        return other != null && identity().equals(other.identity()) && available == other.available
                && (address == null ? other.address == null : address.equals(other.address));
    }

    String request(String token, long baseline) {
        if (!DestinationState.token(token) || baseline < 0) { throw new IllegalArgumentException("challenge"); }
        return "schema::1\nproducer_epoch::" + epoch + "\nsession_gen::" + session
                + "\ndestination_gen::" + destination + "\nchallenge::" + token
                + "\nreply_baseline::" + baseline + "\n";
    }
}
