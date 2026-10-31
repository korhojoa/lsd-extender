/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import de.vw.mib.asl.framework.api.persistence.PersistenceInputStream;
import de.vw.mib.asl.internal.smartphoneintegration.common.DeviceASLExtended;
import de.vw.mib.asl.internal.smartphoneintegration.persistence.SmartPhoneIntegrationPersistence;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

final class SavedDevices {
    private static final Method IS_BLACKLISTED = method(new String[]{"isBlacklisted", "isIsblacklisted"}, new Class[0]);
    private static final Method SET_BLACKLISTED = method(new String[]{"setBlacklisted", "setIsblacklisted"},
        new Class[]{Boolean.TYPE});
    private static final Method LAST_METHOD = method(new String[]{"getLastConnectionMethod", "getLastconnectionmode"},
        new Class[0]);

    private SavedDevices() {
    }

    /** The first of names that DeviceASLExtended has with these parameters, or null. */
    static Method method(String[] names, Class[] params) {
        for (int i = 0; i < names.length; i++) {
            try {
                return DeviceASLExtended.class.getMethod(names[i], params);
            } catch (NoSuchMethodException e) {
                // the next name
            }
        }
        return null;
    }

    private static Object call(Method m, DeviceASLExtended device, Object[] args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(device, args);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isBlacklisted(DeviceASLExtended device) {
        return Boolean.TRUE.equals(call(IS_BLACKLISTED, device, new Object[0]));
    }

    /** False when this firmware has no setter. */
    static boolean setBlacklisted(DeviceASLExtended device, boolean value) {
        if (SET_BLACKLISTED == null) {
            return false;
        }
        call(SET_BLACKLISTED, device, new Object[]{value ? Boolean.TRUE : Boolean.FALSE});
        return true;
    }

    static int lastConnectionMethod(DeviceASLExtended device) {
        Object v = call(LAST_METHOD, device, new Object[0]);
        return v instanceof Integer ? ((Integer) v).intValue() : -1;
    }

    static String list(SmartPhoneIntegrationPersistence persistence,
            DeviceASLExtended[] live, boolean blacklistOnly) {
        DeviceASLExtended[] saved = persistence.getLastConnectedDevicesList();
        StringBuffer rows = new StringBuffer();
        int count = 0;
        for (int i = 0; i < saved.length; i++) {
            DeviceASLExtended device = saved[i];
            if (!valid(device) || (blacklistOnly && !isBlacklisted(device))) {
                continue;
            }
            DeviceASLExtended present = find(live, device.getDeviceAddress());
            rows.append("; address=").append(quote(device.getDeviceAddress()))
                .append(" name=").append(quote(present == null ? device.getDeviceName() : present.getDeviceName()))
                .append(" last_method=").append(lastConnectionMethod(device))
                .append(" blacklisted=").append(isBlacklisted(device))
                .append(" present=").append(present != null);
            count++;
        }
        return "ok si " + (blacklistOnly ? "blacklist" : "devices")
            + " count=" + count + " capacity=" + saved.length + rows.toString();
    }

    /** null address means all saved devices; an address is matched exactly. */
    static String unblock(SmartPhoneIntegrationPersistence persistence,
            DeviceASLExtended[] live, String address) {
        DeviceASLExtended[] saved = persistence.getLastConnectedDevicesList();
        int matched = 0;
        int changed = 0;
        int liveChanged = 0;
        for (int i = 0; i < saved.length; i++) {
            DeviceASLExtended device = saved[i];
            if (!matches(device, address)) {
                continue;
            }
            matched++;
            if (isBlacklisted(device) && setBlacklisted(device, false)) {
                changed++;
            }
            // Saved and live objects can be different, or aliases. Clear both so
            // a later STARTED cannot save an old live blacklist flag again.
            for (int j = 0; live != null && j < live.length; j++) {
                if (matches(live[j], device.getDeviceAddress()) && isBlacklisted(live[j])
                        && setBlacklisted(live[j], false)) {
                    liveChanged++;
                }
            }
        }
        if (address != null && matched == 0) {
            return "err si saved address not found: " + quote(address);
        }
        if (changed > 0) {
            // Do not use updateLastConnectedDevices here: it would reorder the
            // user's connection preferences. The objects are already in the list.
            persistence.markDirty(true);
        }
        return "ok si unblock matched=" + matched + " changed=" + changed
            + " live_changed=" + liveChanged + " save=" + (changed > 0 ? "requested" : "unchanged");
    }

    static String forget(SmartPhoneIntegrationPersistence persistence, String address) throws IOException {
        DeviceASLExtended[] saved = persistence.getLastConnectedDevicesList();
        // There is no public removal API. Use the stock deserializer, without
        // reflection or clear() (which also resets the initial-technology setting).
        // Refuse other layouts rather than guessing their persistence format.
        if (persistence.getVersion() != 6 || persistence.getNamespace() != 5028
                || persistence.getKey() != 200L || saved.length != 10) {
            return "err si unsupported saved-device persistence format version=" + persistence.getVersion()
                + " namespace=" + persistence.getNamespace() + " key=" + persistence.getKey()
                + " capacity=" + saved.length + "; nothing changed";
        }
        DeviceASLExtended[] kept = new DeviceASLExtended[saved.length];
        int count = 0;
        int removed = 0;
        for (int i = 0; i < saved.length; i++) {
            if (matches(saved[i], address)) {
                removed++;
            } else if (valid(saved[i])) {
                kept[count++] = saved[i];
            }
        }
        if (removed == 0) {
            return address == null ? "ok si forget removed=0 save=unchanged"
                : "err si saved address not found: " + quote(address);
        }
        byte[] bytes = encode(kept, persistence.getInitialConnectionType());
        persistence.fromStream(new SnapshotInput(bytes));
        persistence.markDirty(true);
        return "ok si forget removed=" + removed + " remaining=" + count + " save=requested";
    }

    /** Version-6 layout, including all ten slots and the independent preference. */
    static byte[] encode(DeviceASLExtended[] entries, int initialConnectionType) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(6);
        for (int i = 0; i < 10; i++) {
            DeviceASLExtended device = i < entries.length ? entries[i] : null;
            String address = valid(device) ? device.getDeviceAddress() : "empty";
            byte[] utf8 = address.getBytes("UTF-8");
            out.writeInt(utf8.length);
            out.write(utf8);
            out.writeInt(valid(device) ? device.getConnectionMethod() : 0);
            out.writeInt(valid(device) ? lastConnectionMethod(device) : 0);
            out.writeBoolean(valid(device) && isBlacklisted(device));
        }
        out.writeInt(initialConnectionType);
        out.flush();
        return bytes.toByteArray();
    }

