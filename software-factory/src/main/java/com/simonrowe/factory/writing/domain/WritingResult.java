package com.simonrowe.factory.writing.domain;

/**
 * What a writing job produced.
 *
 * @param text the written text, never blank
 */
public record WritingResult(String text) {
}
