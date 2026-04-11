package com.simonrowe.mcp;

import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.admin.CodeExample;
import com.simonrowe.blog.BlogService;
import com.simonrowe.blog.BlogSummaryResponse;
import com.simonrowe.employment.JobService;
import com.simonrowe.employment.JobSummaryDto;
import com.simonrowe.profile.ProfileResponse;
import com.simonrowe.profile.ProfileService;
import com.simonrowe.search.GroupedSearchResponse;
import com.simonrowe.search.SearchService;
import com.simonrowe.skills.SkillGroupService;
import com.simonrowe.skills.SkillGroupSummaryDto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ProfileMcpTools {

  private final ProfileService profileService;
  private final SearchService searchService;
  private final JobService jobService;
  private final SkillGroupService skillGroupService;
  private final BlogService blogService;
  private final AdminCodeExampleRepository codeExampleRepository;

  public ProfileMcpTools(
      final ProfileService profileService,
      final SearchService searchService,
      final JobService jobService,
      final SkillGroupService skillGroupService,
      final BlogService blogService,
      final AdminCodeExampleRepository codeExampleRepository) {
    this.profileService = profileService;
    this.searchService = searchService;
    this.jobService = jobService;
    this.skillGroupService = skillGroupService;
    this.blogService = blogService;
    this.codeExampleRepository = codeExampleRepository;
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
  @Tool(description = "Get Simon's most recent blog posts, ordered by date (newest first). "
      + "Returns titles, summaries, tags, skills, and publication dates. Use this when asked "
      + "what Simon has been writing about, his latest posts, or recent blogging activity.")
  public List<BlogSummaryResponse> getRecentBlogs() {
    return blogService.getLatest(10);
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

  @WithSpan
  @Tool(description = "Get code examples from Simon's portfolio. Returns real code "
      + "snippets with titles, descriptions, language, and associated skills. Optionally "
      + "filter by language (java, typescript, yaml, etc.). Use this when asked to show "
      + "code, demonstrate implementation patterns, or provide technical examples.")
  public List<Map<String, Object>> getCodeExamples(
      @ToolParam(description = "Optional language filter: java, typescript, yaml, python, "
          + "kotlin, go, bash. Pass null or empty to return all.")
      final String language) {
    List<CodeExample> examples;
    if (language != null && !language.isBlank()) {
      examples = codeExampleRepository.findByLanguage(
          language.toLowerCase().trim(),
          org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
    } else {
      examples = codeExampleRepository.findAll();
    }
    return examples.stream()
        .map(this::toCodeExampleSummary)
        .toList();
  }

  private Map<String, Object> toCodeExampleSummary(final CodeExample example) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("id", example.id());
    summary.put("title", example.title());
    summary.put("description", example.description());
    summary.put("language", example.language());
    summary.put("code", example.code());
    if (example.skills() != null) {
      summary.put("skills", example.skills().stream()
          .map(s -> s.name())
          .toList());
    }
    return summary;
  }
}
