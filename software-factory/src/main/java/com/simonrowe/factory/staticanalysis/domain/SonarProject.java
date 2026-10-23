package com.simonrowe.factory.staticanalysis.domain;

/**
 * Where the analysis lives, carried on the observation so the ticket renderer can link to it.
 *
 * <p>It travels from the activity rather than being read from configuration by the renderer,
 * because the renderer runs in workflow code, which can read nothing but its inputs.
 *
 * @param sonarBaseUrl SonarCloud's root, such as {@code https://sonarcloud.io}
 * @param organization the SonarCloud organisation
 * @param projectKey the SonarCloud project key
 * @param branch the analysed branch
 */
public record SonarProject(
    String sonarBaseUrl, String organization, String projectKey, String branch) {
}
