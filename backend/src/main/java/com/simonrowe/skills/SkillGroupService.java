package com.simonrowe.skills;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.media.MediaImageHydrator;
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
  private final MediaImageHydrator mediaImageHydrator;

  public SkillGroupService(
      final SkillGroupRepository skillGroupRepository,
      final SkillRepository skillRepository,
      final JobRepository jobRepository,
      final MediaImageHydrator mediaImageHydrator
  ) {
    this.skillGroupRepository = skillGroupRepository;
    this.skillRepository = skillRepository;
    this.jobRepository = jobRepository;
    this.mediaImageHydrator = mediaImageHydrator;
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
          List<Skill> hydratedSkills = resolvedSkills.stream()
              .map(this::hydrateSkillImage)
              .toList();
          return SkillGroupSummaryDto.fromEntity(
              group,
              mediaImageHydrator.hydrate(group.image(), "medium", "small", "thumbnail"),
              hydratedSkills);
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
              .map(job -> JobReferenceDto.fromEntity(
                  job,
                  mediaImageHydrator.hydrate(
                      job.companyImage(), "small", "thumbnail", "medium")))
              .toList();
          return SkillDetailDto.fromEntity(
              hydrateSkillImage(skill),
              mediaImageHydrator.hydrate(skill.image(), "medium", "small", "thumbnail"),
              jobRefs);
        })
        .toList();

    return SkillGroupDetailDto.fromEntity(
        group,
        mediaImageHydrator.hydrate(group.image(), "medium", "small", "thumbnail"),
        skillDetails);
  }

  private Skill hydrateSkillImage(final Skill skill) {
    return new Skill(
        skill.id(),
        skill.name(),
        skill.rating(),
        skill.displayOrder(),
        skill.description(),
        mediaImageHydrator.hydrate(skill.image(), "medium", "small", "thumbnail")
    );
  }
}
