package com.simonrowe.factory.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether the reviewer arms GitHub auto-merge on pull requests that qualify.
 *
 * <p>A record of its own rather than a component of {@link CodeReviewProperties}: that record is
 * constructed positionally in about twenty tests, none of which care about merging.
 *
 * <p>Switching this off stops the reviewer <em>arming</em> anything, but it still withdraws an
 * arm it made earlier on every re-review. Otherwise turning the flag off would leave every
 * pull request it had already armed to merge on whatever was pushed next.
 *
 * @param enabled {@code FACTORY_CODEREVIEW_AUTO_MERGE_ENABLED}; off unless set
 */
@ConfigurationProperties("factory.codereview.auto-merge")
public record AutoMergeProperties(boolean enabled) {
}
