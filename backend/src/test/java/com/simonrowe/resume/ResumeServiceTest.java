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
    assertThat(result.employment()).hasSize(2);
    assertThat(result.employment().get(0).title()).isEqualTo("Lead");
    assertThat(result.employment().get(0).shortDescription()).isEqualTo("Short");
    assertThat(result.employment().get(1).title()).isEqualTo("Intern");
    assertThat(result.education()).hasSize(1);
    assertThat(result.education().get(0).title()).isEqualTo("BSc CS");
    assertThat(result.skillGroups()).hasSize(1);
    assertThat(result.skillGroups().get(0).name()).isEqualTo("Spring");
    assertThat(result.skillGroups().get(0).rating()).isEqualTo(9.5);
  }

  @Test
  void assembleResumeDataThrowsWhenNoProfile() {
    given(profileRepository.findFirstBy()).willReturn(Optional.empty());

    assertThatThrownBy(resumeService::assembleResumeData)
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void assembleResumeDataIncludesAllNonEducationJobs() {
    given(profileRepository.findFirstBy())
        .willReturn(Optional.of(sampleProfile()));
    given(socialMediaLinkRepository.findAll()).willReturn(List.of());
    given(jobRepository.findAllByOrderByStartDateDesc())
        .willReturn(List.of(
            sampleJob("j-1", "Job 1", "Co1", false, true),
            sampleJob("j-2", "Job 2", "Co2", false, true),
            sampleJob("j-3", "Job 3", "Co3", false, true),
            sampleJob("j-4", "Job 4", "Co4", false, true),
            sampleJob("j-5", "Job 5", "Co5", false, true),
            sampleJob("j-6", "Job 6", "Co6", false, true),
            sampleJob("j-7", "Job 7", "Co7", false, true)));
    given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .willReturn(List.of());

    ResumeData result = resumeService.assembleResumeData();

    assertThat(result.employment()).hasSize(7);
    assertThat(result.employment().get(0).title()).isEqualTo("Job 1");
    assertThat(result.employment().get(6).title()).isEqualTo("Job 7");
  }

  @Test
  void assembleResumeDataIncludesJobsNotMarkedForResume() {
    given(profileRepository.findFirstBy())
        .willReturn(Optional.of(sampleProfile()));
    given(socialMediaLinkRepository.findAll()).willReturn(List.of());
    given(jobRepository.findAllByOrderByStartDateDesc())
        .willReturn(List.of(
            sampleJob("j-1", "Lead", "Upp", false, false)));
    given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .willReturn(List.of());

    ResumeData result = resumeService.assembleResumeData();

    assertThat(result.employment()).hasSize(1);
    assertThat(result.employment().get(0).title()).isEqualTo("Lead");
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
    return new SkillGroup("g-1", "Spring", null, 9.5, 1, null, null);
  }
}
