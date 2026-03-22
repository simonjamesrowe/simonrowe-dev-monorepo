package com.simonrowe.skills;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SkillGroupService {

  private final SkillGroupRepository skillGroupRepository;
  private final SkillRepository skillRepository;
  private final JobRepository jobRepository;

  public SkillGroupService(
      SkillGroupRepository skillGroupRepository,
      SkillRepository skillRepository,
      JobRepository jobRepository
  ) {
    this.skillGroupRepository = skillGroupRepository;
    this.skillRepository = skillRepository;
    this.jobRepository = jobRepository;
  }

  public List<SkillGroupSummaryDto> getAllSkillGroups() {
    List<SkillGroup> groups = skillGroupRepository.findAllByOrderByDisplayOrderAsc();
    List<String> allSkillIds = groups.stream()
        .filter(g -> g.skills() != null)
        .flatMap(g -> g.skills().stream())
        .distinct()
        .toList();
    Map<String, Skill> skillMap = skillRepository.findAllByIdIn(allSkillIds).stream()
        .collect(Collectors.toMap(Skill::id, s -> s));
    return groups.stream()
        .map(group -> {
          List<Skill> resolvedSkills = group.skills() == null
              ? List.of()
              : group.skills().stream()
                  .filter(skillMap::containsKey)
                  .map(skillMap::get)
                  .toList();
          return SkillGroupSummaryDto.fromEntity(group, resolvedSkills);
        })
        .toList();
  }

  public SkillGroupDetailDto getSkillGroupById(String id) {
    SkillGroup group = skillGroupRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Skill group not found with id: " + id));

    List<String> skillIds = group.skills() == null ? List.of() : group.skills();
    List<Skill> skills = skillRepository.findAllByIdIn(skillIds);

    List<String> skillIdentifiers = skills.stream()
        .flatMap(skill -> {
          java.util.List<String> ids = new java.util.ArrayList<>();
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

    List<SkillDetailDto> skillDetails = skills.stream()
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
