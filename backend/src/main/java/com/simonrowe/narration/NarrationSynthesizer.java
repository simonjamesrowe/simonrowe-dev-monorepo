package com.simonrowe.narration;

import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import io.opentelemetry.context.Context;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synthesises a script one sentence at a time and joins the audio, recording where each
 * sentence lands so the player can highlight the word being spoken.
 *
 * <p>Plain byte concatenation is sound: Google returns a bare MPEG frame stream with no ID3
 * header, every request uses identical voice and audio settings, and the first frame of each
 * clip borrows nothing from the bit reservoir, so the frames simply continue.
 *
 * <p><b>The cost, accepted:</b> every clip carries Google's own lead-in and tail silence, so
 * each join is a pause of about 0.6s. On a measured summary the longest pause between sentences
 * went from 0.61s to 1.13s and the narration from 172s to 188s. The silence cannot be cut out:
 * MP3 frames borrow data from earlier frames, and in Google's output no frame after the first
 * starts clean, so dropping the lead-in would play noise. The tail could be trimmed safely but
 * only saves about 0.1s per join. {@link NarrationScriptChunker#MIN_SEGMENT_CHARS} keeps the
 * number of joins down instead.
 */
@Component
public class NarrationSynthesizer {

  private static final Logger LOG = LoggerFactory.getLogger(NarrationSynthesizer.class);
  private static final long RETRY_BACKOFF_MILLIS = 1000;

  private final NarrationProvider provider;
  private final NarrationScriptChunker chunker;
  private final NarrationTimingsProperties properties;

  public NarrationSynthesizer(
      final NarrationProvider provider,
      final NarrationScriptChunker chunker,
      final NarrationTimingsProperties properties
  ) {
    this.provider = provider;
    this.chunker = chunker;
    this.properties = properties;
  }

  /**
   * Whether the provider has a synchronous path at all. Without one, narration takes the
   * long-audio route, which produces no timings.
   *
   * @return true when {@link #synthesize} can be used
   */
  public boolean available() {
    return provider.maxImmediateBytes() > 0;
  }

  /**
   * Synthesises the script and works out its timings.
   *
   * @param script the narration script
   * @param progress called on the calling thread after each sentence except the last, so a
   *     caller holding a lease can extend it
   * @return the joined audio and its timings
   * @throws NarrationProviderException when a sentence still fails after its retries
   */
  public Synthesis synthesize(final String script, final Runnable progress) {
    List<String> segments = chunker.segments(script, provider.maxImmediateBytes());
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("Nothing to synthesise");
    }
    if (segments.size() > 1) {
      LOG.debug("Synthesising narration in {} segments", segments.size());
    }
    ByteArrayOutputStream combined = new ByteArrayOutputStream();
    List<Double> durations = new ArrayList<>(segments.size());
    try (ExecutorService pool = Context.taskWrapping(Executors.newFixedThreadPool(
        Math.min(properties.concurrency(), segments.size())))) {
      List<Future<byte[]>> parts = new ArrayList<>(segments.size());
      for (String segment : segments) {
        parts.add(pool.submit(() -> synthesizeWithRetry(segment)));
      }
      try {
        for (int i = 0; i < parts.size(); i++) {
          byte[] part = await(parts.get(i));
          combined.writeBytes(part);
          durations.add(Mp3Frames.durationSeconds(part));
          if (i < parts.size() - 1) {
            progress.run();
          }
        }
      } catch (RuntimeException ex) {
        // Stop paying for sentences whose narration has already failed.
        pool.shutdownNow();
        throw ex;
      }
    }
    return new Synthesis(combined.toByteArray(), NarrationTimings.of(segments, durations));
  }

  private byte[] synthesizeWithRetry(final String segment) throws InterruptedException {
    NarrationProviderException last = null;
    for (int attempt = 1; attempt <= properties.segmentAttempts(); attempt++) {
      try {
        return provider.synthesizeImmediately(segment);
      } catch (NarrationProviderException ex) {
        if (ex.kind() != FailureKind.SAFE_TO_RETRY) {
          throw ex;
        }
        last = ex;
        if (attempt < properties.segmentAttempts()) {
          Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        }
      }
    }
    throw last;
  }

  private static byte[] await(final Future<byte[]> part) {
    try {
      return part.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new NarrationProviderException(
          "Interrupted while synthesising", FailureKind.SAFE_TO_RETRY, ex);
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new NarrationProviderException(
          "Unable to synthesise narration audio", FailureKind.SAFE_TO_RETRY, ex.getCause());
    }
  }

  /**
   * A synthesised narration.
   *
   * <p>Equality and hashing compare the audio's bytes, not the array's identity, and
   * {@code toString} gives only its length: the record default would print a reference, or,
   * if anyone "fixed" it with {@code Arrays.toString}, hundreds of kilobytes of audio into a log.
   *
   * @param audio the joined MP3 stream
   * @param timings when each word is spoken
   */
  public record Synthesis(byte[] audio, NarrationTimings timings) {

    @Override
    public boolean equals(final Object other) {
      return other instanceof Synthesis that
          && Arrays.equals(audio, that.audio)
          && Objects.equals(timings, that.timings);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(audio) + Objects.hashCode(timings);
    }

    @Override
    public String toString() {
      return "Synthesis[audio=%d bytes, words=%d]".formatted(
          audio == null ? 0 : audio.length,
          timings == null ? 0 : timings.words().size());
    }
  }
}
