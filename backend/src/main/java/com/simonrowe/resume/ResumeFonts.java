package com.simonrowe.resume;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CV's typefaces, mirroring the site: Inter for body copy, Space Grotesk for the
 * name and section headings.
 *
 * <p>The fonts are vendored under {@code resources/resume/fonts} rather than taken from
 * a CDN, because a PDF has to carry its own glyphs. They are embedded with
 * {@link BaseFont#IDENTITY_H} so bullet glyphs and en-dashes render, and OpenPDF subsets
 * them on the way out — a generated CV is a few tens of kilobytes, not a megabyte.
 *
 * <p>If a font cannot be loaded the CV still renders, in Helvetica. A downloadable CV
 * that looks plainer is a far better failure than one that 500s.
 */
final class ResumeFonts {

  private static final Logger LOG = LoggerFactory.getLogger(ResumeFonts.class);

  private static final String FONT_DIR = "/resume/fonts/";

  private static final BaseFont BODY = load("Inter-Regular.ttf", BaseFont.HELVETICA);
  private static final BaseFont BODY_BOLD =
      load("Inter-SemiBold.ttf", BaseFont.HELVETICA_BOLD);
  private static final BaseFont BODY_ITALIC =
      load("Inter-Italic.ttf", BaseFont.HELVETICA_OBLIQUE);
  private static final BaseFont DISPLAY =
      load("SpaceGrotesk-Bold.ttf", BaseFont.HELVETICA_BOLD);

  private ResumeFonts() {
  }

  static Font body(final float size, final Color colour) {
    return new Font(BODY, size, Font.NORMAL, colour);
  }

  static Font bodyBold(final float size, final Color colour) {
    return new Font(BODY_BOLD, size, Font.NORMAL, colour);
  }

  static Font bodyItalic(final float size, final Color colour) {
    return new Font(BODY_ITALIC, size, Font.NORMAL, colour);
  }

  static Font display(final float size, final Color colour) {
    return new Font(DISPLAY, size, Font.NORMAL, colour);
  }

  /** Picks the body face matching a parsed markdown run's emphasis. */
  static Font forRun(final ResumeTextRun run, final float size, final Color colour) {
    if (run.bold()) {
      return bodyBold(size, colour);
    }
    if (run.italic()) {
      return bodyItalic(size, colour);
    }
    return body(size, colour);
  }

  private static BaseFont load(final String fileName, final String fallback) {
    try (InputStream in = ResumeFonts.class.getResourceAsStream(FONT_DIR + fileName)) {
      if (in == null) {
        LOG.warn("CV font {} is not on the classpath; falling back to {}",
            fileName, fallback);
        return fallbackFont(fallback);
      }
      return BaseFont.createFont(
          FONT_DIR + fileName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
          BaseFont.CACHED, in.readAllBytes(), null);
    } catch (IOException e) {
      LOG.warn("Failed to load CV font {}: {}", fileName, e.getMessage());
      return fallbackFont(fallback);
    }
  }

  private static BaseFont fallbackFont(final String name) {
    try {
      return BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    } catch (IOException e) {
      throw new IllegalStateException("No usable font for the CV", e);
    }
  }
}
