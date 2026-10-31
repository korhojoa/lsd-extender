/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

/** Readable installed and offered versions; hashes remain diagnostic inputs. */
final class UpdaterVersionText {
    private static final String[][] COMPONENTS = {
        { "lsd-extender.jar", "Extender core" }, { "lsd-extender-homeui.jar", "Extender settings" },
        { "lsd-extender-aasettings.jar", "Projection settings" }, { "lsd-extender-wirelessaa.jar", "Wireless Android Auto" },
        { "hu-agent", "Updater" }, { "hu-agent-boot-guard", "Updater startup guard" },
        { "hu-agent-recover.sh", "Updater recovery" }, { "lsd-extender-kombisync.jar", "Cluster synchronization" },
        { "lsd-extender-dsispy.jar", "System diagnostics" }, { "lsd-extender-keys.jar", "Key controls" },
        { "lsd-extender-ev.jar", "EV settings" }, { "lsd-extender-aa-guidance.jar", "Projection guidance" },
        { "lsd-extender-audio.jar", "Audio controls" }, { "lsd-extender-native-destination.jar", "Navigation destination" },
        { "lsd-extender-vehicle-probe.jar", "Vehicle information" }, { "lsd-extender-harmless.jar", "Runtime compatibility" },
        { "libgal_btshim.so", "Bluetooth integration" }, { "android-auto-settings", "Android Auto settings helper" },
        { "carplay-settings", "CarPlay settings helper" }, { "devu-vaa.so", "Virtual USB driver" }
    };
    private UpdaterVersionText() { }
    static String format(String record) {
        int first = record.indexOf('~');
        if (first < 0) { return componentText(record) + ": Version unavailable (Status unavailable)"; }
        String component = componentText(record.substring(0, first));
        int second = record.indexOf('~', first + 1), third = second < 0 ? -1 : record.indexOf('~', second + 1);
        if (third < 0) {
            String installed = record.substring(first + 1, second < 0 ? record.length() : second);
            return component + ": " + versionLabel(installed) + " (Status unavailable)";
        }
        String installed = versionLabel(record.substring(first + 1, second));
        String offered = versionLabel(record.substring(second + 1, third));
        String state = record.substring(third + 1);
        String result = component + ": " + installed;
        if (state.length() != 0 && !"Current".equals(state) && !installed.equals(offered)
            && !"Version unavailable".equals(offered) && !"Not checked".equals(offered)) {
            result += " -> " + offered;
        }
        return result + " (" + (state.length() == 0 ? "Status unavailable" : state) + ")";
    }
    private static String componentText(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        for (int i = 0; i < COMPONENTS.length; i++) {
            if (COMPONENTS[i][0].equals(file)) { return COMPONENTS[i][1]; }
        }
        return path;
    }
    private static String versionLabel(String value) {
        if (value.length() == 0 || hexadecimalToken(value) || value.startsWith("Build ") && hexadecimalToken(value.substring(6))) {
            return "Version unavailable";
        }
        int space = value.lastIndexOf(' ');
        return space > 0 && hexadecimalToken(value.substring(space + 1)) ? value.substring(0, space) : value;
    }
    private static boolean hexadecimalToken(String value) {
        if (value.length() == 0) { return false; }
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f' || digit >= 'A' && digit <= 'F')) { return false; }
        }
        return true;
    }
}
