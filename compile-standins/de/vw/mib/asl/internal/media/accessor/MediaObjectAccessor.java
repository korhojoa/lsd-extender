/* Copyright (C) 2026 korhojoa */
// SPDX-License-Identifier: GPL-3.0-only
// Compile-only declaration. Do not install this class.
package de.vw.mib.asl.internal.media.accessor;
public class MediaObjectAccessor {
 public static de.vw.mib.asl.internal.media.controller.MediaController mMediaController = null;
 private MediaObjectAccessor() { super(); throw new UnsupportedOperationException(); }
 public static de.vw.mib.asl.internal.media.sound.MediaAudioManager getAudioManager() { throw new UnsupportedOperationException(); }
}
