package com.simonrowe.aggregation.newsletter;

/**
 * One story read out of a newsletter issue, before it has been scored or saved.
 *
 * @param title the headline, with the trailing {@code (5 minute read)} label removed
 * @param url the story's own address, already unwrapped from the newsletter's click tracking
 * @param summary the newsletter's own two or three sentence summary
 * @param section the issue section it sat under, e.g. {@code Articles & Tutorials}
 * @param label the trailing label, e.g. {@code 5 minute read} or {@code GitHub Repo}
 */
public record NewsletterStory(
    String title,
    String url,
    String summary,
    String section,
    String label
) {
}
