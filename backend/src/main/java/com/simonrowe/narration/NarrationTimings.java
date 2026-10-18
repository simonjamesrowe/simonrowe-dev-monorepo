package com.simonrowe.narration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * When each word of a narration is spoken, for the player's read-along highlight.
 *
 * <p>Chirp 3 HD voices support neither SSML {@code <mark>} nor timepointing, so Google cannot say
 * when a word is spoken. What it can be made to say is when a <em>sentence</em> is: each sentence
 * is synthesised on its own, and its audio's exact length (see {@link Mp3Frames}) places it on
 * the timeline. Words are then spread across their sentence by length. Measured against Whisper
 * word timestamps on a real summary, that puts the median word 0.14s from where it is actually
 * spoken; spreading words across the whole narration instead was 1.5s out, four or five words.
 *
 * <p>A word's share of its sentence is its letter count plus a constant, with extra for a digit
 * (said as a word: "10–20%" is "ten to twenty percent") and for a comma's pause. Checked against
 * Whisper on a real narration made this way: the median word is 0.19s out, nine in ten within
 * 0.42s, with no lean early or late. Syllable counts did worse than letters.
 *
 * <p>Every clip Google returns starts with about 0.4s of silence and ends with about 0.2s
 * (measured 0.35–0.47s and 0.19–0.23s). Those are taken off each sentence before its words are
 * placed, clamped so a very short clip still has room for speech.
 *
 * @param version the file format, for the frontend
 * @param words one entry per whitespace-separated word of the script, in order
 */
public record NarrationTimings(int version, List<Word> words) {

  static final int FORMAT_VERSION = 1;

  private static final double LEAD_IN_SECONDS = 0.4;
  private static final double TAIL_SECONDS = 0.2;
  /** Neither the lead-in nor the tail may take more than this share of a clip. */
  private static final double MAX_SILENCE_SHARE = 0.25;
  /** Extra weight for a word followed by a comma-like pause, in letter-equivalents. */
  private static final double PAUSE_WEIGHT = 6;
  /** Extra weight per digit, on top of counting it as a letter: digits are said as words. */
  private static final double DIGIT_WEIGHT = 3;
  /** Weight every word carries regardless of length: even "a" takes time to say. */
  private static final double BASE_WEIGHT = 1.5;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}\\p{N}]");
  private static final Pattern NOT_A_DIGIT = Pattern.compile("\\D");
  private static final Pattern CLAUSE_END = Pattern.compile("[,;:—–]$");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * One word and the moment it starts.
   *
   * @param w the word as it appears in the script, punctuation included
   * @param s seconds from the start of the narration, to the hundredth
   * @param n which synthesised segment (roughly, sentence) it belongs to
   */
  public record Word(String w, double s, int n) {
  }

  /**
   * Places every word of every segment on the narration's timeline.
   *
   * @param segments the script as it was synthesised, one entry per request
   * @param durations each segment's audio length in seconds, in the same order
   * @return the timings
   */
  public static NarrationTimings of(final List<String> segments, final List<Double> durations) {
    if (segments.size() != durations.size()) {
      throw new IllegalArgumentException("Every segment needs a duration");
    }
    List<Word> words = new ArrayList<>();
    double offset = 0;
    for (int index = 0; index < segments.size(); index++) {
      double duration = durations.get(index);
      String[] tokens = WHITESPACE.split(segments.get(index).trim());
      double leadIn = Math.min(LEAD_IN_SECONDS, duration * MAX_SILENCE_SHARE);
      double tail = Math.min(TAIL_SECONDS, duration * MAX_SILENCE_SHARE);
      double speech = Math.max(0, duration - leadIn - tail);

      double[] weights = new double[tokens.length];
      double total = 0;
      for (int i = 0; i < tokens.length; i++) {
        weights[i] = weight(tokens[i]);
        total += weights[i];
      }
      double elapsed = 0;
      for (int i = 0; i < tokens.length; i++) {
        if (tokens[i].isEmpty()) {
          continue;
        }
        double start = offset + leadIn + (total == 0 ? 0 : speech * elapsed / total);
        words.add(new Word(tokens[i], Math.round(start * 100) / 100.0, index));
        elapsed += weights[i];
      }
      offset += duration;
    }
    return new NarrationTimings(FORMAT_VERSION, List.copyOf(words));
  }

  /**
   * The timings as the JSON file the player fetches.
   *
   * @return UTF-8 JSON bytes
   */
  public byte[] toJson() {
    return JSON.writeValueAsBytes(this);
  }

  private static double weight(final String token) {
    double weight = NOT_A_LETTER.matcher(token).replaceAll("").length() + BASE_WEIGHT
        + DIGIT_WEIGHT * NOT_A_DIGIT.matcher(token).replaceAll("").length();
    if (CLAUSE_END.matcher(token).find()) {
      weight += PAUSE_WEIGHT;
    }
    return weight;
  }
}
