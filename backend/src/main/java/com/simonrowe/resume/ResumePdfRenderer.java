package com.simonrowe.resume;

import static com.simonrowe.resume.ResumeLayout.CONTENT_TOP;
import static com.simonrowe.resume.ResumeLayout.FOOTER_BOTTOM;
import static com.simonrowe.resume.ResumeLayout.FOOTER_TOP;
import static com.simonrowe.resume.ResumeLayout.MAIN_LEFT;
import static com.simonrowe.resume.ResumeLayout.MAIN_RIGHT;
import static com.simonrowe.resume.ResumeLayout.MAIN_WIDTH;
import static com.simonrowe.resume.ResumeLayout.MARGIN_BOTTOM;
import static com.simonrowe.resume.ResumeLayout.PAGE_HEIGHT;
import static com.simonrowe.resume.ResumeLayout.PHOTO_CENTRE_X;
import static com.simonrowe.resume.ResumeLayout.PHOTO_CENTRE_Y;
import static com.simonrowe.resume.ResumeLayout.PHOTO_RADIUS;
import static com.simonrowe.resume.ResumeLayout.SIDEBAR_LEFT;
import static com.simonrowe.resume.ResumeLayout.SIDEBAR_RIGHT;
import static com.simonrowe.resume.ResumeLayout.SIDEBAR_TOP;
import static com.simonrowe.resume.ResumeLayout.SIDEBAR_WIDTH;
import static com.simonrowe.resume.ResumePalette.ACCENT;
import static com.simonrowe.resume.ResumePalette.INK;
import static com.simonrowe.resume.ResumePalette.INK_MUTED;
import static com.simonrowe.resume.ResumePalette.RULE;
import static com.simonrowe.resume.ResumePalette.SIDEBAR_ACCENT;
import static com.simonrowe.resume.ResumePalette.SIDEBAR_BG;
import static com.simonrowe.resume.ResumePalette.SIDEBAR_MUTED;
import static com.simonrowe.resume.ResumePalette.SIDEBAR_RULE;
import static com.simonrowe.resume.ResumePalette.SIDEBAR_TEXT;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders the CV PDF: a navy sidebar carrying the headshot, contact details, links,
 * skills and education, and a flowing main column carrying the profile and experience.
 *
 * <p>Roles taper. The most recent few and any long tenure are given their full
 * description; everything older is reduced to a header and a one-paragraph summary, so
 * a twenty-year history stays inside three pages without losing the shape of it.
 */
