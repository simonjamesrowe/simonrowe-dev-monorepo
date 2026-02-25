package com.simonrowe.skills;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SkillGroupService {

  private final SkillGroupRepository skillGroupRepository;
  private final JobRepository jobRepository;

  public SkillGroupService(
      SkillGroupRepository skillGroupRepository,
      JobRepository jobRepository
  ) {
    this.skillGroupRepository = skillGroupRepository;
    this.jobRepository = jobRepository;
  }

  public List<SkillGroupSummaryDto> getAllSkillGroups() {
    return skillGroupRepository.findAllByOrderByDisplayOrderAsc().stream()
        .map(SkillGroupSummaryDto::fromEntity)
        .toList();
  }

  public SkillGroupDetailDto getSkillGroupById(String id) {
    SkillGroup group = skillGroupRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Skill group not found with id: " + id));

    List<String> skillIdentifiers = group.skills() == null
        ? List.of()
        : group.skills().stream()
            .flatMap(skill -> {
              List<String> ids = new ArrayList<>();
              if (skill.id() != null) {
                ids.add(skill.id());
              }
              if (skill.name() != null) {
                ids.add(skill.name());
              }
              return ids.stream();
            })
            .distinct()
            .toList();

    List<Job> relatedJobs = skillIdentifiers.isEmpty()
        ? List.of()
        : jobRepository.findBySkillsIn(skillIdentifiers);

    List<SkillDetailDto> skillDetails = group.skills() == null
        ? List.of()
        : group.skills().stream()
            .sorted(Comparator.comparingInt(
                s -> s.displayOrder() != null ? s.displayOrder() : 0))
            .map(skill -> {
              List<JobReferenceDto> jobRefs = relatedJobs.stream()
                  .filter(job -> job.skills() != null
                      && (job.skills().contains(skill.id())
                          || job.skills().contains(skill.name())))
                  .sorted(Comparator.comparing(
                      Job::startDate,
                      Comparator.nullsLast(Comparator.reverseOrder())))
                  .map(JobReferenceDto::fromEntity)
                  .toList();
              return SkillDetailDto.fromEntity(skill, jobRefs);
            })
            .toList();

    return SkillGroupDetailDto.fromEntity(group, skillDetails);
  }
}
