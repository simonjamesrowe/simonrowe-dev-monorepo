package com.simonrowe.resume;

import java.awt.Color;

/**
 * The CV's colours, taken from the site's own design tokens in {@code styles.css}.
 *
 * <p>The two accents are not a duplication: the sidebar is a dark surface and the main
 * column is a light one, so each uses the accent the site defines for that background —
 * the dark theme's {@code --primary} on navy, the light theme's on white.
 */
final class ResumePalette {

  /** {@code --on-surface} (light theme): the site's darkest brand value. */
  static final Color SIDEBAR_BG = new Color(0x1A, 0x1D, 0x2B);

  /** {@code --on-surface} (dark theme). */
  static final Color SIDEBAR_TEXT = new Color(0xDF, 0xE2, 0xEF);

  /** {@code --on-surface-variant} (dark theme), darkened for print legibility. */
  static final Color SIDEBAR_MUTED = new Color(0x9A, 0xA6, 0xB8);

  /** {@code --primary} (dark theme): the accent designed to sit on a dark surface. */
  static final Color SIDEBAR_ACCENT = new Color(0x77, 0xD1, 0xFF);

  /** {@link #SIDEBAR_BG} blended with white, for hairlines on the navy. */
  static final Color SIDEBAR_RULE = new Color(0x43, 0x46, 0x52);

  /** {@code --on-surface} (light theme). */
  static final Color INK = new Color(0x1A, 0x1D, 0x2B);

  /** {@code --on-surface-variant} (light theme). */
  static final Color INK_MUTED = new Color(0x4B, 0x55, 0x63);

  /** {@code --primary} (light theme): the accent designed to sit on white. */
  static final Color ACCENT = new Color(0x25, 0x63, 0xEB);

  /** {@code --outline} (light theme): section rules in the main column. */
  static final Color RULE = new Color(0xD1, 0xD5, 0xDB);

  private ResumePalette() {
  }
}
