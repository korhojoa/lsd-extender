/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** A detached snapshot: no references to objects loaded from a feature jar. */
public final class WirelessSettingsState {
    public final boolean ready;
    public final boolean enabled;
    public final boolean busy;
    public final String status;
    private final String[] addresses;
    private final String[] names;
    private final boolean[] phonePermissions;
    private final boolean[] phoneConnections;

    public WirelessSettingsState(boolean ready, boolean enabled, boolean busy, String status,
            String[] addresses, String[] names, boolean[] phonePermissions, boolean[] phoneConnections) {
        this.ready = ready;
        this.enabled = enabled;
        this.busy = busy;
        this.status = status;
        this.addresses = (String[])addresses.clone();
        this.names = (String[])names.clone();
        this.phonePermissions = (boolean[])phonePermissions.clone();
        this.phoneConnections = (boolean[])phoneConnections.clone();
    }

    public int size() { return addresses.length; }
    public String address(int i) { return addresses[i]; }
    public String name(int i) { return names[i]; }
    public boolean allowed(int i) { return phonePermissions[i]; }
    public boolean connected(int i) { return phoneConnections[i]; }
}
