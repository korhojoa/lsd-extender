/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.boot;

import de.vw.mib.extstartup.spi.ExtendedStartupSPI;
import de.vw.mib.extstartup.spi.StartupResumeListener;

/**
 * Entry point through the extended startup SPI, without replacing stock classes.
 * The launcher names this class with
 * -DextendedStartupSPI.class=org.korho.lsdextender.boot.StartupHook; the HMI's
 * MIBMain then loads it with Class.forName and newInstance on its main thread,
 * before any bundle starts.
 *
 * The answers are the same as those of the firmware's own unused implementation
 * (every boolean false, every void method does nothing), so MIBOsgiImpl takes the
 * normal start path: isSnapshotBoot() false means performBundlePreloads() (an
 * empty list on every firmware checked) and then the autostart bundles.
 *
 * MIBMain only catches ClassNotFound, IllegalAccess and Instantiation exceptions
 * around newInstance(), so nothing here may throw: every call into core is
 * wrapped in catch (Throwable). This class names only the SPI types and
 * HookInstaller; HookInstaller is resolved inside the try blocks.
 */
public final class StartupHook implements ExtendedStartupSPI {
    public StartupHook() {
        try {
            HookInstaller.boot();
        } catch (Throwable t) {
            // Do not turn an extension linkage/start failure into caller I/O.
        }
    }

    public void addBootResumeListener(StartupResumeListener listener) {
        // no snapshot boot, so never a resume to report
    }

    public boolean isResumeFromSnapshotBoot() {
        return false;
    }

    /**
     * MIBOsgiImpl.startAutostartBundles() calls this once, after the SPI bundles
     * and immediately before the autostart bundles. It performs no activation;
     * the owned worker installs our listener only after ASL admission.
     * Always false: the stock start sequence.
     */
    public boolean isSnapshotBoot() {
        try {
            HookInstaller.beforeAutostart();
        } catch (Throwable t) {
            // The normal stock answer below always wins.
        }
        return false;
    }

    public boolean providesExtendedBootSupport() {
        return false;
    }

    public void readyToMakeSnaphot() {
        // only called on the snapshot path, which this class never selects
    }

    public void setSnapshotCorrupted() {
        // only called on the snapshot path, which this class never selects
    }

}
