package com.simonrowe.resume;

import com.lowagie.text.PageSize;

/**
 * Page geometry for the CV, in PDF points on A4.
 *
 * <p>Kept apart from the renderer so the numbers can be read as a set. Everything is
 * derived from the sidebar width and the page margins; nothing here should be a
 * standalone magic number.
 */
final class ResumeLayout {

  static final float PAGE_WIDTH = PageSize.A4.getWidth();
  static final float PAGE_HEIGHT = PageSize.A4.getHeight();

  static final float SIDEBAR_WIDTH = 186f;
  static final float SIDEBAR_PAD_X = 22f;
  static final float SIDEBAR_LEFT = SIDEBAR_PAD_X;
  static final float SIDEBAR_RIGHT = SIDEBAR_WIDTH - SIDEBAR_PAD_X;

  static final float MAIN_LEFT = SIDEBAR_WIDTH + 34f;
  static final float MAIN_RIGHT = PAGE_WIDTH - 38f;
  static final float MAIN_WIDTH = MAIN_RIGHT - MAIN_LEFT;

  static final float MARGIN_TOP = 46f;
  static final float MARGIN_BOTTOM = 44f;

  /** Top of the flowing main column on a continuation page. */
  static final float CONTENT_TOP = PAGE_HEIGHT - MARGIN_TOP;

  /**
   * The closing line sits inside the bottom margin rather than in the flowing column.
   * That is where a footer belongs typographically, and it also means the last page
   * cannot run out of room for it — content never descends past {@link #MARGIN_BOTTOM}.
   */
  static final float FOOTER_TOP = 30f;
  static final float FOOTER_BOTTOM = 12f;

  static final float PHOTO_RADIUS = 45f;
  static final float PHOTO_CENTRE_X = SIDEBAR_WIDTH / 2f;
  static final float PHOTO_CENTRE_Y = PAGE_HEIGHT - 42f - PHOTO_RADIUS;

  /** Where sidebar text begins, clearing the photo. */
  static final float SIDEBAR_TOP = PHOTO_CENTRE_Y - PHOTO_RADIUS - 26f;

  private ResumeLayout() {
  }
}
