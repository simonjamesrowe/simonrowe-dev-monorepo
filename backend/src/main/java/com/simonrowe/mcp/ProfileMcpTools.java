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
  @Tool(description = "Get the site owner's profile information including name, title, "
      + "headline, description, contact details, and social media links")
  public ProfileResponse getProfile() {
    return profileService.getProfile();
  }

  @WithSpan
  @Tool(description = "Search published blog posts by keyword query")
  public GroupedSearchResponse searchBlogs(
      @ToolParam(description = "Search query (2-200 characters)") final String query) {
    return searchService.siteSearch(query);
  }

  @WithSpan
  @Tool(description = "Get the complete employment and job history")
  public List<JobSummaryDto> getJobs() {
    return jobService.getAllJobs();
  }

  @WithSpan
  @Tool(description = "Get all skill groups with their individual skills and ratings")
  public List<SkillGroupSummaryDto> getSkills() {
    return skillGroupService.getAllSkillGroups();
  }

  @WithSpan
  @Tool(description = "Search across all site content including blogs, jobs, and skills")
  public GroupedSearchResponse searchSite(
      @ToolParam(description = "Search query (2-200 characters)") final String query) {
    return searchService.siteSearch(query);
  }
}
