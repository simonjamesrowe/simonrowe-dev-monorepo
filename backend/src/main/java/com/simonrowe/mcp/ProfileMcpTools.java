package com.simonrowe.mcp;

import com.simonrowe.employment.JobService;
import com.simonrowe.employment.JobSummaryDto;
import com.simonrowe.profile.ProfileResponse;
import com.simonrowe.profile.ProfileService;
import com.simonrowe.search.GroupedSearchResponse;
import com.simonrowe.search.SearchService;
import com.simonrowe.skills.SkillGroupService;
import com.simonrowe.skills.SkillGroupSummaryDto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ProfileMcpTools {

  private final ProfileService profileService;
  private final SearchService searchService;
  private final JobService jobService;
  private final SkillGroupService skillGroupService;

  public ProfileMcpTools(
      final ProfileService profileService,
      final SearchService searchService,
      final JobService jobService,
      final SkillGroupService skillGroupService) {
    this.profileService = profileService;
    this.searchService = searchService;
    this.jobService = jobService;
    this.skillGroupService = skillGroupService;
  }

  @WithSpan
  @Tool(description = "Get Simon's profile — returns his full name, professional title, "
      + "headline summary, detailed bio/description, location, email addresses, phone number, "
      + "and social media links (GitHub, LinkedIn, etc.). Use this for personal or background "
      + "questions about who Simon is.")
  public ProfileResponse getProfile() {
    return profileService.getProfile();
  }

  @WithSpan
  @Tool(description = "Search Simon's published blog posts by keyword. Returns matching "
      + "blog entries with titles, summaries, tags, and publication dates. Use this when "
      + "asked about topics Simon has written about, his blog content, or technical articles.")
  public GroupedSearchResponse searchBlogs(
      @ToolParam(description = "Search keywords to match against blog titles and content")
      final String query) {
    return searchService.siteSearch(query);
  }

  @WithSpan
  @Tool(description = "Get Simon's complete employment history — returns all jobs with "
      + "company names, job titles, date ranges, descriptions of responsibilities and "
      + "achievements, and associated skills/technologies. Use this for questions about "
      + "where Simon has worked, his career progression, or current role.")
  public List<JobSummaryDto> getJobs() {
    return jobService.getAllJobs();
  }

  @WithSpan
  @Tool(description = "Get all of Simon's technical skill groups with individual skills "
      + "and proficiency ratings (0-10). Skills are organised into categories like "
      + "programming languages, frameworks, cloud platforms, databases, etc. Use this for "
      + "questions about what technologies Simon knows and how experienced he is with them.")
  public List<SkillGroupSummaryDto> getSkills() {
    return skillGroupService.getAllSkillGroups();
  }

  @WithSpan
  @Tool(description = "Search across all site content — blogs, jobs, and skills. Returns "
      + "grouped results by content type. Use this for broad or general questions that "
      + "might span multiple areas of Simon's portfolio.")
  public GroupedSearchResponse searchSite(
      @ToolParam(description = "Search keywords to match across all site content")
      final String query) {
    return searchService.siteSearch(query);
  }
}