    static final class SnapshotInput extends DataInputStream implements PersistenceInputStream {
        SnapshotInput(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        public boolean isValid() {
            return true;
        }

        public void matchVersion(short expected) throws IOException {
            if (readShort() != expected) {
                throw new IOException("Unexpected persistence version");
            }
        }

        public String readString() throws IOException {
            int size = readInt();
            if (size < 0 || size > available()) {
                throw new IOException("Invalid persistence string length");
            }
            byte[] bytes = new byte[size];
            readFully(bytes);
            return new String(bytes, "UTF-8");
        }
    }

    private static boolean valid(DeviceASLExtended device) {
        if (device == null || device.getDsiDevice() == null) {
            return false;
        }
        String address = device.getDeviceAddress();
        return address != null && address.length() > 0 && !address.equals("empty");
    }

    private static boolean matches(DeviceASLExtended device, String address) {
        return valid(device) && (address == null || address.equals(device.getDeviceAddress()));
    }

    private static DeviceASLExtended find(DeviceASLExtended[] devices, String address) {
        for (int i = 0; devices != null && i < devices.length; i++) {
            if (matches(devices[i], address)) {
                return devices[i];
            }
        }
        return null;
    }

    private static String quote(String text) {
        if (text == null) {
            text = "";
        }
        StringBuffer out = new StringBuffer("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                case '"':
                    out.append('\\').append(c);
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.append('"').toString();
    }
}
