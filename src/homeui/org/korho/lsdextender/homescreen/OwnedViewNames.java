/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

/** Native view caches key names, so each new owned provider needs a process-unique suffix. */
final class OwnedViewNames {
    private static final String COUNTER = "org.korho.lsdextender.homeui.viewSequence";
    private static final String PADDING = "00000000";
    private OwnedViewNames() { }
    static String allocate(String prefix) {
        if (prefix == null || !prefix.startsWith(OwnedSettingsPage.VIEW + ".")) {
            throw new IllegalArgumentException("Only owned view names may be allocated");
        }
        java.util.Properties properties = System.getProperties();
        synchronized (properties) {
            long previous = Long.parseLong(properties.getProperty(COUNTER, "0"));
            if (previous < 0 || previous >= 0xffffffffL) {
                throw new IllegalStateException("Owned view identity range exhausted");
            }
            long next = previous + 1;
            String suffix = Long.toHexString(next);
            properties.setProperty(COUNTER, Long.toString(next));
            // Native ViewHandlerCacheImpl.computeId reads only the last eight characters.
            return prefix + "." + PADDING.substring(suffix.length()) + suffix;
        }
    }
}
