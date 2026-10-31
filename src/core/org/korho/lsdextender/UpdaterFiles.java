/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;

final class UpdaterFiles {
    private UpdaterFiles() { }

    /** Why the updater cannot run, or null. */
    static String problem() {
        String directory = Config.directory();
        if (directory == null) { return "Project card directory is unavailable"; }
        if (InventoryConfig.selected(directory)) {
            String inventory = InventoryConfig.problem(directory);
            return inventory != null ? inventory : readableFile(workerPath(directory), "Update worker");
        }
        String config = agentConfig(directory);
        if (!new File(config).isAbsolute()) { return "Update agent configuration path must be absolute: " + config; }
        String problem = readableFile(config, "Update agent configuration");
        return problem != null ? problem : readableFile(workerPath(directory), "Update worker");
    }

    static String agentConfig(String directory) {
        if (InventoryConfig.selected(directory)) { return InventoryConfig.path(); }
        return Config.get("lsdextender.updates.agent.config", new File(directory, "hu-agent.json").getAbsolutePath());
    }

    static String workerPath(String directory) {
        return Config.get("lsdextender.updates.worker", directory + "/hu-agent");
    }

    private static String readableFile(String path, String label) {
        File file = new File(path);
        if (!file.isFile()) { return label + " is missing or not a regular file: " + path; }
        if (!file.canRead()) { return label + " is not readable: " + path; }
        return null;
    }
}
