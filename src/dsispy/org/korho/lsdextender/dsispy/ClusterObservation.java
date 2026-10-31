/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.dsispy;

/** Pure last-observation state. Protocol success is never a picture observation. */
public final class ClusterObservation {
    private static final String UNKNOWN = "UNKNOWN";
    private static final int NATIVE_TAP_COUNT = 3, FLAGS_VALIDITY_INDEX = 5, RECORD_CHARS = 188;
    private static final String[] NATIVE = { "kombi_tap", "komo_tap", "dm_tap", "kombi_internal", "kombi_flags", "kombi_flags_validity", "kombi_context",
        "komo_enabled", "komo_enabled_validity", "komo_visible", "komo_visible_validity",
        "komo_type", "komo_type_validity", "dm_context", "dm_display", "dm_raw_arg2" };
    private static final String[][] PPS_FIELDS = {
        { "gfx_state", "sync_needed", "data_rate" },
        { "version", "up_s", "frames", "frames_delta", "fps_x10", "idr", "nat_idr", "kbps",
          "wq_cur", "wq_peak", "wq_drops", "werr", "wshort", "cb_trips", "iobad", "oobrx" },
        { "taking", "streaming", "streaming_ms" }
    };
    private final String[] nativeValues = new String[NATIVE.length];
    private final String[][] ppsValues = new String[PPS_FIELDS.length][];
    private final String[] stamps = new String[PPS_FIELDS.length];
    private String previous;
    private final String[] previousGroups = new String[7];
    private long sequence;

    public ClusterObservation() {
        for (int i = 0; i < NATIVE.length; i++) { nativeValues[i] = UNKNOWN; }
        for (int i = 0; i < PPS_FIELDS.length; i++) {
            ppsValues[i] = new String[PPS_FIELDS[i].length]; stamps[i] = UNKNOWN;
            for (int j = 0; j < PPS_FIELDS[i].length; j++) { ppsValues[i][j] = UNKNOWN; }
        }
    }

    synchronized void nativeValue(String key, String value) {
        for (int i = 0; i < NATIVE.length; i++) {
            if (NATIVE[i].equals(key)) { nativeValues[i] = nativeToken(i, value); return; }
        }
    }

    private static String nativeToken(int index, String value) {
        if (value == null) { return UNKNOWN; }
        if (index < NATIVE_TAP_COUNT) { return "interceptor_registered".equals(value) ? value : UNKNOWN; }
        if (index == FLAGS_VALIDITY_INDEX) {
            return "not_marked_invalid".equals(value) || "ALL_FLAGS_INVALID".equals(value) ? value : UNKNOWN;
        }
        if (index == 7 || index == 9) {
            return "true".equals(value) || "false".equals(value) ? value : UNKNOWN;
        }
        if (index == 14 && !"4".equals(value)) { return UNKNOWN; }
        if (value.length() == 0 || value.length() > 11) { return UNKNOWN; }
        try { return Integer.toString(Integer.parseInt(value)).equals(value) ? value : UNKNOWN; }
        catch (NumberFormatException invalid) { return UNKNOWN; }
    }

    /** Only bounded complete numeric snapshots are accepted; ambiguity stays unknown. */
    synchronized void pps(int source, String text) {
        for (int i = 0; i < PPS_FIELDS[source].length; i++) {
            ppsValues[source][i] = attribute(text, PPS_FIELDS[source][i]);
        }
        stamps[source] = attribute(text, "updated_ms");
        if (source == 1 && !"84".equals(ppsValues[1][0])) {
            for (int i = 1; i < ppsValues[1].length; i++) { ppsValues[1][i] = UNKNOWN; }
        }
    }

