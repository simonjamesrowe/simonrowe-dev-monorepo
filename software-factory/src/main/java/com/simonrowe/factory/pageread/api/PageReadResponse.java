package com.simonrowe.factory.pageread.api;

import com.simonrowe.factory.pageread.domain.PageReadResult;

/**
 * The answer to a read.
 *
 * @param state whether the read finished
 * @param result how it ended, present only when {@code state} is {@code COMPLETED}
 */
public record PageReadResponse(PageReadState state, PageReadResult result) {

  static PageReadResponse of(final PageReadState state) {
    return new PageReadResponse(state, null);
  }
}
