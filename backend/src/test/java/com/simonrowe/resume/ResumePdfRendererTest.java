package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResumePdfRendererTest {

    private final ResumePdfRenderer renderer = new ResumePdfRenderer();

    @Test
    void renderProducesNonEmptyPdfBytes() {
        ResumeData data = sampleResumeData();

        byte[] pdf = renderer.render(data);

        assertThat(pdf).isNotEmpty();
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(pdf[1]).isEqualTo((byte) 'P');
        assertThat(pdf[2]).isEqualTo((byte) 'D');
        assertThat(pdf[3]).isEqualTo((byte) 'F');
    }

    @Test
    void renderHandlesEmptyCollections() {
        ResumeData data = new ResumeData(
            new ResumeProfile("Name", "Title", "email", "phone",
                "London", null, null, null),
            List.of(), List.of(), List.of());

        byte[] pdf = renderer.render(data);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    void renderHandlesMarkdownInDescriptions() {
        ResumeJob job = new ResumeJob(
            "Lead", "Company", "2020-01-01", null, "London",
            "**Bold** text and _italic_ with `code`");
        ResumeData data = new ResumeData(
            new ResumeProfile("Name", "Title", "email", "phone",
                "London", null, null, null),
            List.of(job), List.of(), List.of());

        byte[] pdf = renderer.render(data);

        assertThat(pdf).isNotEmpty();
    }

    private static ResumeData sampleResumeData() {
        ResumeProfile profile = new ResumeProfile(
            "Simon Rowe", "Engineering Leader",
            "simon@test.com", "+44123456", "London",
            "https://linkedin.com/in/simon",
            "https://github.com/simon",
            "https://simonrowe.dev");

        ResumeJob employment = new ResumeJob(
            "Lead Engineer", "Upp Technologies",
            "2019-04-15", "2020-05-01", "London",
            "Lead engineer working on all verticals.");

        ResumeJob education = new ResumeJob(
            "BSc Computer Science", "University of Leeds",
            "2008-09-01", "2011-06-01", "Leeds",
            "First class honours degree.");

        ResumeSkill skill = new ResumeSkill("Spring Boot", 10.0);
        ResumeSkillGroup group = new ResumeSkillGroup("Spring", List.of(skill));

        return new ResumeData(profile, List.of(employment),
            List.of(education), List.of(group));
    }
}
