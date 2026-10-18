package com.simonrowe.narration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NarrationStorage {

  /** Hex characters of the audio checksum used in content-addressed file names. */
  private static final int NAME_HASH_LENGTH = 16;
  private static final String NARRATIONS_DIRECTORY = "narrations";

  private final Path uploadsPath;

  public NarrationStorage(
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.uploadsPath = Path.of(uploadsPath);
  }

  public StoredNarration store(final String narrationId, final byte[] audio) {
    return store(narrationId, audio, null);
  }

  /**
   * Writes a narration's audio, and its read-along timings when it has them.
   *
   * <p>With timings, both files are named after the audio's checksum
   * ({@code audio-<hash>.mp3} beside {@code timings-<hash>.json}). Re-making old audio to add
   * timings therefore gives it a new URL: a browser holding the old MP3 in its cache can never
   * pair it with timings made for different audio, which would highlight the wrong words.
   * Audio without timings keeps the original {@code narration.mp3} name.
   *
   * @param narrationId the narration, which names the directory
   * @param audio the MP3 stream
   * @param timings when each word is spoken, or null when there are none
   * @return where the files went
   */
  public StoredNarration store(
      final String narrationId,
      final byte[] audio,
      @Nullable final NarrationTimings timings
  ) {
    validateMp3(audio);
    String checksum = checksum(audio);
    String audioName = timings == null
        ? "narration.mp3" : "audio-" + checksum.substring(0, NAME_HASH_LENGTH) + ".mp3";
    String timingsName = timings == null
        ? null : "timings-" + checksum.substring(0, NAME_HASH_LENGTH) + ".json";
    Path directory = uploadsPath.resolve(NARRATIONS_DIRECTORY).resolve(narrationId);
    Path workDirectory = directory.resolve(".work");
    try {
      Files.createDirectories(workDirectory);
      // Timings first: audio that is visible always has its timings beside it.
      if (timingsName != null) {
        Path partialTimings = workDirectory.resolve(timingsName + ".part");
        Files.write(partialTimings, timings.toJson());
        moveAtomically(partialTimings, directory.resolve(timingsName));
      }
      Path partial = workDirectory.resolve(audioName + ".part");
      Path target = directory.resolve(audioName);
      Files.write(partial, audio);
      moveAtomically(partial, target);
      deleteDirectoryIfEmpty(workDirectory);
      long fileSize = Files.size(target);
      long durationSeconds = Math.max(1, Math.round(Mp3Frames.durationSeconds(audio)));
      String base = "/uploads/narrations/" + narrationId + "/";
      return new StoredNarration(
          base + audioName,
          fileSize,
          checksum,
          durationSeconds,
          timingsName == null ? null : base + timingsName);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to store generated narration", ex);
    }
  }

  /**
   * Whether a narration's timings file is on disk. False when it has none.
   *
   * @param narration the narration
   * @return true when {@code timingsPath} names a readable file
   */
  public boolean timingsExist(final Narration narration) {
    if (narration.timingsPath() == null) {
      return false;
    }
    try {
      return Files.isRegularFile(resolvePublicPath(narration.timingsPath()));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public boolean isValid(final Narration narration) {
    if (narration.audioPath() == null || narration.checksumSha256() == null) {
      return false;
    }
    Path file = resolvePublicPath(narration.audioPath());
    try {
      if (!Files.isRegularFile(file) || Files.size(file) == 0) {
        return false;
      }
      byte[] audio = Files.readAllBytes(file);
      validateMp3(audio);
      return narration.checksumSha256().equals(checksum(audio));
    } catch (IOException | IllegalArgumentException ex) {
      return false;
    }
  }

  public void delete(final Narration narration) {
    deletePublicPath(narration.audioPath());
    deletePublicPath(narration.timingsPath());
  }

  public void delete(final String narrationId) {
    try {
      Files.deleteIfExists(uploadsPath.resolve(NARRATIONS_DIRECTORY)
          .resolve(narrationId).resolve("narration.mp3"));
    } catch (IOException ignored) {
      // Cleanup is best effort; a later maintenance pass can remove orphaned files.
    }
  }

  /**
   * Removes files just written, when the narration they were for has moved on.
   *
   * @param stored what {@link #store} returned
   */
  public void delete(final StoredNarration stored) {
    deletePublicPath(stored.publicPath());
    deletePublicPath(stored.timingsPath());
  }

  /**
   * Removes one file under {@code /uploads/}, best effort.
   *
   * @param publicPath the file's public path, or null for nothing
   */
  void deletePublicPath(@Nullable final String publicPath) {
    if (publicPath == null) {
      return;
    }
    try {
      Files.deleteIfExists(resolvePublicPath(publicPath));
    } catch (IOException | IllegalArgumentException ignored) {
      // Cleanup is best effort; a later maintenance pass can remove orphaned files.
    }
  }

  /**
   * Removes every stored narration audio file and the directories holding them.
   *
   * <p>Used when a change to the voice or script format invalidates the whole corpus at
   * once: the narration id is a fingerprint over those settings, so no existing file can
   * ever be looked up again and each one is only consuming disk and backup space.
   *
   * <p>Best effort, in keeping with the rest of this class — a file that cannot be
   * removed is skipped rather than failing the caller, because the caller is a Mongock
   * change unit and a thrown exception there aborts application startup.
   *
   * @return the number of audio files removed
   */
  public int deleteAll() {
    Path root = uploadsPath.resolve(NARRATIONS_DIRECTORY);
    if (!Files.isDirectory(root)) {
      return 0;
    }
    int removed = 0;
    try (var paths = Files.walk(root)) {
      // Deepest first, so a directory is only visited once its contents are gone.
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        // Timings files go too, but only audio is counted.
        boolean audioFile = Files.isRegularFile(path)
            && path.getFileName().toString().endsWith(".mp3");
        try {
          Files.deleteIfExists(path);
          if (audioFile) {
            removed++;
          }
        } catch (IOException ignored) {
          // Leave this entry, and the directories above it, in place.
        }
      }
    } catch (IOException ignored) {
      // The tree could not be walked; whatever was already removed stays removed.
    }
    return removed;
  }

  private Path resolvePublicPath(final String publicPath) {
    String prefix = "/uploads/";
    if (!publicPath.startsWith(prefix)) {
      throw new IllegalArgumentException("Narration path is outside uploads");
    }
    Path resolved = uploadsPath.resolve(publicPath.substring(prefix.length())).normalize();
    Path normalizedUploads = uploadsPath.toAbsolutePath().normalize();
    Path absolute = resolved.toAbsolutePath().normalize();
    if (!absolute.startsWith(normalizedUploads)) {
      throw new IllegalArgumentException("Narration path is outside uploads");
    }
    return absolute;
  }

  private static void validateMp3(final byte[] audio) {
    if (audio == null || audio.length < 4) {
      throw new IllegalArgumentException("Generated audio is empty or truncated");
    }
    boolean id3 = audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3';
    boolean mpegFrame = (audio[0] & 0xff) == 0xff && (audio[1] & 0xe0) == 0xe0;
    if (!id3 && !mpegFrame) {
      throw new IllegalArgumentException("Generated audio is not an MP3 stream");
    }
  }

  private static String checksum(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static void moveAtomically(final Path source, final Path target)
      throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ex) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteDirectoryIfEmpty(final Path directory) throws IOException {
    try (var entries = Files.list(directory)) {
      if (entries.findAny().isEmpty()) {
        Files.deleteIfExists(directory);
      }
    }
  }

  /**
   * Where a narration's files went.
   *
   * @param publicPath the audio, under {@code /uploads/}
   * @param fileSize audio bytes
   * @param checksumSha256 of the audio
   * @param durationSeconds playing time, rounded
   * @param timingsPath the read-along timings, or null when there are none
   */
  public record StoredNarration(
      String publicPath,
      long fileSize,
      String checksumSha256,
      long durationSeconds,
      @Nullable String timingsPath
  ) {

    public StoredNarration(
        final String publicPath,
        final long fileSize,
        final String checksumSha256,
        final long durationSeconds
    ) {
      this(publicPath, fileSize, checksumSha256, durationSeconds, null);
    }
  }
}
