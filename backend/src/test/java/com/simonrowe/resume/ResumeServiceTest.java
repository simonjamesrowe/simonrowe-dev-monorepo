package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.profile.Profile;
import com.simonrowe.profile.ProfileRepository;
import com.simonrowe.profile.SocialMediaLink;
import com.simonrowe.profile.SocialMediaLinkRepository;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private SocialMediaLinkRepository socialMediaLinkRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private SkillGroupRepository skillGroupRepository;

    @InjectMocks
    private ResumeService resumeService;

    @Test
    void assembleResumeDataReturnsCompleteData() {
        given(profileRepository.findFirstBy())
            .willReturn(Optional.of(sampleProfile()));
        given(socialMediaLinkRepository.findAll())
            .willReturn(List.of(
                sampleSocialLink("linkedin", "https://linkedin.com/in/simon", true),
                sampleSocialLink("github", "https://github.com/simon", true)));
        given(jobRepository.findAllByOrderByStartDateDesc())
            .willReturn(List.of(
                sampleJob("j-1", "Lead", "Upp", false, true),
                sampleJob("j-2", "BSc CS", "University", true, true),
                sampleJob("j-3", "Intern", "Startup", false, false)));
        given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
            .willReturn(List.of(sampleSkillGroup()));

        ResumeData result = resumeService.assembleResumeData();

        assertThat(result.profile().name()).isEqualTo("Simon Rowe");
        assertThat(result.profile().linkedIn()).isEqualTo("https://linkedin.com/in/simon");
        assertThat(result.profile().github()).isEqualTo("https://github.com/simon");
        assertThat(result.employment()).hasSize(1);
        assertThat(result.employment().get(0).title()).isEqualTo("Lead");
        assertThat(result.education()).hasSize(1);
        assertThat(result.education().get(0).title()).isEqualTo("BSc CS");
        assertThat(result.skillGroups()).hasSize(1);
        assertThat(result.skillGroups().get(0).skills()).hasSize(1);
    }

    @Test
    void assembleResumeDataThrowsWhenNoProfile() {
        given(profileRepository.findFirstBy()).willReturn(Optional.empty());

        assertThatThrownBy(resumeService::assembleResumeData)
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assembleResumeDataExcludesJobsNotOnResume() {
        given(profileRepository.findFirstBy())
            .willReturn(Optional.of(sampleProfile()));
        given(socialMediaLinkRepository.findAll()).willReturn(List.of());
        given(jobRepository.findAllByOrderByStartDateDesc())
            .willReturn(List.of(
                sampleJob("j-1", "Lead", "Upp", false, false)));
        given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
            .willReturn(List.of());

        ResumeData result = resumeService.assembleResumeData();

        assertThat(result.employment()).isEmpty();
    }

    private static Profile sampleProfile() {
        Instant now = Instant.now();
        return new Profile(
            "p-1", "Simon Rowe", "Simon", "Rowe",
            "Engineering Leader", "Headline", "Description",
            null, null, null, null,
            "London", "+44123456", "simon@test.com", null,
            "/api/resume", now, now);
    }

    private static SocialMediaLink sampleSocialLink(
        String type, String link, boolean includeOnResume
    ) {
        Instant now = Instant.now();
        return new SocialMediaLink(null, type, type, link, includeOnResume, now, now);
    }

    private static Job sampleJob(
        String id, String title, String company,
        boolean isEducation, boolean includeOnResume
    ) {
        return new Job(
            id, title, company, null, null,
            "2020-01-01", null, "London",
            "Short", "Long desc", isEducation, includeOnResume, List.of());
    }

    private static SkillGroup sampleSkillGroup() {
        Skill skill = new Skill("s-1", "Spring Boot", 10.0, 1, null, null);
        return new SkillGroup("g-1", "Spring", null, 9.5, 1, null, List.of(skill));
    }
}
