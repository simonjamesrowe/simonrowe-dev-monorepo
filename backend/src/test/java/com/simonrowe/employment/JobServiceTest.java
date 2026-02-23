package com.simonrowe.employment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private SkillGroupRepository skillGroupRepository;

    @InjectMocks
    private JobService jobService;

    @Test
    void getAllJobsReturnsSortedSummaries() {
        Job job1 = sampleJob("j-1", "Lead Engineer", "Upp", "2019-04-15");
        Job job2 = sampleJob("j-2", "Senior Dev", "ASOS", "2017-01-01");
        given(jobRepository.findAllByOrderByStartDateDesc())
            .willReturn(List.of(job1, job2));

        List<JobSummaryDto> result = jobService.getAllJobs();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Lead Engineer");
        assertThat(result.get(0).company()).isEqualTo("Upp");
        assertThat(result.get(1).title()).isEqualTo("Senior Dev");
    }

    @Test
    void getJobByIdReturnsDetailWithResolvedSkills() {
        Job job = new Job(
            "j-1", "Lead Engineer", "Upp", "https://upp.ai", null,
            "2019-04-15", "2020-05-01", "London",
            "Short desc", "Long desc", false, true,
            List.of("s-1", "s-2"));

        Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, null, null);
        Skill springMvc = new Skill("s-2", "Spring MVC", 9.0, 2, null, null);
        SkillGroup springGroup = new SkillGroup(
            "g-1", "Spring", null, 9.5, 1, null,
            List.of(springBoot, springMvc));

        given(jobRepository.findById("j-1")).willReturn(Optional.of(job));
        given(skillGroupRepository.findAll()).willReturn(List.of(springGroup));

        JobDetailDto result = jobService.getJobById("j-1");

        assertThat(result.title()).isEqualTo("Lead Engineer");
        assertThat(result.longDescription()).isEqualTo("Long desc");
        assertThat(result.skills()).hasSize(2);
        assertThat(result.skills())
            .extracting(SkillReferenceDto::name)
            .containsExactly("Spring Boot", "Spring MVC");
        assertThat(result.skills())
            .extracting(SkillReferenceDto::skillGroupId)
            .containsOnly("g-1");
    }

    @Test
    void getJobByIdThrowsNotFoundForMissingJob() {
        given(jobRepository.findById("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJobById("missing"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Job not found");
    }

    @Test
    void getJobByIdHandlesNullSkillsList() {
        Job job = new Job(
            "j-1", "Junior Dev", "Startup", null, null,
            "2015-01-01", "2016-01-01", "London",
            "Short", "Long", false, true, null);

        given(jobRepository.findById("j-1")).willReturn(Optional.of(job));

        JobDetailDto result = jobService.getJobById("j-1");

        assertThat(result.skills()).isEmpty();
    }

    @Test
    void getJobByIdHandlesUnresolvedSkillIds() {
        Job job = new Job(
            "j-1", "Dev", "Company", null, null,
            "2018-01-01", null, "London",
            "Short", "Long", false, true,
            List.of("s-1", "s-nonexistent"));

        Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, null, null);
        SkillGroup group = new SkillGroup(
            "g-1", "Spring", null, 9.5, 1, null, List.of(springBoot));

        given(jobRepository.findById("j-1")).willReturn(Optional.of(job));
        given(skillGroupRepository.findAll()).willReturn(List.of(group));

        JobDetailDto result = jobService.getJobById("j-1");

        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("Spring Boot");
    }

    private static Job sampleJob(
        String id, String title, String company, String startDate
    ) {
        return new Job(
            id, title, company, null, null, startDate, null,
            "London", "Short desc", "Long desc", false, true, List.of());
    }
}
