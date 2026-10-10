package com.simonrowe.coparent.statement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Suggesting shared costs from uploaded statements. Reading a statement needs no model; only the
 * suggestions do, so they have their own switch and model.
 */
@ConfigurationProperties("coparent.statements")
public record StatementProperties(boolean aiEnabled, String model) {
}
