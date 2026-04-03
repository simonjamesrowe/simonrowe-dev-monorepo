package com.simonrowe.employment;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.media.MediaImageHydrator;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class JobService {

  private final JobRepository jobRepository;
  private final SkillGroupRepository skillGroupRepository;
  private final SkillRepository skillRepository;
  private final MediaImageHydrator mediaImageHydrator;

  public JobService(
      final JobRepository jobRepository,
      final SkillGroupRepository skillGroupRepository,
      final SkillRepository skillRepository,
      final MediaImageHydrator mediaImageHydrator
  ) {
    this.jobRepository = jobRepository;
    this.skillGroupRepository = skillGroupRepository;
    this.skillRepository = skillRepository;
    this.mediaImageHydrator = mediaImageHydrator;
  }

  public List<JobSummaryDto> getAllJobs() {
    return jobRepository.findAllByOrderByStartDateDesc().stream()
        .map(job -> JobSummaryDto.fromEntity(
            job,
            mediaImageHydrator.hydrate(job.companyImage(), "medium", "small", "thumbnail")))
        .toList();
  }

  public JobDetailDto getJobById(String id) {
    Job job = jobRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Job not found with id: " + id));

    List<SkillReferenceDto> resolvedSkills = resolveSkills(job.skills());
    return JobDetailDto.fromEntity(
        job,
        mediaImageHydrator.hydrate(job.companyImage(), "medium", "small", "thumbnail"),
        resolvedSkills);
  }

  private List<SkillReferenceDto> resolveSkills(List<String> skillIdentifiers) {
    if (skillIdentifiers == null || skillIdentifiers.isEmpty()) {
      return List.of();
    }

    Map<String, SkillReferenceDto> skillMap = new HashMap<>();
    List<SkillGroup> allGroups = skillGroupRepository.findAll();

    for (SkillGroup group : allGroups) {
      if (group.skills() == null || group.skills().isEmpty()) {
        continue;
      }
      List<Skill> groupSkills = skillRepository.findAllByIdIn(group.skills());
      for (Skill skill : groupSkills) {
        if (skillIdentifiers.contains(skill.id())
            || skillIdentifiers.contains(skill.name())) {
          skillMap.putIfAbsent(skill.name(), new SkillReferenceDto(
              skill.id(),
              skill.name(),
              skill.rating(),
              mediaImageHydrator.hydrate(skill.image(), "medium", "small", "thumbnail"),
              group.id()
          ));
        }
      }
    }

    return skillIdentifiers.stream()
        .map(identifier -> skillMap.values().stream()
            .filter(ref -> Objects.equals(ref.id(), identifier)
                || Objects.equals(ref.name(), identifier))
            .findFirst()
            .orElse(null))
        .filter(ref -> ref != null)
        .sorted(Comparator.comparing(
            SkillReferenceDto::name, String::compareToIgnoreCase))
        .toList();
  }
}
