package com.simonrowe.resume;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

@Component
public class ResumePdfRenderer {
  private static final int DETAILED_EMPLOYMENT_LIMIT = 5;

  private static final float PAGE_WIDTH = PageSize.A4.getWidth();
  private static final float PAGE_HEIGHT = PageSize.A4.getHeight();

  private static final float SIDEBAR_WIDTH = 175f;
  private static final float SIDEBAR_MARGIN_LEFT = 35f;
  private static final float SIDEBAR_MARGIN_RIGHT = 20f;
  private static final float MAIN_X = 186f;
  private static final float MAIN_WIDTH = 380f;
  private static final float CONTENT_START_Y = PAGE_HEIGHT - 170f;
  private static final float PAGE_MARGIN = 20f;

  private static final Color SIDEBAR_BG = new Color(0xDD, 0xDD, 0xDD);

  private static final Font NAME_FONT =
      FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, Color.BLACK);
  private static final Font HEADLINE_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);
  private static final Font SIDEBAR_HEADING_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
  private static final Font SIDEBAR_LABEL_FONT =
      FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Color.BLACK);
  private static final Font SIDEBAR_VALUE_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 7, Color.BLACK);
  private static final Font SKILL_NAME_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 7, Color.BLACK);
  private static final Font SKILL_STARS_FONT =
      FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

  private static final Font EMPLOYMENT_HEADING_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
  private static final Font JOB_TITLE_FONT =
      FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
  private static final Font JOB_LOCATION_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 7, Color.BLACK);
  private static final Font JOB_DATE_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
  private static final Font JOB_DESC_FONT =
      FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);

  private static final DateTimeFormatter DATE_INPUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter DATE_OUTPUT =
      DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

  private final Parser markdownParser = Parser.builder().build();
  private final TextContentRenderer textRenderer =
      TextContentRenderer.builder().build();

  public byte[] render(ResumeData data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try {
      Document document = new Document(PageSize.A4, 0, 0, 0, 0);
      PdfWriter writer = PdfWriter.getInstance(document, out);
      document.open();

      PdfContentByte cb = writer.getDirectContent();
      PdfContentByte cbUnder = writer.getDirectContentUnder();

      drawSidebarBackground(cbUnder);
      drawHeadlineBox(cb, data);
      drawSidebar(cb, data);
      drawMainContent(document, writer, data);

      document.close();
    } catch (DocumentException e) {
      throw new RuntimeException("Failed to generate PDF CV", e);
    }

    return out.toByteArray();
  }

  private void drawSidebarBackground(PdfContentByte cb) {
    cb.setColorFill(SIDEBAR_BG);
    cb.rectangle(0, 0, SIDEBAR_WIDTH, PAGE_HEIGHT);
    cb.fill();
  }

  private void drawHeadlineBox(PdfContentByte cb, ResumeData data) {
    final ResumeProfile profile = data.profile();

    float boxWidth = PAGE_WIDTH * 0.65f;
    float boxX = (PAGE_WIDTH - boxWidth) / 2f + 30f;
    float boxHeight = 90f;
    float boxY = PAGE_HEIGHT - 50f - boxHeight;

    cb.setColorFill(Color.WHITE);
    cb.setColorStroke(Color.BLACK);
    cb.setLineWidth(1.5f);
    cb.rectangle(boxX, boxY, boxWidth, boxHeight);
    cb.fillStroke();

    float textX = boxX + 20f;
    float textWidth = boxWidth - 40f;

    ColumnText ct = new ColumnText(cb);
    ct.setSimpleColumn(textX, boxY + 5f, textX + textWidth, boxY + boxHeight - 10f);

    Paragraph name = new Paragraph(profile.name().toUpperCase(), NAME_FONT);
    name.setAlignment(Element.ALIGN_CENTER);
    name.setSpacingAfter(4);
    ct.addElement(name);

    if (profile.headline() != null && !profile.headline().isBlank()) {
      Paragraph headline = new Paragraph(
          profile.headline().toUpperCase(), HEADLINE_FONT);
      headline.setAlignment(Element.ALIGN_CENTER);
      ct.addElement(headline);
    }

    ct.go();
  }

  private void drawSidebar(PdfContentByte cb, ResumeData data) {
    ResumeProfile profile = data.profile();

    float llx = SIDEBAR_MARGIN_LEFT;
    float urx = SIDEBAR_WIDTH - SIDEBAR_MARGIN_RIGHT;

    ColumnText ct = new ColumnText(cb);
    ct.setSimpleColumn(llx, PAGE_MARGIN, urx, CONTENT_START_Y);

    addSidebarSection(ct, "INFO");
    addContactField(ct, "PHONE", profile.phone());
    addContactField(ct, "EMAIL", profile.email());
    if (profile.website() != null && !profile.website().isBlank()) {
      addContactField(ct, "WEBSITE", profile.website());
    }

    addSidebarSection(ct, "LINKS");
    if (profile.linkedIn() != null) {
      addSidebarLink(ct, profile.linkedIn());
    }
    if (profile.github() != null) {
      addSidebarLink(ct, profile.github());
    }

    if (!data.skillGroups().isEmpty()) {
      addSidebarSection(ct, "SKILLS");
      for (ResumeSkillGroup group : data.skillGroups()) {
        Paragraph skillName = new Paragraph(group.name(), SKILL_NAME_FONT);
        skillName.setSpacingBefore(0);
        skillName.setSpacingAfter(0);
        ct.addElement(skillName);

        Paragraph stars = new Paragraph(
            buildStarRating(group.rating()), SKILL_STARS_FONT);
        stars.setSpacingBefore(0);
        stars.setSpacingAfter(2);
        ct.addElement(stars);
      }
    }

    ct.go();
  }

  private void drawMainContent(Document document, PdfWriter writer, ResumeData data) {
    float llx = MAIN_X + 10f;
    float urx = MAIN_X + MAIN_WIDTH - 20f;

    PdfContentByte cb = writer.getDirectContent();
    ColumnText ct = new ColumnText(cb);
    ct.setSimpleColumn(llx, PAGE_MARGIN, urx, CONTENT_START_Y);

    if (!data.employment().isEmpty()) {
      addEmploymentSection(ct, "Employment History");
      for (int i = 0; i < data.employment().size(); i++) {
        ResumeJob job = data.employment().get(i);
        if (i < DETAILED_EMPLOYMENT_LIMIT) {
          addJobBlock(ct, job);
        } else {
          addCompactJobBlock(ct, job);
        }
      }
    }

    if (!data.education().isEmpty()) {
      addEmploymentSection(ct, "Education");
      for (ResumeJob job : data.education()) {
        addJobBlock(ct, job);
      }
    }

    int status = ct.go();

    while (ColumnText.hasMoreText(status)) {
      document.newPage();
      drawSidebarBackground(writer.getDirectContentUnder());
      ct.setSimpleColumn(
          llx, PAGE_MARGIN, urx, PAGE_HEIGHT - PAGE_MARGIN);
      status = ct.go();
    }
  }

  private void addSidebarSection(ColumnText ct, String heading) {
    PdfPTable table = new PdfPTable(1);
    table.setWidthPercentage(100);
    table.setSpacingBefore(10);

    PdfPCell cell = new PdfPCell(new Phrase(heading.toUpperCase(), SIDEBAR_HEADING_FONT));
    cell.setBorder(PdfPCell.BOTTOM);
    cell.setBorderWidthBottom(1f);
    cell.setBorderColorBottom(Color.BLACK);
    cell.setPaddingBottom(4f);
    cell.setPaddingLeft(0);
    table.addCell(cell);

    ct.addElement(table);

    Paragraph spacer = new Paragraph(" ");
    spacer.setSpacingAfter(5);
    ct.addElement(spacer);
  }

  private void addContactField(ColumnText ct, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    Paragraph labelP = new Paragraph(label, SIDEBAR_LABEL_FONT);
    labelP.setSpacingBefore(0);
    labelP.setSpacingAfter(0);
    ct.addElement(labelP);

    Paragraph valueP = new Paragraph(value, SIDEBAR_VALUE_FONT);
    valueP.setSpacingBefore(0);
    valueP.setSpacingAfter(8);
    ct.addElement(valueP);
  }

  private void addSidebarLink(ColumnText ct, String url) {
    Paragraph link = new Paragraph(url, SIDEBAR_VALUE_FONT);
    link.setSpacingBefore(0);
    link.setSpacingAfter(2);
    ct.addElement(link);
  }

  private void addEmploymentSection(ColumnText ct, String heading) {
    PdfPTable table = new PdfPTable(1);
    table.setWidthPercentage(100);
    table.setSpacingBefore(5);

    PdfPCell cell = new PdfPCell(
        new Phrase(heading.toUpperCase(), EMPLOYMENT_HEADING_FONT));
    cell.setBorder(PdfPCell.BOTTOM);
    cell.setBorderWidthBottom(1f);
    cell.setBorderColorBottom(Color.BLACK);
    cell.setPaddingBottom(4f);
    cell.setPaddingLeft(0);
    table.addCell(cell);

    ct.addElement(table);

    Paragraph spacer = new Paragraph(" ");
    spacer.setSpacingAfter(5);
    ct.addElement(spacer);
  }

  private void addJobBlock(ColumnText ct, ResumeJob job) {
    PdfPTable titleRow = new PdfPTable(2);
    titleRow.setWidthPercentage(100);
    try {
      titleRow.setWidths(new float[]{80, 20});
    } catch (DocumentException e) {
      throw new RuntimeException(e);
    }
    titleRow.setSpacingBefore(0);

    String titleCompany = job.title();
    if (job.company() != null && !job.company().isBlank()) {
      titleCompany += ", " + job.company();
    }

    PdfPCell titleCell = new PdfPCell(new Phrase(titleCompany, JOB_TITLE_FONT));
    titleCell.setBorder(PdfPCell.NO_BORDER);
    titleCell.setPadding(0);
    titleCell.setHorizontalAlignment(Element.ALIGN_LEFT);
    titleCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
    titleRow.addCell(titleCell);

    PdfPCell locationCell = new PdfPCell(
        new Phrase(job.location() != null ? job.location() : "", JOB_LOCATION_FONT));
    locationCell.setBorder(PdfPCell.NO_BORDER);
    locationCell.setPadding(0);
    locationCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
    locationCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
    titleRow.addCell(locationCell);

    ct.addElement(titleRow);

    String dateRange = formatDate(job.startDate()) + " - "
        + (job.endDate() != null ? formatDate(job.endDate()) : "Present");
    Paragraph dateParagraph = new Paragraph(dateRange, JOB_DATE_FONT);
    dateParagraph.setSpacingBefore(0);
    dateParagraph.setSpacingAfter(2);
    dateParagraph.setIndentationLeft(2);
    ct.addElement(dateParagraph);

    if (job.longDescription() != null && !job.longDescription().isBlank()) {
      String plainText = markdownToPlainText(job.longDescription());
      Paragraph desc = new Paragraph(plainText, JOB_DESC_FONT);
      desc.setSpacingBefore(0);
      desc.setSpacingAfter(10);
      desc.setIndentationLeft(2);
      ct.addElement(desc);
    }
  }

  private void addCompactJobBlock(ColumnText ct, ResumeJob job) {
    Paragraph title = new Paragraph(job.title(), JOB_TITLE_FONT);
    title.setSpacingBefore(0);
    title.setSpacingAfter(2);
    ct.addElement(title);

    String dateRange = formatDate(job.startDate()) + " - "
        + (job.endDate() != null ? formatDate(job.endDate()) : "Present");
    Paragraph dateParagraph = new Paragraph(dateRange, JOB_DATE_FONT);
    dateParagraph.setSpacingBefore(0);
    dateParagraph.setSpacingAfter(2);
    dateParagraph.setIndentationLeft(2);
    ct.addElement(dateParagraph);

    String compactSummary = compactSummary(job);
    if (compactSummary != null) {
      Paragraph summary = new Paragraph(compactSummary, JOB_DESC_FONT);
      summary.setSpacingBefore(0);
      summary.setSpacingAfter(8);
      summary.setIndentationLeft(2);
      ct.addElement(summary);
    }
  }

  private String buildStarRating(Double rating) {
    if (rating == null) {
      return "";
    }
    int stars = rating.intValue();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < stars; i++) {
      sb.append("* ");
    }
    return sb.toString();
  }

  private String formatDate(String dateStr) {
    try {
      LocalDate date = LocalDate.parse(dateStr, DATE_INPUT);
      return date.format(DATE_OUTPUT);
    } catch (Exception e) {
      return dateStr;
    }
  }

  private String markdownToPlainText(String markdown) {
    Node document = markdownParser.parse(markdown);
    return textRenderer.render(document).trim();
  }

  private String compactSummary(ResumeJob job) {
    if (job.shortDescription() != null && !job.shortDescription().isBlank()) {
      return markdownToPlainText(job.shortDescription());
    }
    if (job.company() != null && !job.company().isBlank()) {
      return job.company();
    }
    if (job.longDescription() != null && !job.longDescription().isBlank()) {
      return markdownToPlainText(job.longDescription());
    }
    return null;
  }
}
