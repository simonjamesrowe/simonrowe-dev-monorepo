package com.simonrowe.school.classify;

/**
 * Text and a filing label read from one photographed page.
 *
 * @param text the page text, preserving its order and line breaks
 * @param title a short suggested filing label
 */
record TranscribedNote(String text, String title) {
}
