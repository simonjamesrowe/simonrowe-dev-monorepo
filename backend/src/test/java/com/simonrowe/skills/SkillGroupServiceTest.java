package com.simonrowe.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.simonrowe.common.Image;
import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillGroupServiceTest {

  @Mock
  private SkillGroupRepository skillGroupRepository;

  @Mock
  private JobRepository jobRepository;

  @InjectMocks
  private SkillGroupService skillGroupService;

  @Test
  void getAllSkillGroupsReturnsSortedSummaries() {
    SkillGroup group1 = sampleSkillGroup("g-1", "Spring", 9.5, 1);
    SkillGroup group2 = sampleSkillGroup("g-2", "JavaScript", 8.0, 2);
    given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .willReturn(List.of(group1, group2));

    List<SkillGroupSummaryDto> result = skillGroupService.getAllSkillGroups();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).name()).isEqualTo("Spring");
    assertThat(result.get(0).rating()).isEqualTo(9.5);
    assertThat(result.get(1).name()).isEqualTo("JavaScript");
  }

  @Test
  void getAllSkillGroupsIncludesNestedSkills() {
    Skill skill = new Skill("s-1", "Spring Boot", 10.0, 1, "Boot desc", null);
    SkillGroup group = new SkillGroup("g-1", "Spring", null, 9.5, 1, null, List.of(skill));
    given(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .willReturn(List.of(group));

    List<SkillGroupSummaryDto> result = skillGroupService.getAllSkillGroups();

    assertThat(result.get(0).skills()).hasSize(1);
    assertThat(result.get(0).skills().get(0).name()).isEqualTo("Spring Boot");
    assertThat(result.get(0).skills().get(0).rating()).isEqualTo(10.0);
  }

  @Test
  void getSkillGroupByIdReturnsDetailWithJobCorrelations() {
    Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, null, null);
    Skill springMvc = new Skill("s-2", "Spring MVC", 9.0, 2, null, null);
    SkillGroup group = new SkillGroup(
        "g-1", "Spring", "Spring Framework", 9.5, 1, null,
        List.of(springBoot, springMvc));

    Job job1 = sampleJob("j-1", "Lead Engineer", "Upp", "2019-04-15",
        List.of("s-1", "s-2"));
    Job job2 = sampleJob("j-2", "Senior Dev", "ASOS", "2017-01-01",
        List.of("s-1"));

    given(skillGroupRepository.findById("g-1")).willReturn(Optional.of(group));
    given(jobRepository.findBySkillsIn(List.of("s-1", "s-2")))
        .willReturn(List.of(job1, job2));

    SkillGroupDetailDto result = skillGroupService.getSkillGroupById("g-1");

    assertThat(result.name()).isEqualTo("Spring");
    assertThat(result.skills()).hasSize(2);

    SkillDetailDto bootDetail = result.skills().get(0);
    assertThat(bootDetail.name()).isEqualTo("Spring Boot");
    assertThat(bootDetail.jobs()).hasSize(2);
    assertThat(bootDetail.jobs().get(0).title()).isEqualTo("Lead Engineer");
    assertThat(bootDetail.jobs().get(1).title()).isEqualTo("Senior Dev");

    SkillDetailDto mvcDetail = result.skills().get(1);
    assertThat(mvcDetail.name()).isEqualTo("Spring MVC");
    assertThat(mvcDetail.jobs()).hasSize(1);
    assertThat(mvcDetail.jobs().get(0).title()).isEqualTo("Lead Engineer");
  }

  @Test
  void getSkillGroupByIdThrowsNotFoundForMissingGroup() {
    given(skillGroupRepository.findById("missing")).willReturn(Optional.empty());

    assertThatThrownBy(() -> skillGroupService.getSkillGroupById("missing"))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Skill group not found");
  }

  @Test
  void getSkillGroupByIdHandlesNullSkillsList() {
    SkillGroup group = new SkillGroup(
        "g-1", "Empty", null, 0.0, 1, null, null);
    given(skillGroupRepository.findById("g-1")).willReturn(Optional.of(group));

    SkillGroupDetailDto result = skillGroupService.getSkillGroupById("g-1");

    assertThat(result.skills()).isEmpty();
  }

  private static SkillGroup sampleSkillGroup(
      String id, String name, Double rating, Integer displayOrder
  ) {
    Skill skill = new Skill(
        id + "-skill", name + " Skill", rating, 1, null, null);
    return new SkillGroup(id, name, null, rating, displayOrder, null, List.of(skill));
  }

  private static Job sampleJob(
      String id, String title, String company, String startDate, List<String> skills
  ) {
    return new Job(
        id, title, company, null, null, startDate, null,
        "London", "Short desc", "Long desc", false, true, skills);
  }
}
