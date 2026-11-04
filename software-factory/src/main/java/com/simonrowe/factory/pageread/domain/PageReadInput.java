package com.simonrowe.factory.pageread.domain;

/**
 * The {@code readPage} activity's argument, as the page-reader's {@code ReadPageInput}.
 *
 * @param url the article's address
 */
public record PageReadInput(String url) {
}
