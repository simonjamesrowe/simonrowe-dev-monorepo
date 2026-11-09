package com.simonrowe.factory.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code docker-compose.prod.yml} well enough to assert what a service does and does not
 * declare.
 *
 * <p>Extracted when a second credential-confinement test needed exactly the same parsing as
 * {@code DeployerLinearCredentialTest}, which had carried it inline. Two tests needing identical
 * parsing is where the abstraction earns its place; one would not have.
 *
 * <p>A hand-rolled line scan rather than a YAML library, matching {@code NoHostProcessLaunchTest}
 * in the backend module: this module carries no YAML dependency, and a handful of lines of the
 * compose file is not worth adding one for.
 */
public final class ComposeFile {

  /**
   * The Gradle test working directory is the module directory ({@code software-factory/}), one
   * level below the repo root where the compose file lives.
   */
  public static final Path PATH = Path.of("..", "docker-compose.prod.yml");

  /**
   * The factory host's compose file. {@code software-factory} and {@code page-reader} live here,
   * and so does a second {@code deployer}, which holds that host's Docker socket and so carries
   * every confinement rule the Pi's does.
   */
  public static final Path FACTORY_PATH = Path.of("..", "docker-compose.factory.yml");

  /** A top-level compose mapping key at two-space indent, e.g. {@code "  deployer:"}. */
  private static final Pattern SERVICE_HEADER = Pattern.compile("^ {2}([a-zA-Z0-9_-]+):\\s*$");

  /** A YAML {@code key: value} line, capturing the key. */
  private static final Pattern MAPPING_KEY = Pattern.compile("^\\s+([A-Za-z0-9_]+):.*");

  private ComposeFile() {
  }

  /**
   * Reads the compose file.
   *
   * @return every line, in order
   * @throws IOException if the file cannot be read
   */
  public static List<String> lines() throws IOException {
    return readLines(PATH);
  }

  /**
   * Reads the factory host's compose file.
   *
   * @return every line, in order
   * @throws IOException if the file cannot be read
   */
  public static List<String> factoryLines() throws IOException {
    return readLines(FACTORY_PATH);
  }

  /**
   * Both deployers' service blocks, keyed by compose file name: the Pi's and the factory host's.
   *
   * <p>Every rule that keeps a credential or a flag off a container holding a Docker socket must
   * hold for both, so confinement tests assert over this rather than over one file.
   *
   * @return the two blocks, the Pi's first
   * @throws IOException if either file cannot be read
   */
  public static Map<String, List<String>> deployerBlocks() throws IOException {
    Map<String, List<String>> blocks = new LinkedHashMap<>();
    blocks.put(PATH.getFileName().toString(), serviceBlock(lines(), "deployer"));
    blocks.put(FACTORY_PATH.getFileName().toString(), serviceBlock(factoryLines(), "deployer"));
    return blocks;
  }

  /**
   * Both deployers' lines, concatenated, for assertions that something appears in neither.
   *
   * @return the Pi's deployer block followed by the factory host's
   * @throws IOException if either file cannot be read
   */
  public static List<String> everyDeployer() throws IOException {
    List<String> lines = new ArrayList<>();
    deployerBlocks().values().forEach(lines::addAll);
    return lines;
  }

  /**
   * The {@code software-factory} service block, from the factory host's file.
   *
   * @return its lines
   * @throws IOException if the file cannot be read
   */
  public static List<String> softwareFactory() throws IOException {
    return serviceBlock(factoryLines(), "software-factory");
  }

  private static List<String> readLines(final Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new AssertionError(
          """
          Could not find %s - this test assumes the Gradle test \
          working directory is the module directory, one level below the repo \
          root.""".formatted(path.toAbsolutePath()));
    }
    return Files.readAllLines(path);
  }

  /**
   * Isolates one top-level service's mapping: every line from its {@code "  <name>:"} header, up
   * to (excluding) the next line at the same indentation that also looks like a mapping key.
   *
   * @param lines the whole compose file, in order
   * @param serviceName the service to isolate, e.g. {@code deployer}
   * @return the lines belonging to that service, never including its own header line
   */
  public static List<String> serviceBlock(final List<String> lines, final String serviceName) {
    List<String> block = new ArrayList<>();
    boolean insideService = false;
    boolean found = false;
    for (String line : lines) {
      Matcher header = SERVICE_HEADER.matcher(line);
      if (header.matches()) {
        if (header.group(1).equals(serviceName)) {
          insideService = true;
          found = true;
          continue;
        } else if (insideService) {
          break;
        }
      }
      if (insideService) {
        block.add(line);
      }
    }
    if (!found) {
      throw new AssertionError(
          """
          Could not find a '%s:' service block in the compose file - has it been renamed, \
          removed or moved to the other host's file? This test must not pass by reading \
          nothing."""
              .formatted(serviceName));
    }
    return block;
  }

  /**
   * Finds declared variable names in a service block whose name contains the given fragment.
   *
   * <p>Comment lines are skipped, because the comments explaining why a variable is absent
   * necessarily name it — a scan over raw lines would fail on its own rationale and teach the
   * next person to delete the explanation.
   *
   * @param serviceBlock the lines of one service, from {@link #serviceBlock}
   * @param fragment the substring to look for in variable names, e.g. {@code LINEAR}
   * @return the offending keys, in order
   */
  public static List<String> declaredKeysContaining(
      final List<String> serviceBlock, final String fragment) {
    List<String> offending = new ArrayList<>();
    for (String line : serviceBlock) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      Matcher key = MAPPING_KEY.matcher(line);
      if (key.matches() && key.group(1).contains(fragment)) {
        offending.add(key.group(1));
      }
    }
    return offending;
  }
}
