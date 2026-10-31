/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

import java.util.StringTokenizer;

/** Exact feed grammar; no arbitrary service or request selector. */
final class ProbeCommands {
    private static final String PREFIX = "vehicle-probe";

    private ProbeCommands() {
    }

    static String command(ComingHomeProbe probe, String line) {
        StringTokenizer words = new StringTokenizer(line);
        if (!words.hasMoreTokens() || !PREFIX.equals(words.nextToken())) { return null; }
        int count = words.countTokens();
        if (count == 1 && "status".equals(words.nextToken())) { return probe.status(); }
        if (count < 2 || !"coming-home".equals(words.nextToken())) { return usage(); }
        String action = words.nextToken();
        if (count == 2 && "restore".equals(action)) { return probe.restore(); }
        if (count == 5 && "set".equals(action)) {
            try {
                int requested = Integer.parseInt(words.nextToken());
                if (!"if-current".equals(words.nextToken())) { return usage(); }
                return probe.set(requested, Integer.parseInt(words.nextToken()));
            } catch (NumberFormatException invalid) {
                return usage();
            }
        }
        return usage();
    }

    private static String usage() { return "err usage: vehicle-probe status | coming-home set <duration> if-current <old> | coming-home restore"; }

}
