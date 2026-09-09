package com.simonrowe.school.chat;

/**
 * The result of one school chat turn.
 *
 * <p>{@code outcome} is reported separately from the text so the frontend can render a refusal,
 * a budget stop and a real answer differently, and so the four cases stay distinguishable in
 * logs. Collapsing them into "here is some text" would make a withheld answer and a genuine
 * don't-know indistinguishable, which is precisely the pair worth telling apart when something
 * looks wrong.
 *
 * @param answer the text to show
 * @param outcome what kind of response this is
 */
public record SchoolChatResponse(String answer, Outcome outcome) {

  /** What kind of turn this was. */
  public enum Outcome {
    /** A real answer from the model. */
    ANSWERED,
    /** Off topic; the guardrail stopped it before the model ran. */
    DECLINED,
    /** The answer named someone and was withheld from an anonymous visitor. */
    WITHHELD,
    /** The daily budget is spent, or the model call failed. */
    UNAVAILABLE
  }

  static SchoolChatResponse answered(final String text) {
    return new SchoolChatResponse(text, Outcome.ANSWERED);
  }

  static SchoolChatResponse declined(final String text) {
    return new SchoolChatResponse(text, Outcome.DECLINED);
  }

  static SchoolChatResponse withheld(final String text) {
    return new SchoolChatResponse(text, Outcome.WITHHELD);
  }

  static SchoolChatResponse unavailable(final String text) {
    return new SchoolChatResponse(text, Outcome.UNAVAILABLE);
  }
}
