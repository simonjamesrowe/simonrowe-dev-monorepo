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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NarrationStorage {

  private static final long MP3_BITS_PER_SECOND = 32_000L;

  private final Path uploadsPath;

  public NarrationStorage(
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.uploadsPath = Path.of(uploadsPath);
  }

  public StoredNarration store(final String narrationId, final byte[] audio) {
    validateMp3(audio);
    Path directory = uploadsPath.resolve("narrations").resolve(narrationId);
    Path workDirectory = directory.resolve(".work");
    Path partial = workDirectory.resolve("narration.mp3.part");
    Path target = directory.resolve("narration.mp3");
    try {
      Files.createDirectories(workDirectory);
      Files.write(partial, audio);
      moveAtomically(partial, target);
      deleteDirectoryIfEmpty(workDirectory);
      long fileSize = Files.size(target);
      long durationSeconds = Math.max(1, fileSize * 8 / MP3_BITS_PER_SECOND);
      return new StoredNarration(
          "/uploads/narrations/" + narrationId + "/narration.mp3",
          fileSize,
          checksum(audio),
          durationSeconds);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to store generated narration", ex);
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
    if (narration.audioPath() == null) {
      return;
    }
    try {
      Files.deleteIfExists(resolvePublicPath(narration.audioPath()));
    } catch (IOException ignored) {
      // Cleanup is best effort; a later maintenance pass can remove orphaned files.
    }
  }

  public void delete(final String narrationId) {
    try {
      Files.deleteIfExists(uploadsPath.resolve("narrations")
          .resolve(narrationId).resolve("narration.mp3"));
    } catch (IOException ignored) {
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
    Path root = uploadsPath.resolve("narrations");
    if (!Files.isDirectory(root)) {
      return 0;
    }
    int removed = 0;
    try (var paths = Files.walk(root)) {
      // Deepest first, so a directory is only visited once its contents are gone.
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
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

  public record StoredNarration(
      String publicPath,
      long fileSize,
      String checksumSha256,
      long durationSeconds
  ) {
  }
}
