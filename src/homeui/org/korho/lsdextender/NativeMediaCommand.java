/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.internal.media.common.ResponseHandler;
import de.vw.mib.asl.internal.media.controller.commands.AbstractCommand;

/** Serialize native acquisition with the stock media audio commands. */
public final class NativeMediaCommand extends AbstractCommand {
    private static final int ACQUIRE = 1;
    public interface Audio {
        boolean closed();
        void acquire(ResponseHandler response);
        void fade(ResponseHandler response);
        void finished(boolean success);
    }

    private final Audio audio;

    public NativeMediaCommand(Audio audio) {
        // Component 4 is SOUND, as used by CmdRequestDummyAudioConnection.
        super(4, ACQUIRE, 2, 15000);
        this.audio = audio;
    }

    protected void execute(int step) {
        if (audio.closed()) { abort("native media session closed"); return; }
        if (step == ACQUIRE) { audio.acquire(NEXT_STEP); }
        else { audio.fade(NEXT_STEP); }
    }

    public void onExit() { audio.finished(isCompleted()); }
    protected String getParameterStr() { return "connection=20"; }
    protected String getStepStr(int step) { return step == ACQUIRE ? "ACQUIRE" : "FADE"; }
}
