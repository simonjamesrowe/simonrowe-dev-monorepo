package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drops components already recorded as unfixable, unless their finding set has changed.
 *
 * <p>This is what stops an advisory with no available fix costing an agent run every night. It is
 * deliberately plain logic rather than an agent judgement.
 */
@Component
public class FindingSuppressor {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindingSuppressor.class);

  private final UnfixableFindingRepository repository;

  /**
   * Creates the suppressor.
   *
   * @param repository the store of components already recorded as unfixable
   */
  public FindingSuppressor(final UnfixableFindingRepository repository) {
    this.repository = repository;
  }

  /**
   * The components worth attempting this run.
   *
   * @param components every component with an open finding this run
   * @return the components with no stored give-up, or whose finding set has changed since
   */
  public List<ComponentFindings> retainActionable(final List<ComponentFindings> components) {
    return components.stream().filter(this::isActionable).toList();
  }

  private boolean isActionable(final ComponentFindings component) {
    return repository
        .findByPurl(component.purl())
        .map(record -> !record.fingerprint().equals(component.fingerprint()))
        .orElse(true);
  }

  /**
   * Upserts the give-up records so later runs skip these components until something changes.
   *
   * <p>The stored fingerprint and ids come from {@code components} — the Dependency-Track data
   * that {@link #retainActionable} will compare against — never from the agent's output. A
   * model-emitted key would be compared against a Java-computed one and would disable suppression
   * entirely on any deviation.
   *
   * @param unfixable the components the agent declined to bump, from this run
   * @param components every component with an open finding this run
   */
  public void record(
      final List<UnfixableComponent> unfixable, final List<ComponentFindings> components) {
    Map<String, ComponentFindings> byPurl =
        components.stream()
            .collect(Collectors.toMap(ComponentFindings::purl, Function.identity(), (a, b) -> a));
    for (UnfixableComponent component : unfixable) {
      ComponentFindings current = byPurl.get(component.purl());
      if (current == null) {
        // The agent named a component Dependency-Track did not report. There is no fingerprint
        // to store, and storing a guess would suppress something that was never seen.
        LOGGER.warn(
            "Ignoring unfixable purl not in this run's findings: {} (reason: {})",
            component.purl(),
            component.reason());
        continue;
      }
      repository.save(
          new UnfixableFindingRecord(
              UnfixableFindingRecord.idFor(current.purl()),
              current.purl(),
              current.fingerprint(),
              current.vulnerabilityIds(),
              component.reason(),
              Instant.now()));
    }
  }
}
