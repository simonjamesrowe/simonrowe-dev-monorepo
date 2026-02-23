package com.simonrowe.employment;

import com.simonrowe.common.ResourceNotFoundException;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final SkillGroupRepository skillGroupRepository;

    public JobService(
        JobRepository jobRepository,
        SkillGroupRepository skillGroupRepository
    ) {
        this.jobRepository = jobRepository;
        this.skillGroupRepository = skillGroupRepository;
    }

    public List<JobSummaryDto> getAllJobs() {
        return jobRepository.findAllByOrderByStartDateDesc().stream()
            .map(JobSummaryDto::fromEntity)
            .toList();
    }

    public JobDetailDto getJobById(String id) {
        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Job not found with id: " + id));

        List<SkillReferenceDto> resolvedSkills = resolveSkills(job.skills());
        return JobDetailDto.fromEntity(job, resolvedSkills);
    }

    private List<SkillReferenceDto> resolveSkills(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }

        Map<String, SkillReferenceDto> skillMap = new HashMap<>();
        List<SkillGroup> allGroups = skillGroupRepository.findAll();

        for (SkillGroup group : allGroups) {
            if (group.skills() == null) {
                continue;
            }
            for (Skill skill : group.skills()) {
                if (skillIds.contains(skill.id())) {
                    skillMap.put(skill.id(), new SkillReferenceDto(
                        skill.id(),
                        skill.name(),
                        skill.rating(),
                        skill.image(),
                        group.id()
                    ));
                }
            }
        }

        return skillIds.stream()
            .map(skillMap::get)
            .filter(ref -> ref != null)
            .sorted(Comparator.comparing(
                SkillReferenceDto::name, String::compareToIgnoreCase))
            .toList();
    }
}
