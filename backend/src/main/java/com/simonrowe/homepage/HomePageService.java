package com.simonrowe.homepage;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HomePageService {

  private final HomePageRepository repository;

  public HomePageService(final HomePageRepository repository) {
    this.repository = repository;
  }

  /** The saved copy, or the defaults when nothing has been saved yet. */
  public HomePageContent get() {
    return repository.findById(HomePage.SINGLETON_ID)
        .map(HomePageContent::from)
        .orElse(HomePageDefaults.CONTENT);
  }

  /** Replaces the whole hero copy. Every field is written, so none is dropped by omission. */
  public HomePageContent save(final HomePageContent content) {
    List<FieldError> errors = HomePageValidator.validate(content);
    if (!errors.isEmpty()) {
      throw new HomePageValidationException(errors);
    }
    HomePage saved = repository.save(new HomePage(
        HomePage.SINGLETON_ID,
        content.headlineLine1().strip(),
        content.headlineLine2().strip(),
        blankToNull(content.lede()),
        new HomePage.Cta(content.primaryCta().label().strip(), content.primaryCta().href()),
        secondaryOrNull(content.secondaryCta()),
        content.showTourLink(),
        blankToNull(content.tourLinkLabel()),
        new HomePage.AskPill(
            blankToNull(content.askPill().lead()),
            content.askPill().label().strip(),
            content.askPill().buttonLabel().strip()),
        Instant.now()));
    return HomePageContent.from(saved);
  }

  private static HomePage.Cta secondaryOrNull(final HomePage.Cta cta) {
    if (cta == null || cta.label() == null || cta.label().isBlank()) {
      return null;
    }
    return new HomePage.Cta(cta.label().strip(), cta.href());
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
