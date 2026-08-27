package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import java.time.Instant;
import java.util.ArrayList;
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
    return components.stream().filter(this::isNewInformation).toList();
  }

  /**
   * Whether this component's current findings differ from whatever was last given up on.
   *
   * <p>The single definition of "new information", shared by both public methods on purpose: what
   * makes a component worth re-attempting is exactly what makes a fresh give-up worth reporting,
   * and two copies of this comparison would drift.
   */
  private boolean isNewInformation(final ComponentFindings component) {
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
   * @return only the components this call newly recorded — absent before, or stored under a
   *     different fingerprint. That is the same "new information" test {@link #retainActionable}
   *     applies, and it is what a notification sink needs: the schedule is daily, so returning
   *     every stored give-up would report the same component every 24 hours forever. A component
   *     not in {@code components} is never returned, because nothing was recorded for it.
   */
  public List<UnfixableComponent> record(
      final List<UnfixableComponent> unfixable, final List<ComponentFindings> components) {
    Map<String, ComponentFindings> byPurl =
        components.stream()
            .collect(Collectors.toMap(ComponentFindings::purl, Function.identity(), (a, b) -> a));
    List<UnfixableComponent> newlyRecorded = new ArrayList<>();
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
      // Read before the upsert, deliberately: afterwards the stored fingerprint is this run's by
      // definition and every component would look new.
      boolean newInformation = isNewInformation(current);
      repository.save(
          new UnfixableFindingRecord(
              UnfixableFindingRecord.idFor(current.purl()),
              current.purl(),
              current.fingerprint(),
              current.vulnerabilityIds(),
              component.reason(),
              Instant.now()));
      if (newInformation) {
        newlyRecorded.add(component);
      }
    }
    return List.copyOf(newlyRecorded);
  }
}
