package com.simonrowe.factory.staticanalysis.domain;

/**
 * One failing condition of the branch's quality gate.
 *
 * @param metricKey the metric, such as {@code new_coverage}
 * @param comparator {@code LT} or {@code GT}
 * @param errorThreshold the threshold the metric failed
 * @param actualValue the metric's value on the analysed revision
 */
public record QualityGateCondition(
    String metricKey, String comparator, String errorThreshold, String actualValue) {
}
