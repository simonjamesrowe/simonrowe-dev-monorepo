package com.simonrowe.narration;

/**
 * Exact playing time of an MP3 stream, by walking its MPEG audio frames.
 *
 * <p>Read-along timings are built from each sentence's own duration, so the duration has to be
 * what a player will actually play, not an estimate from the file size. Google's output is a
 * bare MPEG-2 Layer III stream (24 kHz, 32 kbps, 96-byte frames of 24 ms each) with no ID3
 * header. Summing every frame's samples reproduces ffprobe's duration to the millisecond on a
 * real narration (7186 frames, 172.464s).
 *
 * <p>A stream this cannot walk (an unexpected header, a truncated tail) falls back to the
 * byte-rate estimate {@link NarrationStorage} has always used, rather than failing: a slightly
 * wrong highlight is better than no audio.
 */
final class Mp3Frames {

  /** Bits per second {@link NarrationStorage} assumes when it has nothing better. */
  static final long FALLBACK_BITS_PER_SECOND = 32_000L;

  private static final int HEADER_BYTES = 4;
  private static final int ID3_HEADER_BYTES = 10;

  /** Layer III bitrates in kbps, by version family then index. Index 0 is "free", 15 is bad. */
  private static final int[] MPEG1_BITRATES = {
    0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
  private static final int[] MPEG2_BITRATES = {
    0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

  private static final int VERSION_MPEG1 = 3;
  private static final int VERSION_MPEG2 = 2;
  private static final int VERSION_MPEG25 = 0;
  private static final int LAYER_III = 1;

  private Mp3Frames() {
  }

  /**
   * The stream's playing time in seconds.
   *
   * @param audio an MP3 stream, optionally starting with an ID3v2 tag
   * @return the duration, from frames when the stream is walkable and from the byte rate
   *     otherwise
   */
  static double durationSeconds(final byte[] audio) {
    double fromFrames = walk(audio);
    if (fromFrames > 0) {
      return fromFrames;
    }
    return audio.length * 8.0 / FALLBACK_BITS_PER_SECOND;
  }

  /** Total seconds across every frame, or -1 when the stream does not parse end to end. */
  private static double walk(final byte[] audio) {
    int offset = id3Length(audio);
    double seconds = 0;
    int frames = 0;
    while (offset + HEADER_BYTES <= audio.length) {
      int b1 = audio[offset + 1] & 0xff;
      int b2 = audio[offset + 2] & 0xff;
      boolean sync = (audio[offset] & 0xff) == 0xff && (b1 & 0xe0) == 0xe0;
      int version = (b1 >> 3) & 0x3;
      int layer = (b1 >> 1) & 0x3;
      int bitrateIndex = b2 >> 4;
      int sampleRateIndex = (b2 >> 2) & 0x3;
      if (!sync || layer != LAYER_III || version == 1
          || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
        return -1;
      }
      boolean mpeg1 = version == VERSION_MPEG1;
      int bitrate = (mpeg1 ? MPEG1_BITRATES : MPEG2_BITRATES)[bitrateIndex] * 1000;
      int sampleRate = sampleRate(version, sampleRateIndex);
      int padding = (b2 >> 1) & 0x1;
      int length = (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding;
      int samples = mpeg1 ? 1152 : 576;
      seconds += (double) samples / sampleRate;
      frames++;
      offset += length;
    }
    // A final frame cut short still counts: everything before it walked cleanly.
    return frames > 0 && offset >= audio.length ? seconds : -1;
  }

  private static int sampleRate(final int version, final int index) {
    int base = switch (index) {
      case 0 -> 44_100;
      case 1 -> 48_000;
      default -> 32_000;
    };
    return switch (version) {
      case VERSION_MPEG2 -> base / 2;
      case VERSION_MPEG25 -> base / 4;
      default -> base;
    };
  }

  /** Bytes taken by a leading ID3v2 tag, whose size is stored as four 7-bit "syncsafe" bytes. */
  private static int id3Length(final byte[] audio) {
    if (audio.length < ID3_HEADER_BYTES
        || audio[0] != 'I' || audio[1] != 'D' || audio[2] != '3') {
      return 0;
    }
    int size = ((audio[6] & 0x7f) << 21) | ((audio[7] & 0x7f) << 14)
        | ((audio[8] & 0x7f) << 7) | (audio[9] & 0x7f);
    return ID3_HEADER_BYTES + size;
  }
}
