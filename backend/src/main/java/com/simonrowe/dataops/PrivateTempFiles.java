package com.simonrowe.dataops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Temporary files in the shared temp directory, created readable and writable by this process's
 * user alone. Backup archives hold every collection, so another local user must not be able to
 * open one while it is being written or read.
 *
 * <p>Owner-only is already what the JDK's default provider does on POSIX. Passing it at creation
 * makes it a guarantee of this code rather than of the provider, with no window in which the
 * file exists with wider permissions. Production and every development machine are POSIX; on a
 * filesystem without POSIX permissions this throws {@link UnsupportedOperationException} rather
 * than quietly creating a file anyone can read.
 */
final class PrivateTempFiles {

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

  private PrivateTempFiles() {
  }

  static Path create(final String prefix, final String suffix) throws IOException {
    return Files.createTempFile(prefix, suffix, OWNER_ONLY);
  }
}
