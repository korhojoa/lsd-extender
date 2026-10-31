/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.LsdExtender;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.smartphoneintegration.common.DeviceASLExtended;
import de.vw.mib.asl.internal.smartphoneintegration.common.SmartPhoneIntegrationGlobalProperyAccessor;
import de.vw.mib.asl.internal.smartphoneintegration.persistence.SmartPhoneIntegrationPersistence;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;

/** Feed commands for saved SI devices; no connection or disconnection requests. */
final class SavedDeviceCommands implements CommandHandler {
    private static final int DEVICES = 0, BLACKLIST = 1, UNBLOCK = 2, FORGET = 3;
    private static final String HELP = "si devices | si blacklist | si unblock all"
        + " | si unblock address <exact-address> | si forget all | si forget address <exact-address>";

    public String command(String line) {
        if (!line.equals("si") && !line.startsWith("si ")) {
            return null;
        }
        String rest = line.substring(2).trim();
        if (rest.length() == 0 || rest.equals("help")) {
            return "ok " + HELP;
        }
        final int operation;
        final String address;
        if (rest.equals("devices") || rest.equals("blacklist")) {
            operation = rest.equals("devices") ? DEVICES : BLACKLIST;
            address = null;
        } else if (rest.equals("unblock all") || rest.equals("forget all")) {
            operation = rest.equals("unblock all") ? UNBLOCK : FORGET;
            address = null;
        } else if (rest.startsWith("unblock address ") || rest.startsWith("forget address ")) {
            operation = rest.startsWith("unblock ") ? UNBLOCK : FORGET;
            address = rest.substring(operation == UNBLOCK ? 16 : 15).trim();
            if (address.length() == 0 || address.indexOf('\n') >= 0 || address.indexOf('\r') >= 0) {
                return "err usage: " + HELP;
            }
        } else {
            return "err usage: " + HELP;
        }
        return submit(operation, address);
    }

    private static String submit(final int operation, final String address) {
        if (ServiceManager.aslThreadSwitch == null) {
            return "err si ASL thread not ready; nothing changed";
        }
        Call call = new Call() {
            String execute() throws Exception {
                HsmTarget target = LsdExtender.siTarget();
                if (target == null) {
                    return "err si target not ready; nothing changed";
                }
                SmartPhoneIntegrationPersistence persistence = target.getPersistence();
                if (operation == FORGET) {
                    return SavedDevices.forget(persistence, address);
                }
                SmartPhoneIntegrationGlobalProperyAccessor gp = SmartPhoneIntegrationGlobalProperyAccessor.getInstance();
                DeviceASLExtended[] detected = gp.getDsiDevicelistASLExtended();
                if (operation < UNBLOCK) {
                    return SavedDevices.list(persistence, detected, operation == BLACKLIST);
                }
                int count = detected == null ? 0 : detected.length;
                DeviceASLExtended[] live = new DeviceASLExtended[count + 2];
                if (count > 0) {
                    System.arraycopy(detected, 0, live, 0, count);
                }
                live[count] = gp.getCurrentConnectedDevice();
                live[count + 1] = gp.getCurrentSelectedDevice();
                return SavedDevices.unblock(persistence, live, address);
            }
        };
        try {
            ServiceManager.aslThreadSwitch.enqueue(call);
            return call.await(3000);
        } catch (Throwable failure) {
            call.cancel();
            return "err si could not complete command: " + failure;
        }
    }

    /** A timed-out queued command must not mutate preferences later. */
    abstract static class Call implements Runnable {
        private boolean started;
        private boolean cancelled;
        private boolean done;
        private String result;

        abstract String execute() throws Exception;

        public void run() {
            synchronized (this) {
                if (cancelled || started) {
                    return;
                }
                started = true;
            }
            String reply;
            try {
                reply = execute();
            } catch (Throwable failure) {
                reply = "err si command failed: " + failure;
            }
            synchronized (this) {
                result = reply;
                done = true;
                notifyAll();
            }
        }

        synchronized String await(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    if (!started) {
                        cancelled = true;
                        return "err si ASL queue timeout; nothing changed";
                    }
                    return "err si command still running; inspect si devices before retrying";
                }
                wait(remaining);
            }
            return result;
        }

        synchronized void cancel() {
            cancelled = true;
        }
    }
}
