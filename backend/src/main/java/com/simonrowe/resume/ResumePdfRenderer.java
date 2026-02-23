package com.simonrowe.resume;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

@Component
public class ResumePdfRenderer {

    private static final Color SIDEBAR_BG = new Color(45, 55, 72);
    private static final Color SIDEBAR_TEXT = Color.WHITE;
    private static final Color HEADING_COLOR = new Color(45, 55, 72);
    private static final Color STAR_FILLED = new Color(234, 179, 8);
    private static final Color STAR_EMPTY = new Color(200, 200, 200);

    private static final Font NAME_FONT =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, SIDEBAR_TEXT);
    private static final Font TITLE_FONT =
        FontFactory.getFont(FontFactory.HELVETICA, 11, SIDEBAR_TEXT);
    private static final Font SIDEBAR_HEADING =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, SIDEBAR_TEXT);
    private static final Font SIDEBAR_BODY =
        FontFactory.getFont(FontFactory.HELVETICA, 9, SIDEBAR_TEXT);
    private static final Font SIDEBAR_SKILL =
        FontFactory.getFont(FontFactory.HELVETICA, 8, SIDEBAR_TEXT);
    private static final Font SECTION_HEADING =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, HEADING_COLOR);
    private static final Font JOB_TITLE_FONT =
        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
    private static final Font JOB_META_FONT =
        FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
    private static final Font BODY_FONT =
        FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);

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
            PdfWriter.getInstance(document, out);
            document.open();

            PdfPTable layout = new PdfPTable(2);
            layout.setWidthPercentage(100);
            layout.setWidths(new float[]{30, 70});

            layout.addCell(buildSidebar(data));
            layout.addCell(buildMainContent(data));

            document.add(layout);
            document.close();
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to generate PDF resume", e);
        }

        return out.toByteArray();
    }

    private PdfPCell buildSidebar(ResumeData data) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(SIDEBAR_BG);
        cell.setPadding(15);
        cell.setBorder(PdfPCell.NO_BORDER);

        ResumeProfile profile = data.profile();

        cell.addElement(new Paragraph(profile.name(), NAME_FONT));
        cell.addElement(new Paragraph(profile.title(), TITLE_FONT));
        cell.addElement(spacer(10));

        cell.addElement(new Paragraph("CONTACT", SIDEBAR_HEADING));
        cell.addElement(spacer(5));
        addContactLine(cell, profile.email());
        addContactLine(cell, profile.phone());
        addContactLine(cell, profile.location());
        cell.addElement(spacer(8));

        if (profile.linkedIn() != null || profile.github() != null
            || profile.website() != null) {
            cell.addElement(new Paragraph("LINKS", SIDEBAR_HEADING));
            cell.addElement(spacer(5));
            addContactLine(cell, profile.linkedIn());
            addContactLine(cell, profile.github());
            addContactLine(cell, profile.website());
            cell.addElement(spacer(8));
        }

        if (!data.skillGroups().isEmpty()) {
            cell.addElement(new Paragraph("SKILLS", SIDEBAR_HEADING));
            cell.addElement(spacer(5));

            for (ResumeSkillGroup group : data.skillGroups()) {
                Paragraph groupName = new Paragraph(group.name(), SIDEBAR_BODY);
                groupName.setSpacingBefore(4);
                cell.addElement(groupName);

                for (ResumeSkill skill : group.skills()) {
                    String stars = buildStarRating(skill.rating());
                    Paragraph skillLine = new Paragraph(
                        skill.name() + "  " + stars, SIDEBAR_SKILL);
                    skillLine.setIndentationLeft(5);
                    cell.addElement(skillLine);
                }
            }
        }

        return cell;
    }

    private PdfPCell buildMainContent(ResumeData data) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(20);
        cell.setBorder(PdfPCell.NO_BORDER);

        if (!data.employment().isEmpty()) {
            cell.addElement(sectionHeading("EXPERIENCE"));

            for (ResumeJob job : data.employment()) {
                addJobEntry(cell, job);
            }
        }

        if (!data.education().isEmpty()) {
            cell.addElement(sectionHeading("EDUCATION"));

            for (ResumeJob job : data.education()) {
                addJobEntry(cell, job);
            }
        }

        return cell;
    }

    private void addJobEntry(PdfPCell cell, ResumeJob job) {
        Paragraph title = new Paragraph(job.title(), JOB_TITLE_FONT);
        title.setSpacingBefore(8);
        cell.addElement(title);

        String dateRange = formatDate(job.startDate()) + " - "
            + (job.endDate() != null ? formatDate(job.endDate()) : "Present");
        String meta = job.company() + " | " + job.location() + " | " + dateRange;
        Paragraph metaParagraph = new Paragraph(meta, JOB_META_FONT);
        metaParagraph.setSpacingAfter(4);
        cell.addElement(metaParagraph);

        if (job.longDescription() != null && !job.longDescription().isBlank()) {
            String plainText = markdownToPlainText(job.longDescription());
            Paragraph body = new Paragraph(plainText, BODY_FONT);
            body.setSpacingAfter(8);
            cell.addElement(body);
        }
    }

    private Paragraph sectionHeading(String text) {
        Paragraph heading = new Paragraph(text, SECTION_HEADING);
        heading.setSpacingBefore(12);
        heading.setSpacingAfter(4);
        return heading;
    }

    private void addContactLine(PdfPCell cell, String value) {
        if (value != null && !value.isBlank()) {
            cell.addElement(new Paragraph(value, SIDEBAR_BODY));
        }
    }

    private Paragraph spacer(float height) {
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(height);
        return spacer;
    }

    private String buildStarRating(Double rating) {
        if (rating == null) {
            return "";
        }
        int filledStars = (int) Math.round(rating / 2.0);
        int totalStars = 5;
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < totalStars; i++) {
            stars.append(i < filledStars ? "\u2605" : "\u2606");
        }
        return stars.toString();
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
}
