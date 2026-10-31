/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aasettings;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.Slog;

/** Reloadable entry point for persistent CarPlay configuration. */
public final class CarPlaySettingsService {
    private CarPlaySettingsService() { }
    static {
        try {
            ProjectionSettingsService s = ProjectionSettingsService.start(Config.directory(),
                Config.get("lsdextender.carplay.config", "auto"), true);
            LsdExtender.register(s); LsdExtender.registerCommands(s);
        } catch (Throwable t) { Slog.write(" carplay-settings start failed: " + t); }
    }
}
