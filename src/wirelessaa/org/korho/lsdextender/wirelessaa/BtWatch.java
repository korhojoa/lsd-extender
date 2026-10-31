/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.PfOpen;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.dsi.ifc.bluetooth.DSIBluetooth;
import org.dsi.ifc.bluetooth.DSIBluetoothListener;
import org.dsi.ifc.bluetooth.DiscoveredDevice;
import org.dsi.ifc.bluetooth.MasterRoleRequestStruct;
import org.dsi.ifc.bluetooth.PasskeyStateStruct;
import org.dsi.ifc.bluetooth.ReconnectInfo;
import org.dsi.ifc.bluetooth.RequestIncomingService;
import org.dsi.ifc.bluetooth.ServiceRequestStateStruct;
import org.dsi.ifc.bluetooth.TrustedDevice;

/**
 * Watches the Bluetooth DSI service from inside the HMI: subscribes to the trusted
 * device list (attr 6) and the service request state (attr 8). A trusted device
 * with any active service type is "the phone"; WirelessDevices then shows its row.
 *
 *   lsdextender.wireless.bt        true   watch at all
 *   lsdextender.wireless.bt_mask   0      service-type bits that count as connected
 *                                         (0 = any; e.g. 2 HFP, 32768 A2DP sink, 0x40000 PAN)
 */
public final class BtWatch implements MflListener, DSIBluetoothListener, Deferred {
    private static final int MAX_ATTEMPTS = 300;

    private boolean subscribed;
    private int attempts;

    static {
        try {
            WirelessSettingsService.start();
            WirelessLabelHook.start();
            BtWatch b = new BtWatch();
            LsdExtender.register(b);
            LsdExtender.defer(b);
            // Register the owned wireless hook for the core event dispatcher.
            // Core loads BtWatch on every feature reload, so this hook belongs
            // to the current generation's class loader. It does not replace
            // a stock handler or listener.
            LsdExtender.setWirelessHook(new WirelessHookImpl());
            startPfMonitor();
        } catch (Throwable ignored) {
            // fail open
        }
    }

    /** Core owns the complete ruleset and the only monitor, across jar reloads. */
    static void startPfMonitor() {
        try {
            if (Config.getBool("lsdextender.wireless.pf", true)) {
                PfOpen.keepOpen(Config.getInt("lsdextender.wireless.pf_port", 5288));
            }
        } catch (Throwable t) {
            LsdExtender.trace(" wireless pf: registration failed: " + t);
        }
    }

    private BtWatch() {
    }

    public String name() {
        return "wireless/bt [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    public boolean run() {
        if (subscribed) {
            return true;
        }
        if (!Config.getBool("lsdextender.wireless.bt", true)) {
            LsdExtender.trace(" wireless: bt watch disabled by config");
            return true;
        }
        attempts++;
        HsmTarget t = LsdExtender.siTarget();
        if (t == null) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            // subscribed from the service's registration callback (DsiSubscribe): a
            // setNotification made before the service is up is silently lost
            DsiSubscribe.whenRegistered("wireless/bt", DSIBluetooth.class,
                new int[]{(6), (8)}, this);
            subscribed = true;
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(" wireless: bt subscribe attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    public void updateTrustedDevices(TrustedDevice[] devs, int validFlag) {
        WirelessSettingsService.trusted(devs, validFlag == (1));
    }

    public void updateServiceRequestState(ServiceRequestStateStruct s, int validFlag) {
        if (validFlag != (1) || s == null) {
            return;
        }
        LsdExtender.trace(" wireless: bt service request state=" + s.btServiceRequestState
            + " " + s.btDeviceName + " " + s.btDeviceAddress);
    }

    public void deviceDisonnectionInfo(String s, String s1, int i) {
        LsdExtender.trace(" wireless: bt disconnection info " + s + " " + s1 + " " + i);
    }

    // the rest of DSIBluetoothListener: not subscribed, nothing to do
    public void responseAbortConnectService(int i) {
    }

    public void responseAbortInquiry(int i) {
    }

    public void responseAcceptIncomingServiceRequest(int i) {
    }

    public void responseConnectService(String s, String s1, int i, int j, int k) {
    }

    public void responseConnectServiceToInstance(String s, String s1, int i, int j, int k) {
    }

    public void responseDisconnectService(String s, int i, int j) {
    }

    public void responseGetServices(String s, String s1, int i, int j) {
    }

    public void responseInquiry(int i, int j) {
    }

    public void responsePasskeyResponse(String s, String s1, int i) {
    }

    public void responseRemoveAuthentication(String s, String s1, int i) {
    }

    public void responseRestoreFactorySettings(int i) {
    }

    public void responseSetA2DPUserSetting(int i) {
    }

    public void responseSetAccessibleMode(int i) {
    }

    public void responseSwitchBTState(int i) {
    }

    public void removeAuthenticationNoSupport(String s, String s1) {
    }

    public void updateAccessibleMode(int i, boolean flag, int j) {
    }

    public void updateBTState(int i, int j) {
    }

    public void updateDiscoveredDevices(DiscoveredDevice discovereddevice, int i) {
    }

    public void updateHUCandBTHSState(int i, int j) {
    }

    public void updateIncomingServiceRequest(RequestIncomingService requestincomingservice, int i) {
    }

    public void updateMasterRoleRequestError(MasterRoleRequestStruct masterrolerequeststruct, int i) {
    }

    public void updatePasskeyState(PasskeyStateStruct passkeystatestruct, int i) {
    }

    public void updateReconnectIndicator(ReconnectInfo reconnectinfo, int i) {
    }

    public void updateSupportedBTProfiles(int i, int j) {
    }

    public void updateUserFriendlyName(String s, int i) {
    }

    public void updateA2DPUserSetting(boolean flag, int i) {
    }

    public void updatePriorizedDeviceReconnect(boolean flag, String s, int i) {
    }

    public void serviceRejectNoSupport(String s, String s1) {
    }

    public void responseReconnectSuspend(int i) {
    }

    public void responseSetPriorizedDeviceReconnect(int i) {
    }

    public void asyncException(int i, String s, int j) {
        LsdExtender.trace(" wireless: bt asyncException(" + i + ", " + s + ", " + j + ")");
    }
}