    private static String attribute(String text, String key) {
        if (text == null || text.length() >= 8192 || !text.endsWith("\n")) { return UNKNOWN; }
        String found = null;
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start); if (end < 0) { end = text.length(); }
            String line = text.substring(start, end); start = end + 1;
            if (line.endsWith("\r")) { line = line.substring(0, line.length() - 1); }
            if (line.startsWith(key + ":")) {
                if (found != null || !line.startsWith(key + "::")) { return UNKNOWN; }
                found = line.substring(key.length() + 2);
                try {
                    if (found.length() == 0 || found.length() > 19 || Long.parseLong(found) < 0) { return UNKNOWN; }
                    for (int i = 0; i < found.length(); i++) {
                        if (found.charAt(i) < '0' || found.charAt(i) > '9') { return UNKNOWN; }
                    }
                } catch (NumberFormatException invalid) { return UNKNOWN; }
            }
        }
        return found == null ? UNKNOWN : found;
    }

    /** Source heartbeat timestamps alone are not semantic changes or freshness proof. */
    public synchronized String changed(long wallMillis, long localMillis) {
        return changed(wallMillis, localMillis, -1L);
    }

    synchronized String changed(long wallMillis, long localMillis, long timerMillis) {
        String[] pair = changedSample(wallMillis, localMillis, timerMillis);
        return pair == null ? null : pair[1];
    }

    /** Same-sample physical summary and detailed best-effort feed record. */
    synchronized String[] changedSample(long wallMillis, long localMillis, long timerMillis) {
        return snapshot(wallMillis, localMillis, timerMillis, false);
    }

    /** An interval snapshot is evidence of sampling, never a semantic change. */
    synchronized String[] snapshot(long wallMillis, long localMillis, long timerMillis, boolean force) {
        StringBuffer[] groups = new StringBuffer[7];
        for (int i = 0; i < groups.length; i++) { groups[i] = new StringBuffer(); }
        StringBuffer body = new StringBuffer("picture=UNKNOWN cause=UNPROVED native_lifetime=UNTRACKED");
        for (int i = 0; i < NATIVE.length; i++) {
            body.append(' ').append(NATIVE[i]).append("_last=").append(nativeValues[i]);
            int group = i < 3 ? 3 : i < 7 ? 0 : i < 13 ? 1 : 2;
            groups[group].append(' ').append(nativeValues[i]);
        }
        for (int i = 0; i < PPS_FIELDS.length; i++) {
            for (int j = 0; j < PPS_FIELDS[i].length; j++) {
                body.append(' ').append(i == 0 ? "sink_" : i == 1 ? "transport_" : "takeover_")
                    .append(PPS_FIELDS[i][j]).append('=').append(ppsValues[i][j]);
                groups[4 + i].append(' ').append(ppsValues[i][j]);
            }
        }
        body.append(" bytes_written=UNKNOWN first_idr_ms=UNKNOWN decoded_frames=UNKNOWN");
        String semantic = body.toString();
        if (!force && semantic.equals(previous)) { return null; }
        previous = semantic;
        int mask = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i].toString();
            if (!group.equals(previousGroups[i])) { mask |= 1 << i; }
            previousGroups[i] = group;
        }
        sequence = sequence == Long.MAX_VALUE ? 0L : sequence + 1L;
        String detail = "cluster-observer seq=" + sequence + " wall_ms=" + wallMillis + " observer_wall_delta_ms=" + localMillis + " " + semantic
            + " observer_hmi_timer_ms=" + (timerMillis < 0 ? UNKNOWN : "" + timerMillis)
            + " sink_updated_wall_ms=" + stamps[0] + " transport_updated_qnx_mono_ms=" + stamps[1]
            + " takeover_updated_ms=" + stamps[2] + " source_age_ms=UNKNOWN clock_equivalence=UNPROVED";
        StringBuffer summary = new StringBuffer("cluster-observer s=");
        summary.append(sequence).append(" c=").append(Integer.toHexString(mask))
            .append(" p=? life=? taps=");
        for (int i = 0; i < 3; i++) { summary.append(UNKNOWN.equals(nativeValues[i]) ? '?' : 'R'); }
        summary.append(" K=").append(number(3)).append(',').append(number(4)).append(',').append(number(6));
        summary.append(" O=").append(flag(7)).append(',').append(number(8)).append(',')
            .append(flag(9)).append(',').append(number(10)).append(',').append(number(11)).append(',').append(number(12));
        summary.append(" D=").append(number(13)).append(',').append(number(14)).append(',').append(number(15));
        String[] values = physicalValues();
        String[] pair = new String[3 + values.length];
        pair[0] = summary.toString(); pair[1] = detail;
        pair[2] = "cluster-sample s=" + sequence + " hmi_ms=" + (timerMillis < 0 ? UNKNOWN : "" + timerMillis)
            + " wall_ms=" + wallMillis + " c=" + Integer.toHexString(mask);
        System.arraycopy(values, 0, pair, 3, values.length);
        return pair;
    }

    /** Numbered value slices remain native-log evidence if the optional feed is absent. */
    private String[] physicalValues() {
        String[] records = new String[32];
        int used = 0;
        for (int source = 0; source < ppsValues.length; source++) {
            int offset = 0;
            while (offset < ppsValues[source].length) {
                String header = "cluster-values s=" + sequence + " src=" + source
                    + " off=" + offset + " stamp=" + stamps[source] + " v=";
                String values = "";
                int start = offset;
                while (offset < ppsValues[source].length) {
                    String next = values + (offset == start ? "" : ",") + ppsValues[source][offset];
                    if (header.length() + next.length() > RECORD_CHARS) { break; }
                    values = next; offset++;
                }
                if (offset == start) { throw new IllegalStateException("observer value exceeds record bound"); }
                records[used++] = header + values;
            }
        }
        String[] result = new String[used];
        System.arraycopy(records, 0, result, 0, used);
        return result;
    }

    private String number(int index) { return UNKNOWN.equals(nativeValues[index]) ? "?" : nativeValues[index]; }
    private String flag(int index) {
        return "true".equals(nativeValues[index]) ? "1" : "false".equals(nativeValues[index]) ? "0" : "?";
    }
}
