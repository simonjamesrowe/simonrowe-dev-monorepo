package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Drops components already recorded as unfixable, unless their finding set has changed.
 *
 * <p>This is what stops an advisory with no available fix costing an agent run every night. It is
 * deliberately plain logic rather than an agent judgement.
 */
@Component
public class FindingSuppressor {

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
}
