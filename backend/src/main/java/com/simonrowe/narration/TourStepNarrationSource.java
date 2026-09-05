package com.simonrowe.narration;

import com.simonrowe.tour.TourStep;
import com.simonrowe.tour.TourStepRepository;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narration source for a guided-tour step, so the tour can speak each card aloud in the
 * same Google voice the rest of the site uses.
 *
 * <p>Reads the public {@link TourStep} projection of the {@code tourSteps} collection —
 * the same one {@code GET /api/tour/steps} serves — rather than the admin projection, so
 * the id narrated against is exactly the id the browser holds.
 *
 * <p>The {@code contentId} is the tour step's own id, which is what
 * {@code GET /api/tour/steps} already hands the browser — so the tour can key the bulk
 * {@code /api/narrations/ready?contentType=TOUR_STEP} read straight off the steps it holds,
 * with no join and no second request. Same convention as {@link ReadyNarration} documents.
 *
 * <p>The script is the step's title followed by its description, through the shared
 * {@link NarrationScriptBuilder}: descriptions are authored as Markdown in the admin CMS and
 * rendered as Markdown in the tooltip, so the same stripping the blog path needs applies
 * here. Because the fingerprint is computed over that text, re-wording a step in the CMS
 * yields a different narration id and marks the previous audio {@code STALE} for free.
 *
 * <p>Unlike blogs and article summaries there is no length guard beyond the shared maximum.
 * A tour step is two sentences by construction; the interesting failure is an <em>empty</em>
 * one, which is a 422 rather than silence.
 */
@Component
public class TourStepNarrationSource implements NarrationSource {

  private static final String AUDIO_ENCODING = "MP3";

  /**
   * At least one letter or digit, which is what "there is something to say" means here.
   *
   * <p>A blank check is not enough. {@link NarrationScriptBuilder#build} joins the title and
   * the body with a full stop, so a step with both fields empty yields the script {@code "."}
   * — not blank, and text-to-speech would be asked to synthesise a full stop.
   */
  private static final Pattern SPEAKABLE = Pattern.compile("[\\p{L}\\p{N}]");

  private final TourStepRepository tourStepRepository;
  private final NarrationScriptBuilder scriptBuilder;
  private final NarrationProperties properties;

  public TourStepNarrationSource(
      final TourStepRepository tourStepRepository,
      final NarrationScriptBuilder scriptBuilder,
      final NarrationProperties properties
  ) {
    this.tourStepRepository = tourStepRepository;
    this.scriptBuilder = scriptBuilder;
    this.properties = properties;
  }

  @Override
  public NarrationContentType contentType() {
    return NarrationContentType.TOUR_STEP;
  }

  @Override
  public NarrationDescriptor scriptFor(final String contentId) {
    return descriptor(tourStepRepository.findById(contentId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Tour step not found")));
  }

  @Override
  public boolean isCurrent(final Narration narration) {
    return tourStepRepository.findById(narration.contentId())
        .map(this::descriptor)
        .map(current -> current.id().equals(narration.id()))
        .orElse(false);
  }

  /**
   * The descriptor a tour step's current copy produces.
   *
   * <p>Package-private so a caller sweeping every step can compute what it would narrate
   * without going back through the repository.
   *
   * @param step the tour step
   * @return the descriptor
   */
  NarrationDescriptor scriptForStep(final TourStep step) {
    return descriptor(step);
  }

  private NarrationDescriptor descriptor(final TourStep step) {
    String script = scriptBuilder.build(step.title(), step.description());
    if (!SPEAKABLE.matcher(script).find()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Tour step has no narratable prose");
    }
    if (script.length() > properties.maxBlogCharacters()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Tour step is too long to narrate");
    }
    String id = scriptBuilder.fingerprint(
        script,
        properties.voiceName(),
        properties.languageCode(),
        AUDIO_ENCODING);
    return new NarrationDescriptor(id, script);
  }
}
