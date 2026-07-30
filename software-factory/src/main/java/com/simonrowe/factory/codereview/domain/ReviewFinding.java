package com.simonrowe.factory.codereview.domain;

/** One actionable problem grounded in a changed file and line. */
public record ReviewFinding(
    Severity severity,
    String file,
    int line,
    String title,
    String explanation,
    String recommendation) {
}
