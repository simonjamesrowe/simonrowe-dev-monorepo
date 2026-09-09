package com.simonrowe.school.retrieval;

import com.simonrowe.school.model.Visibility;
import java.util.List;

/**
 * Which tiers a single request is allowed to see.
 *
 * <p>Resolved once per request from the authenticated principal and passed down. It is the single
 * source of truth for FR-024, and the reason it is a type rather than a boolean is that a boolean
 * gets negated by accident and reads the same either way at the call site.
 *
 * <p>There is deliberately no constructor taking a tier list. The only two audiences that exist
 * are the two factory methods, so no caller can invent a third — in particular, none can construct
 * an audience from anything a client sent.
 */
public final class SchoolAudience {

  private static final SchoolAudience ANONYMOUS =
      new SchoolAudience(List.of(Visibility.PUBLIC), false);
  private static final SchoolAudience SCHOOL_MEMBER =
      new SchoolAudience(List.of(Visibility.PUBLIC, Visibility.RESTRICTED), true);

  private final List<Visibility> visibilities;
  private final boolean authenticated;

  private SchoolAudience(final List<Visibility> visibilities, final boolean authenticated) {
    this.visibilities = visibilities;
    this.authenticated = authenticated;
  }

  /**
   * The audience for a request with no school role: public content only.
   *
   * @return the anonymous audience
   */
  public static SchoolAudience anonymous() {
    return ANONYMOUS;
  }

  /**
   * The audience for a request carrying the school role.
   *
   * @return the authenticated audience
   */
  public static SchoolAudience schoolMember() {
    return SCHOOL_MEMBER;
  }

  /**
   * The tiers this request may read.
   *
   * @return one or both visibilities, never empty
   */
  public List<Visibility> visibilities() {
    return visibilities;
  }

  /**
   * Whether this request carries the school role.
   *
   * @return true for an authenticated school member
   */
  public boolean authenticated() {
    return authenticated;
  }

  /**
   * Whether the output-side name check must run before returning an answer.
   *
   * <p>Only for anonymous requests. The check exists to stop a retrieval-filter mistake putting
   * someone else's child's name on the open internet; applying it to the owner's own view of their
   * own mail would withhold answers for no benefit.
   *
   * @return true when the generated answer must be name-checked
   */
  public boolean requiresPublicAnswerCheck() {
    return !authenticated;
  }
}
