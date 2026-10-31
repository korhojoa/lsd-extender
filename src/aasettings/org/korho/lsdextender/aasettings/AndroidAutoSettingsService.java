/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aasettings;

import java.io.File;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.Slog;

/** Reloadable entry point for the independent Android Auto settings worker. */
public final class AndroidAutoSettingsService {
    private AndroidAutoSettingsService() { }
    static {
        try {
            String base = Config.get("lsdextender.androidauto.config", "/fs/sda0/gal-extender/gal_loader.conf");
            if (validBase(base)) {
                ProjectionSettingsService s = ProjectionSettingsService.startAndroidAuto(Config.directory(), base);
                LsdExtender.register(s); LsdExtender.registerCommands(s);
            } else { Slog.write(" aa-settings unavailable: GAL config missing at " + base); }
        } catch (Throwable t) { Slog.write(" aa-settings start failed: " + t); }
    }
    static boolean validBase(String base) { return base != null && new File(base).isAbsolute() && new File(base).isFile(); }
}