@Component
public class ResumePdfRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(ResumePdfRenderer.class);

  /**
   * Roles this recent always get their full description. Four covers the last eight
   * years, which is the span a reader actually interrogates.
   */
  private static final int DETAILED_ROLE_COUNT = 4;

  /**
   * An older role this long also gets its full description. A seven-year tenure is part
   * of the story regardless of its position in the list, and a positional cut-off alone
   * would reduce it to two lines.
   */
  private static final double LONG_TENURE_YEARS = 5.0;

  /** Per sub-heading, so every theme of a role survives rather than the first few. */
  private static final int MAX_BULLETS_PER_HEADING = 2;

  /**
   * For a role written as one flat list. The per-heading cap cannot be reused here: with
   * no headings the whole role is one section, so it would keep two bullets out of
   * seven and leave the role looking like it had nothing to say.
   */
  private static final int MAX_BULLETS_UNGROUPED = 5;

  /** Across a whole role, so the current job cannot swallow a page and a half. */
  private static final int MAX_BULLETS_PER_ROLE = 16;

  private static final float NAME_SIZE = 25f;
  private static final float TITLE_SIZE = 10.5f;
  private static final float SECTION_SIZE = 10f;
  private static final float JOB_TITLE_SIZE = 10.5f;
  private static final float COMPANY_SIZE = 9.3f;
  private static final float META_SIZE = 8.2f;
  private static final float BODY_SIZE = 8.7f;
  private static final float BODY_LEADING = 11.9f;
  private static final float SUBHEADING_SIZE = 8.6f;

  private static final float FOOTER_SIZE = 7.5f;

  private static final float SIDEBAR_HEADING_SIZE = 8f;
  private static final float SIDEBAR_LABEL_SIZE = 6.6f;
  private static final float SIDEBAR_VALUE_SIZE = 8.2f;

  private static final String BULLET_PREFIX = "•  ";
  private static final String DATE_SEPARATOR = " – ";

  private static final DateTimeFormatter DATE_INPUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter DATE_OUTPUT =
      DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

  /**
   * The least main-column space worth starting a role in. Below this a role begins on
   * the next page; above it the role is allowed to split, because leaving two thirds of
   * a page blank to keep a long role intact costs more than the split does.
   */
  private static final float MIN_GROUP_SPACE = 96f;

  private final ResumeMarkdownParser markdownParser;
  private final ResumePhotoProvider photoProvider;

  /**
   * A unit of main-column content laid out as a whole.
   *
   * <p>{@code gapBefore} is explicit rather than left to {@code setSpacingBefore},
   * because every group gets its own {@link ColumnText} and iText drops leading spacing
   * at the top of a column — which silently collapsed the gap above every section
   * heading.
   *
   * <p>{@code elements} is a supplier because a {@code ColumnText} consumes what it is
   * given: the measuring pass and the drawing pass each need their own copy.
   */
  private record Group(float gapBefore, Supplier<List<Element>> elements) {
  }

  public ResumePdfRenderer(
      final ResumeMarkdownParser markdownParser,
      final ResumePhotoProvider photoProvider
  ) {
    this.markdownParser = markdownParser;
    this.photoProvider = photoProvider;
  }

  /**
   * Renders a CV.
   *
   * @param data the assembled CV content
   * @return the PDF bytes
   */
  public byte[] render(final ResumeData data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document document = new Document(PageSize.A4, 0, 0, 0, 0);
    try {
      PdfWriter writer = PdfWriter.getInstance(document, out);
      document.open();
      new Render(document, writer, data).run();
      document.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Failed to generate PDF CV", e);
    }
    return out.toByteArray();
  }

  /**
   * One render pass. Holds the page-position state that a shared singleton must not.
   */
  private final class Render {

    private final Document document;
    private final ResumeData data;
    private final PdfContentByte canvas;
    private final PdfContentByte background;

    Render(final Document document, final PdfWriter writer, final ResumeData data) {
      this.document = document;
      this.data = data;
      this.canvas = writer.getDirectContent();
      this.background = writer.getDirectContentUnder();
    }

    void run() {
      drawSidebarBand();
      drawPhoto();
      drawSidebarContent();

      float y = drawNameBlock();
      y = flow(profileElements(), y);
      flow(experienceElements(), y);

      // Written last, so it lands on whichever page the content ended on. The canvas
      // accumulates against the current page, so there is nothing to look up.
      drawFooter();
    }

    /**
     * The closing line on the final page. Sourced from the CMS website link rather than
     * hardcoded, so it stays truthful and matches what the sidebar already shows.
     */
    private void drawFooter() {
      String website = data.profile().website();
      if (!isPresent(website)) {
        return;
      }

      Chunk link = new Chunk(shortenUrl(website), ResumeFonts.body(FOOTER_SIZE, ACCENT));
      link.setAnchor(website);

      Paragraph line = new Paragraph();
      line.setAlignment(Element.ALIGN_CENTER);
      line.setLeading(FOOTER_SIZE * 1.35f);
      line.add(new Chunk("See ", ResumeFonts.body(FOOTER_SIZE, INK_MUTED)));
      line.add(link);
      line.add(new Chunk(" for more.", ResumeFonts.body(FOOTER_SIZE, INK_MUTED)));

      ColumnText column = new ColumnText(canvas);
      column.setSimpleColumn(MAIN_LEFT, FOOTER_BOTTOM, MAIN_RIGHT, FOOTER_TOP);
      column.addElement(line);
      column.go();
    }

    // ---------------------------------------------------------------- sidebar

    private void drawSidebarBand() {
      background.setColorFill(SIDEBAR_BG);
      background.rectangle(0, 0, SIDEBAR_WIDTH, PAGE_HEIGHT);
      background.fill();
    }

    private void drawPhoto() {
      Optional<byte[]> bytes = photoProvider.resolve(data.profile().photoUrl());
      if (bytes.isEmpty()) {
        return;
      }
      try {
        // Clip to a circle, then draw the image square inside it. Clipping handles any
        // aspect ratio without the renderer having to reason about crops.
        canvas.saveState();
        canvas.circle(PHOTO_CENTRE_X, PHOTO_CENTRE_Y, PHOTO_RADIUS);
        canvas.clip();
        canvas.newPath();
        Image photo = Image.getInstance(bytes.get());
        photo.scaleAbsolute(PHOTO_RADIUS * 2f, PHOTO_RADIUS * 2f);
        photo.setAbsolutePosition(
            PHOTO_CENTRE_X - PHOTO_RADIUS, PHOTO_CENTRE_Y - PHOTO_RADIUS);
        canvas.addImage(photo);
        canvas.restoreState();

        canvas.setColorStroke(SIDEBAR_ACCENT);
        canvas.setLineWidth(1.1f);
        canvas.circle(PHOTO_CENTRE_X, PHOTO_CENTRE_Y, PHOTO_RADIUS);
        canvas.stroke();
      } catch (IOException | DocumentException e) {
        // A CV without a photo is a complete CV; failing the download is not.
        LOG.warn("Could not draw the CV headshot: {}", e.getMessage());
      }
    }

    private void drawSidebarContent() {
      ResumeProfile profile = data.profile();
      List<Element> elements = new ArrayList<>();

      elements.add(sidebarHeading("Contact", true));
      addContactField(elements, "Location", profile.location());
      addContactField(elements, "Phone", profile.phone());
      addContactField(elements, "Email", profile.email());

      List<String> links = new ArrayList<>();
      addIfPresent(links, profile.linkedIn());
      addIfPresent(links, profile.github());
      addIfPresent(links, profile.website());
      if (!links.isEmpty()) {
        elements.add(sidebarHeading("Links", false));
        for (String link : links) {
          elements.add(sidebarValue(shortenUrl(link), SIDEBAR_TEXT));
        }
      }

      if (!data.skillGroups().isEmpty()) {
        elements.add(sidebarHeading("Skills", false));
        for (ResumeSkillGroup group : data.skillGroups()) {
          elements.add(sidebarValue(group.name(), SIDEBAR_TEXT));
        }
      }

      if (!data.education().isEmpty()) {
        elements.add(sidebarHeading("Education", false));
        for (ResumeJob course : data.education()) {
          elements.addAll(educationElements(course));
        }
      }

      ColumnText column = new ColumnText(canvas);
      column.setSimpleColumn(SIDEBAR_LEFT, MARGIN_BOTTOM, SIDEBAR_RIGHT, SIDEBAR_TOP);
      elements.forEach(column::addElement);
      column.go();
    }

    private List<Element> educationElements(final ResumeJob course) {
      Paragraph heading = new Paragraph();
      heading.setLeading(10.6f);
      heading.setSpacingAfter(1f);
      heading.add(new Chunk(course.title(),
          ResumeFonts.bodyBold(8.4f, SIDEBAR_TEXT)));
      if (isPresent(course.company())) {
        heading.add(Chunk.NEWLINE);
        heading.add(new Chunk(course.company(),
            ResumeFonts.body(7.8f, SIDEBAR_MUTED)));
      }
      List<Element> elements = new ArrayList<>();
      elements.add(heading);

      Paragraph meta = new Paragraph(dateRange(course),
          ResumeFonts.body(7f, SIDEBAR_MUTED));
      meta.setLeading(9.4f);
      meta.setSpacingAfter(isPresent(course.shortDescription()) ? 2f : 8f);
      elements.add(meta);

      if (isPresent(course.shortDescription())) {
        Paragraph detail = new Paragraph(
            plainText(course.shortDescription()),
            ResumeFonts.body(7f, SIDEBAR_MUTED));
        detail.setLeading(9.4f);
        detail.setSpacingAfter(8f);
        elements.add(detail);
      }
      return elements;
    }

    private Element sidebarHeading(final String text, final boolean first) {
      PdfPTable table = new PdfPTable(1);
      table.setWidthPercentage(100);
      table.setSpacingBefore(first ? 0f : 15f);
      table.setSpacingAfter(8f);

      Chunk label = new Chunk(
          text.toUpperCase(Locale.ENGLISH),
          ResumeFonts.display(SIDEBAR_HEADING_SIZE, SIDEBAR_ACCENT));
      label.setCharacterSpacing(1.3f);

      PdfPCell cell = new PdfPCell(new Phrase(label));
      cell.setBorder(Rectangle.BOTTOM);
      cell.setBorderWidthBottom(0.7f);
      cell.setBorderColorBottom(SIDEBAR_RULE);
      cell.setPadding(0f);
      cell.setPaddingBottom(5f);
      table.addCell(cell);
      return table;
    }

    private void addContactField(
        final List<Element> elements, final String label, final String value
    ) {
      if (!isPresent(value)) {
        return;
      }
      Chunk labelChunk = new Chunk(
          label.toUpperCase(Locale.ENGLISH),
          ResumeFonts.body(SIDEBAR_LABEL_SIZE, SIDEBAR_MUTED));
      labelChunk.setCharacterSpacing(0.9f);

      Paragraph paragraph = new Paragraph();
      paragraph.setLeading(9.2f);
      paragraph.setSpacingAfter(7f);
      paragraph.add(labelChunk);
      paragraph.add(Chunk.NEWLINE);
      paragraph.add(new Chunk(value,
          ResumeFonts.body(SIDEBAR_VALUE_SIZE, SIDEBAR_TEXT)));
      elements.add(paragraph);
    }

    private Element sidebarValue(final String text, final Color colour) {
      Paragraph paragraph = new Paragraph(text,
          ResumeFonts.body(SIDEBAR_VALUE_SIZE, colour));
      paragraph.setLeading(11f);
      paragraph.setSpacingAfter(3.4f);
      return paragraph;
    }

    // ------------------------------------------------------------ main column

    private float drawNameBlock() {
      ResumeProfile profile = data.profile();
      float top = PAGE_HEIGHT - 58f;

      ColumnText column = new ColumnText(canvas);
      column.setSimpleColumn(MAIN_LEFT, MARGIN_BOTTOM, MAIN_RIGHT, top);

      Chunk name = new Chunk(
          profile.name().toUpperCase(Locale.ENGLISH),
          ResumeFonts.display(NAME_SIZE, INK));
      name.setCharacterSpacing(0.8f);
      Paragraph nameParagraph = new Paragraph(name);
      nameParagraph.setLeading(NAME_SIZE * 1.1f);
      nameParagraph.setSpacingAfter(3f);
      column.addElement(nameParagraph);

      if (isPresent(profile.title())) {
        Paragraph title = new Paragraph(profile.title(),
            ResumeFonts.bodyBold(TITLE_SIZE, ACCENT));
        title.setLeading(TITLE_SIZE * 1.3f);
        column.addElement(title);
      }
      column.go();
      return column.getYLine();
    }

    private List<Group> profileElements() {
      String summary = summaryText();
      if (summary == null) {
        return List.of();
      }
      return List.of(new Group(18f, () -> {
        List<Element> elements = new ArrayList<>();
        elements.add(sectionHeading("Profile"));
        Paragraph paragraph = new Paragraph(summary,
            ResumeFonts.body(BODY_SIZE + 0.3f, INK_MUTED));
        paragraph.setLeading(BODY_LEADING + 0.6f);
        paragraph.setAlignment(Element.ALIGN_LEFT);
        elements.add(paragraph);
        return elements;
      }));
    }

    /**
     * The CMS summary, falling back to the site headline so the CV still reads as a CV
     * before anyone has filled the field in. The headline is stored upper case for the
     * site's own styling, which on paper is shouting, so it is recased.
     */
    private String summaryText() {
      ResumeProfile profile = data.profile();
      if (isPresent(profile.summary())) {
        return plainText(profile.summary());
      }
      if (isPresent(profile.headline())) {
        return sentenceCase(profile.headline());
      }
      return null;
    }

    private List<Group> experienceElements() {
      List<ResumeJob> employment = data.employment();
      if (employment.isEmpty()) {
        return List.of();
      }

      List<Group> groups = new ArrayList<>();
      for (int i = 0; i < employment.size(); i++) {
        ResumeJob job = employment.get(i);
        boolean detailed = isDetailed(job, i);
        // The section heading travels with the first role. On its own it is a group
        // that fits anywhere, including the last few points of a page, which would
        // strand "EXPERIENCE" at the foot of one page and the job on the next.
        boolean first = i == 0;
        groups.add(new Group(first ? 15f : 0f, () -> {
          List<Element> elements = new ArrayList<>();
          if (first) {
            elements.add(sectionHeading("Experience"));
          }
          elements.addAll(detailed ? detailedJob(job) : compactJob(job));
          return elements;
        }));
      }
      return groups;
    }

    private List<Element> detailedJob(final ResumeJob job) {
      List<Element> elements = new ArrayList<>();
      elements.add(jobHeader(job));
      List<ResumeBlock> blocks = cap(markdownParser.parse(job.longDescription()));
      if (blocks.isEmpty()) {
        addCompactSummary(elements, job);
        return elements;
      }
      for (ResumeBlock block : blocks) {
        elements.add(blockElement(block));
      }
      elements.add(jobSpacer());
      return elements;
    }

    private List<Element> compactJob(final ResumeJob job) {
      List<Element> elements = new ArrayList<>();
      elements.add(jobHeader(job));
      addCompactSummary(elements, job);
      return elements;
    }

    private void addCompactSummary(final List<Element> elements, final ResumeJob job) {
      String summary = compactSummary(job);
      if (summary == null) {
        elements.add(jobSpacer());
        return;
      }
      Paragraph paragraph = new Paragraph(summary, ResumeFonts.body(BODY_SIZE, INK_MUTED));
      paragraph.setLeading(BODY_LEADING);
      paragraph.setSpacingAfter(13f);
      elements.add(paragraph);
    }

    private Element jobSpacer() {
      Paragraph spacer = new Paragraph(" ", ResumeFonts.body(2f, INK));
      spacer.setLeading(2f);
      spacer.setSpacingAfter(8f);
      return spacer;
    }

    private Element blockElement(final ResumeBlock block) {
      return switch (block.kind()) {
        case HEADING -> subHeading(block);
        case BULLET -> bullet(block);
        case PARAGRAPH -> prose(block);
      };
    }

    private Element subHeading(final ResumeBlock block) {
      Chunk chunk = new Chunk(block.plainText(),
          ResumeFonts.bodyBold(SUBHEADING_SIZE, INK));
      chunk.setCharacterSpacing(0.3f);
      Paragraph paragraph = new Paragraph(chunk);
      paragraph.setLeading(SUBHEADING_SIZE * 1.35f);
      paragraph.setSpacingBefore(6.5f);
      paragraph.setSpacingAfter(2.5f);
      return paragraph;
    }

    private Element bullet(final ResumeBlock block) {
      Font prefixFont = ResumeFonts.body(BODY_SIZE, ACCENT);
      float indent = prefixFont.getBaseFont().getWidthPoint(BULLET_PREFIX, BODY_SIZE);

      Paragraph paragraph = new Paragraph();
      paragraph.setLeading(BODY_LEADING);
      paragraph.setSpacingAfter(2.2f);
      // A hanging indent measured from the prefix itself, so wrapped lines line up with
      // the text above them whatever the font or size is changed to.
      paragraph.setIndentationLeft(indent);
      paragraph.setFirstLineIndent(-indent);
      paragraph.add(new Chunk(BULLET_PREFIX, prefixFont));
      addRuns(paragraph, block, INK_MUTED);
      return paragraph;
    }

    private Element prose(final ResumeBlock block) {
      Paragraph paragraph = new Paragraph();
      paragraph.setLeading(BODY_LEADING);
      paragraph.setSpacingAfter(5f);
      addRuns(paragraph, block, INK_MUTED);
      return paragraph;
    }

    private void addRuns(
        final Paragraph paragraph, final ResumeBlock block, final Color colour
    ) {
      for (ResumeTextRun run : block.runs()) {
        Color runColour = run.bold() ? INK : colour;
        paragraph.add(new Chunk(run.text(),
            ResumeFonts.forRun(run, BODY_SIZE, runColour)));
      }
    }

    private Element jobHeader(final ResumeJob job) {
      PdfPTable table = new PdfPTable(2);
      table.setWidthPercentage(100);
      table.setKeepTogether(true);
      table.setSpacingAfter(5f);
      try {
        table.setWidths(new float[]{66f, 34f});
      } catch (DocumentException e) {
        throw new IllegalStateException("Invalid CV job header widths", e);
      }

      Paragraph left = new Paragraph();
      left.setLeading(12.6f);
      left.add(new Chunk(job.title(), ResumeFonts.bodyBold(JOB_TITLE_SIZE, INK)));
      if (isPresent(job.company())) {
        left.add(Chunk.NEWLINE);
        left.add(new Chunk(job.company(), ResumeFonts.bodyBold(COMPANY_SIZE, ACCENT)));
      }

      Paragraph right = new Paragraph();
      right.setLeading(12.6f);
      right.setAlignment(Element.ALIGN_RIGHT);
      right.add(new Chunk(dateRange(job), ResumeFonts.body(META_SIZE, INK_MUTED)));
      if (isPresent(job.location())) {
        right.add(Chunk.NEWLINE);
        right.add(new Chunk(job.location(), ResumeFonts.body(META_SIZE, INK_MUTED)));
      }

      table.addCell(headerCell(left, Element.ALIGN_LEFT));
      table.addCell(headerCell(right, Element.ALIGN_RIGHT));
      return table;
    }

    private PdfPCell headerCell(final Paragraph content, final int alignment) {
      PdfPCell cell = new PdfPCell();
      cell.addElement(content);
      cell.setBorder(Rectangle.NO_BORDER);
      cell.setPadding(0f);
      cell.setHorizontalAlignment(alignment);
      return cell;
    }

    private Element sectionHeading(final String text) {
      PdfPTable table = new PdfPTable(1);
      table.setWidthPercentage(100);
      // No spacingBefore: a section heading always opens a group, and iText drops
      // leading spacing at the top of a column. The gap comes from Group.gapBefore.
      table.setSpacingAfter(9f);

      Chunk label = new Chunk(
          text.toUpperCase(Locale.ENGLISH), ResumeFonts.display(SECTION_SIZE, INK));
      label.setCharacterSpacing(1.4f);

      PdfPCell cell = new PdfPCell(new Phrase(label));
      cell.setBorder(Rectangle.BOTTOM);
      cell.setBorderWidthBottom(0.8f);
      cell.setBorderColorBottom(RULE);
      cell.setPadding(0f);
      cell.setPaddingBottom(6f);
      table.addCell(cell);
      return table;
    }

    // ------------------------------------------------------------------ flow

    /**
     * Lays groups down the main column, starting a page when there is too little room
     * left to begin one.
     *
     * <p>The test is deliberately not "does the whole group fit". Roles vary from four
     * lines to most of a page, and keeping every one intact pushed the entire experience
     * section onto page two while page one sat two thirds empty. What has to stay
     * together is the *start* of a group — its heading and first line or two — so that
     * is what is measured, floored at {@link #MIN_GROUP_SPACE}.
     */
    private float flow(final List<Group> groups, final float startY) {
      float y = startY;
      for (Group group : groups) {
        float available = y - MARGIN_BOTTOM;
        float required = Math.min(measure(group.elements().get()), MIN_GROUP_SPACE);
        if (required > available) {
          y = newPage();
        } else if (y < CONTENT_TOP) {
          y -= group.gapBefore();
        }
        y = draw(group.elements().get(), y);
      }
      return y;
    }

    private float draw(final List<Element> elements, final float startY) {
      ColumnText column = new ColumnText(canvas);
      column.setSimpleColumn(MAIN_LEFT, MARGIN_BOTTOM, MAIN_RIGHT, startY);
      elements.forEach(column::addElement);
      int status = column.go();
      while (ColumnText.hasMoreText(status)) {
        newPage();
        column.setSimpleColumn(MAIN_LEFT, MARGIN_BOTTOM, MAIN_RIGHT, CONTENT_TOP);
        status = column.go();
      }
      return column.getYLine();
    }

    private float measure(final List<Element> elements) {
      ColumnText column = new ColumnText(canvas);
      column.setSimpleColumn(
          MAIN_LEFT, MARGIN_BOTTOM, MAIN_LEFT + MAIN_WIDTH, CONTENT_TOP);
      elements.forEach(column::addElement);
      column.go(true);
      return CONTENT_TOP - column.getYLine();
    }

    private float newPage() {
      document.newPage();
      drawSidebarBand();
      return CONTENT_TOP;
    }
  }

  // ------------------------------------------------------------------ content

  /**
   * Whether a role keeps its full description. Recency or a long tenure, deliberately
   * not position alone — see {@link #LONG_TENURE_YEARS}.
   */
  private boolean isDetailed(final ResumeJob job, final int index) {
    return index < DETAILED_ROLE_COUNT || tenureYears(job) >= LONG_TENURE_YEARS;
  }

  /**
   * Trims a role's blocks to the bullet budget, dropping any sub-heading left with
   * nothing under it. Prose is never truncated — a paragraph cut short reads as a
   * mistake, where a shorter list of bullets does not.
   */
  private List<ResumeBlock> cap(final List<ResumeBlock> blocks) {
    boolean grouped = blocks.stream()
        .anyMatch(block -> block.kind() == ResumeBlock.Kind.HEADING);
    int perSection = grouped ? MAX_BULLETS_PER_HEADING : MAX_BULLETS_UNGROUPED;

    List<ResumeBlock> capped = new ArrayList<>();
    int totalBullets = 0;
    int bulletsInSection = 0;
    boolean bulletWasDropped = false;

    for (ResumeBlock block : blocks) {
      switch (block.kind()) {
        case HEADING -> {
          dropTrailingOrphan(capped, bulletWasDropped);
          bulletsInSection = 0;
          bulletWasDropped = false;
          capped.add(block);
        }
        case BULLET -> {
          if (bulletsInSection < perSection && totalBullets < MAX_BULLETS_PER_ROLE) {
            capped.add(block);
            bulletsInSection++;
            totalBullets++;
          } else {
            bulletWasDropped = true;
          }
        }
        case PARAGRAPH -> capped.add(block);
      }
    }
    dropTrailingOrphan(capped, bulletWasDropped);
    return capped;
  }

  /**
   * Removes a block left stranded by the cap: a sub-heading with nothing under it, or
   * the lead-in sentence of a list that has been trimmed away. A role ending on
   * "including but not limited to:" reads as a truncation bug, which is exactly what it
   * would be.
   */
  private void dropTrailingOrphan(
      final List<ResumeBlock> blocks, final boolean bulletWasDropped
  ) {
    if (blocks.isEmpty()) {
      return;
    }
    ResumeBlock last = blocks.get(blocks.size() - 1);
    if (last.kind() == ResumeBlock.Kind.HEADING) {
      blocks.remove(blocks.size() - 1);
      return;
    }
    if (bulletWasDropped
        && last.kind() == ResumeBlock.Kind.PARAGRAPH
        && last.plainText().stripTrailing().endsWith(":")) {
      blocks.remove(blocks.size() - 1);
    }
  }

  private String compactSummary(final ResumeJob job) {
    if (isPresent(job.shortDescription())) {
      return plainText(job.shortDescription());
    }
    if (isPresent(job.longDescription())) {
      return plainText(job.longDescription());
    }
    return null;
  }

  private String plainText(final String markdown) {
    return markdownParser.parse(markdown).stream()
        .map(ResumeBlock::plainText)
        .reduce((a, b) -> a + " " + b)
        .orElse("")
        .trim();
  }

  private double tenureYears(final ResumeJob job) {
    LocalDate start = parseDate(job.startDate());
    if (start == null) {
      return 0d;
    }
    LocalDate end = job.endDate() == null ? LocalDate.now() : parseDate(job.endDate());
    if (end == null) {
      end = LocalDate.now();
    }
    return (end.toEpochDay() - start.toEpochDay()) / 365.25d;
  }

  private String dateRange(final ResumeJob job) {
    String start = formatDate(job.startDate());
    String end = isPresent(job.endDate()) ? formatDate(job.endDate()) : "Present";
    return start + DATE_SEPARATOR + end;
  }

  private String formatDate(final String value) {
    LocalDate date = parseDate(value);
    return date == null ? String.valueOf(value) : date.format(DATE_OUTPUT);
  }

  private LocalDate parseDate(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim(), DATE_INPUT);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  /** Recases text that arrives entirely upper case; leaves anything else untouched. */
  private static String sentenceCase(final String text) {
    String trimmed = text.trim();
    if (!trimmed.equals(trimmed.toUpperCase(Locale.ENGLISH))) {
      return trimmed;
    }
    String lower = trimmed.toLowerCase(Locale.ENGLISH);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static void addIfPresent(final List<String> target, final String value) {
    if (isPresent(value)) {
      target.add(value);
    }
  }

  /** Strips the parts of a URL a reader does not need, so links fit the sidebar. */
  private static String shortenUrl(final String url) {
    String shortened = url.trim();
    shortened = shortened.replaceFirst("^https?://", "");
    shortened = shortened.replaceFirst("^www\\.", "");
    return shortened.replaceFirst("/$", "");
  }

  private static boolean isPresent(final String value) {
    return value != null && !value.isBlank();
  }
}
