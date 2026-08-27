package com.simonrowe.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The third-party images production is declared to run, parsed from the copy of
 * {@code docker-compose.prod.yml} shipped as a resource by {@code processResources}.
 *
 * <p>Parsed once at construction: the file is immutable inside the image, so re-reading it per
 * request would buy nothing.
 *
 * <p>Excluded are the four first-party services (they self-report a commit SHA, which is a
 * far better answer than an image tag) and the one-shot init containers, which are not
 * "running" anything a reader could care about.
 */
@Component
public class ProdImageCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(ProdImageCatalog.class);

  private static final String RESOURCE = "platform/docker-compose.prod.yml";

  /** Services that report their own commit SHA instead of an image tag. */
  static final Set<String> FIRST_PARTY =
      Set.of("backend", "frontend", "software-factory", "deployer");

  private static final Set<String> ONE_SHOT = Set.of("temporal-create-namespace");

  private static final String LATEST = "latest";

  /** Matches {@code ${VAR:-default}} and {@code ${VAR}} compose interpolation. */
  private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^:}]+)(?::-([^}]*))?}");

  private final List<PlatformComponent> components;

  public ProdImageCatalog() {
    this.components = parse();
  }

  /**
   * Every third-party image production declares, sorted by service name.
   *
   * @return the components; empty when the resource is missing, never null
   */
  public List<PlatformComponent> components() {
    return components;
  }

  @SuppressWarnings("unchecked")
  private static List<PlatformComponent> parse() {
    try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
      // SafeConstructor, not the default Constructor: the default can instantiate arbitrary
      // Java types from YAML tags (the classic !!javax.script.ScriptEngineManager gadget).
      // The file parsed here is baked into our own image from the repo, so this is not
      // reachable today — it is defence in depth against this parser later being pointed at
      // a less trusted source.
      Map<String, Object> root =
          new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);
      Object services = root == null ? null : root.get("services");
      if (!(services instanceof Map)) {
        LOG.warn("No services block in {}; the status page will list no components", RESOURCE);
        return List.of();
      }
      return ((Map<String, Object>) services).entrySet().stream()
          .filter(entry -> included(entry.getKey()))
          .map(entry -> component(entry.getKey(), entry.getValue()))
          .filter(Objects::nonNull)
          .sorted(Comparator.comparing(PlatformComponent::name))
          .toList();
    } catch (IOException | RuntimeException e) {
      // A missing or malformed resource must never stop the application from starting.
      // The page renders an empty component table, which is honest.
      LOG.warn("Could not parse {}: {}", RESOURCE, e.getMessage());
      return List.of();
    }
  }

  private static boolean included(final String name) {
    return !FIRST_PARTY.contains(name) && !ONE_SHOT.contains(name) && !name.endsWith("-init");
  }

  @SuppressWarnings("unchecked")
  private static PlatformComponent component(final String name, final Object definition) {
    if (!(definition instanceof Map)) {
      return null;
    }
    Object image = ((Map<String, Object>) definition).get("image");
    if (image == null) {
      return null;
    }
    return parseReference(name, image.toString());
  }

  /**
   * Splits a compose {@code image:} reference into repository and tag, resolving {@code
   * ${VAR:-default}} interpolation first.
   *
   * <p>Package-private so it is directly testable without a classpath resource — the
   * registry-port-versus-tag disambiguation below is easy to get wrong silently, so it is
   * exercised straight from a raw reference string rather than only indirectly through {@link
   * #components()}.
   *
   * @param name the compose service name
   * @param reference the raw {@code image:} value, e.g. {@code mongo:8} or {@code host:5000/img}
   * @return the parsed component, with {@code tag} defaulted to {@code latest} when absent
   */
  static PlatformComponent parseReference(final String name, final String reference) {
    String resolved = resolve(reference.trim());
    int separator = resolved.lastIndexOf(':');
    // A colon before the last slash belongs to a registry port, not a tag.
    boolean tagged = separator > resolved.lastIndexOf('/');
    String repository = tagged ? resolved.substring(0, separator) : resolved;
    String tag = tagged ? resolved.substring(separator + 1) : LATEST;
    return new PlatformComponent(name, repository, tag, LATEST.equals(tag));
  }

  /**
   * Replaces {@code ${VAR:-default}} with its default. Production supplies these from
   * {@code .env}, which is not in the image — the declared default is the best available
   * answer and is what an unset variable would resolve to anyway.
   */
  private static String resolve(final String reference) {
    Matcher matcher = INTERPOLATION.matcher(reference);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          resolved, Matcher.quoteReplacement(matcher.group(2) == null ? "" : matcher.group(2)));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }
}
