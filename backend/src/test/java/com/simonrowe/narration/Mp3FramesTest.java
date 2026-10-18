package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Mp3FramesTest {

  /** Google's frame: MPEG-2 Layer III, 32 kbps, 24 kHz, no padding. 96 bytes, 24 ms. */
  private static final byte[] GOOGLE_HEADER = {(byte) 0xff, (byte) 0xf3, 0x44, (byte) 0xc4};
  private static final int GOOGLE_FRAME_BYTES = 96;

  /**
   * A stream of {@code count} frames shaped like Google's output.
   *
   * @param count how many frames
   * @return the stream, 24 ms per frame
   */
  static byte[] googleFrames(final int count) {
    byte[] audio = new byte[count * GOOGLE_FRAME_BYTES];
    for (int frame = 0; frame < count; frame++) {
      System.arraycopy(GOOGLE_HEADER, 0, audio, frame * GOOGLE_FRAME_BYTES, 4);
    }
    return audio;
  }

  @Test
  void sumsTheSamplesOfEveryFrameInGooglesFormat() {
    assertThat(Mp3Frames.durationSeconds(googleFrames(7186))).isCloseTo(172.464, within(1e-9));
  }

  @Test
  void readsMpegOneFramesWithPadding() {
    // MPEG-1 Layer III, 128 kbps, 44.1 kHz, padded: 418 bytes, 1152 samples.
    byte[] frame = new byte[418];
    frame[0] = (byte) 0xff;
    frame[1] = (byte) 0xfb;
    frame[2] = (byte) 0x92;
    byte[] audio = new byte[frame.length * 3];
    for (int i = 0; i < 3; i++) {
      System.arraycopy(frame, 0, audio, i * frame.length, frame.length);
    }

    assertThat(Mp3Frames.durationSeconds(audio)).isCloseTo(3 * 1152 / 44_100.0, within(1e-9));
  }

  @Test
  void skipsLeadingId3Tag() {
    byte[] tag = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 6, 1, 2, 3, 4, 5, 6};
    byte[] frames = googleFrames(10);
    byte[] audio = Arrays.copyOf(tag, tag.length + frames.length);
    System.arraycopy(frames, 0, audio, tag.length, frames.length);

    assertThat(Mp3Frames.durationSeconds(audio)).isCloseTo(0.24, within(1e-9));
  }

  @Test
  void countsFinalFrameThatWasCutShort() {
    byte[] audio = Arrays.copyOf(googleFrames(5), 4 * GOOGLE_FRAME_BYTES + 10);

    assertThat(Mp3Frames.durationSeconds(audio)).isCloseTo(0.12, within(1e-9));
  }

  @Test
  void fallsBackToTheByteRateWhenTheStreamDoesNotWalk() {
    byte[] audio = googleFrames(10);
    audio[GOOGLE_FRAME_BYTES * 3] = 0x12;

    assertThat(Mp3Frames.durationSeconds(audio))
        .isCloseTo(audio.length * 8.0 / 32_000, within(1e-9));
  }
}
