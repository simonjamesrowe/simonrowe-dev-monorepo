package com.simonrowe.factory.pageread.api;

/**
 * Body of {@code POST /api/page-reads}.
 *
 * @param url the article's address, an absolute http(s) URL
 */
public record PageReadRequest(String url) {
}
