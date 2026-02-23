package com.simonrowe.resume;

import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.profile.Profile;
import com.simonrowe.profile.ProfileRepository;
import com.simonrowe.profile.SocialMediaLink;
import com.simonrowe.profile.SocialMediaLinkRepository;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResumeService {

  private final ProfileRepository profileRepository;
  private final SocialMediaLinkRepository socialMediaLinkRepository;
  private final JobRepository jobRepository;
  private final SkillGroupRepository skillGroupRepository;

  public ResumeService(
      ProfileRepository profileRepository,
      SocialMediaLinkRepository socialMediaLinkRepository,
      JobRepository jobRepository,
      SkillGroupRepository skillGroupRepository
  ) {
    this.profileRepository = profileRepository;
    this.socialMediaLinkRepository = socialMediaLinkRepository;
    this.jobRepository = jobRepository;
    this.skillGroupRepository = skillGroupRepository;
  }

  public ResumeData assembleResumeData() {
    Profile profile = profileRepository.findFirstBy()
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No profile data available for resume generation"));

    List<SocialMediaLink> socialLinks = socialMediaLinkRepository.findAll();

    ResumeProfile resumeProfile = buildResumeProfile(profile, socialLinks);

    List<Job> allJobs = jobRepository.findAllByOrderByStartDateDesc();

    List<ResumeJob> employment = allJobs.stream()
        .filter(job -> Boolean.TRUE.equals(job.includeOnResume())
            && !Boolean.TRUE.equals(job.isEducation()))
        .map(this::toResumeJob)
        .toList();

    List<ResumeJob> education = allJobs.stream()
        .filter(job -> Boolean.TRUE.equals(job.isEducation()))
        .map(this::toResumeJob)
        .toList();

    List<ResumeSkillGroup> skillGroups =
        skillGroupRepository.findAllByOrderByDisplayOrderAsc().stream()
            .map(this::toResumeSkillGroup)
            .toList();

    return new ResumeData(resumeProfile, employment, education, skillGroups);
  }

  private ResumeProfile buildResumeProfile(
      Profile profile, List<SocialMediaLink> socialLinks
  ) {
    String linkedIn = findSocialLink(socialLinks, "linkedin");
    String github = findSocialLink(socialLinks, "github");
    String website = findSocialLink(socialLinks, "website");

    return new ResumeProfile(
        profile.name(),
        profile.title(),
        profile.primaryEmail(),
        profile.phoneNumber(),
        profile.location(),
        linkedIn,
        github,
        website
    );
  }

  private String findSocialLink(List<SocialMediaLink> links, String type) {
    return links.stream()
        .filter(link -> type.equalsIgnoreCase(link.type())
            && Boolean.TRUE.equals(link.includeOnResume()))
        .map(SocialMediaLink::link)
        .findFirst()
        .orElse(null);
  }

  private ResumeJob toResumeJob(Job job) {
    return new ResumeJob(
        job.title(),
        job.company(),
        job.startDate(),
        job.endDate(),
        job.location(),
        job.longDescription()
    );
  }

  private ResumeSkillGroup toResumeSkillGroup(SkillGroup group) {
    List<ResumeSkill> skills = group.skills() == null
        ? List.of()
        : group.skills().stream()
            .sorted(Comparator.comparingInt(
                s -> s.displayOrder() != null ? s.displayOrder() : 0))
            .map(skill -> new ResumeSkill(skill.name(), skill.rating()))
            .toList();

    return new ResumeSkillGroup(group.name(), skills);
  }
}
