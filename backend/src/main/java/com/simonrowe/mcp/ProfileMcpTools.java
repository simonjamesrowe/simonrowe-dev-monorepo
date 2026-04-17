package com.simonrowe.mcp;

import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.admin.CodeExample;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.BlogService;
import com.simonrowe.blog.BlogSummaryResponse;
import com.simonrowe.chat.ChatContactTracker;
import com.simonrowe.contact.ContactService;
import com.simonrowe.contact.ContactSubmission;
import com.simonrowe.contact.EmailDeliveryException;
import com.simonrowe.employment.JobService;
import com.simonrowe.employment.JobSummaryDto;
import com.simonrowe.profile.ProfileResponse;
import com.simonrowe.profile.ProfileService;
import com.simonrowe.search.BlogSearchResult;
import com.simonrowe.search.GroupedSearchResponse;
import com.simonrowe.search.SearchResult;
import com.simonrowe.search.SearchService;
import com.simonrowe.search.SearchUnavailableException;
import com.simonrowe.skills.SkillGroupService;
import com.simonrowe.skills.SkillGroupSummaryDto;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ProfileMcpTools {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileMcpTools.class);
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
  private static final String SEARCH_UNAVAILABLE =
      "Search is temporarily unavailable. Please try again later.";

  private final ProfileService profileService;
  private final SearchService searchService;
  private final JobService jobService;
  private final SkillGroupService skillGroupService;
  private final BlogService blogService;
  private final AdminCodeExampleRepository codeExampleRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final ContactService contactService;
  private final ChatContactTracker contactTracker;

  public ProfileMcpTools(
      final ProfileService profileService,
      final SearchService searchService,
      final JobService jobService,
      final SkillGroupService skillGroupService,
      final BlogService blogService,
      final AdminCodeExampleRepository codeExampleRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository,
      final ContactService contactService,
      final ChatContactTracker contactTracker) {
    this.profileService = profileService;
    this.searchService = searchService;
    this.jobService = jobService;
    this.skillGroupService = skillGroupService;
    this.blogService = blogService;
    this.codeExampleRepository = codeExampleRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.contactService = contactService;
    this.contactTracker = contactTracker;
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
      + "blog entries with titles, summaries, tags, and publication dates, ranked by relevance "
      + "with title and tags weighted higher. Use this when asked about topics Simon has "
      + "written about, his blog content, or technical articles. Returns blog posts only.")
  public List<BlogSearchResult> searchBlogs(
      @ToolParam(description = "Search keywords to match against blog titles and content")
      final String query) {
    try {
      return searchService.blogSearch(query);
    } catch (Exception e) {
      LOG.error("Blog search failed for query: {}", query, e);
      return List.of();
    }
  }

  @WithSpan
  @Tool(description = "Get Simon's employment history. Returns jobs with company names, "
      + "job titles, date ranges, descriptions, and associated skills/technologies. "
      + "Optionally filter by keyword to find jobs related to a specific technology or role. "
      + "Use this for questions about where Simon has worked or his career progression.")
  public Object getJobs(
      @ToolParam(description = "Optional search keywords to filter jobs by technology, "
          + "role, or company. Pass null or empty for all jobs.")
      final String query) {
    if (query != null && !query.isBlank()) {
      try {
        return searchService.searchByType(query, "job");
      } catch (SearchUnavailableException e) {
        return SEARCH_UNAVAILABLE;
      }
    }
    return jobService.getAllJobs();
  }

  @WithSpan
  @Tool(description = "Get Simon's technical skill groups with individual skills "
      + "and proficiency ratings (0-10). Optionally filter by keyword to find skills "
      + "related to a specific technology. Use this for questions about what technologies "
      + "Simon knows and how experienced he is with them.")
  public Object getSkills(
      @ToolParam(description = "Optional search keywords to filter skills by technology. "
          + "Pass null or empty for all skill groups.")
      final String query) {
    if (query != null && !query.isBlank()) {
      try {
        return searchService.searchByType(query, "skill");
      } catch (SearchUnavailableException e) {
        return SEARCH_UNAVAILABLE;
      }
    }
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
  @Tool(description = "Search across all site content — blogs, jobs, skills, news, and "
      + "events. Returns grouped results by content type. Use this for broad or general "
      + "questions that might span multiple areas of Simon's portfolio.")
  public GroupedSearchResponse searchSite(
      @ToolParam(description = "Search keywords to match across all site content")
      final String query) {
    try {
      return searchService.siteSearch(query);
    } catch (Exception e) {
      LOG.error("Site search failed for query: {}", query, e);
      return new GroupedSearchResponse(List.of(), List.of(), List.of(), List.of(), List.of());
    }
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

  @WithSpan
  @Tool(description = "Search aggregated tech news articles from external sources like "
      + "AI Native Dev, Rundown AI, and Spring Blog. Returns recent articles with "
      + "AI-generated summaries and source attribution. Use this when asked about "
      + "recent tech news, industry trends, or what's happening in the tech world.")
  public Object searchNews(
      @ToolParam(description = "Search keywords to match against news article titles "
          + "and summaries. Pass null or empty for latest articles.")
      final String query) {
    if (query != null && !query.isBlank()) {
      try {
        List<SearchResult> results = searchService.searchByType(query, "news");
        return results.stream()
            .map(r -> {
              Map<String, Object> map = new LinkedHashMap<>();
              map.put("title", r.name());
              map.put("url", r.url());
              return map;
            })
            .toList();
      } catch (SearchUnavailableException e) {
        return SEARCH_UNAVAILABLE;
      }
    }
    List<AggregatedArticle> articles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc(
            org.springframework.data.domain.PageRequest.of(0, 10))
        .getContent();
    return articles.stream()
        .map(a -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("title", a.title());
          map.put("summary", a.summary());
          map.put("sourceName", a.sourceName());
          map.put("originalUrl", a.originalUrl());
          map.put("publishedDate", a.publishedDate());
          return map;
        })
        .toList();
  }

  @WithSpan
  @Tool(description = "Get upcoming tech community events like meetups and conferences. "
      + "Optionally filter by keyword. Returns events with dates, venues, and descriptions. "
      + "Use this when asked about upcoming events, meetups, or tech gatherings.")
  public Object getUpcomingEvents(
      @ToolParam(description = "Optional search keywords to filter events. "
          + "Pass null or empty for all upcoming events.")
      final String query) {
    if (query != null && !query.isBlank()) {
      try {
        return searchService.searchByType(query, "event");
      } catch (SearchUnavailableException e) {
        return SEARCH_UNAVAILABLE;
      }
    }
    List<AggregatedEvent> events = eventRepository
        .findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
            java.time.Instant.now());
    return events.stream()
        .limit(10)
        .map(e -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("title", e.title());
          map.put("summary", e.summary());
          map.put("sourceName", e.sourceName());
          map.put("originalUrl", e.originalUrl());
          map.put("eventDate", e.eventDate());
          map.put("venue", e.venue());
          map.put("location", e.location());
          return map;
        })
        .toList();
  }

  @WithSpan
  @Tool(description = "Submit a contact message to Simon on behalf of the visitor. "
      + "Collects the visitor's details and sends an email to Simon. Can only be used "
      + "once per chat session to prevent spam. If the email fails to send, the visitor "
      + "can retry. Use this when a visitor asks to get in touch, send a message, or "
      + "contact Simon.")
  public String submitContactForm(
      @ToolParam(description = "Visitor's first name") final String firstName,
      @ToolParam(description = "Visitor's last name") final String lastName,
      @ToolParam(description = "Visitor's email address") final String email,
      @ToolParam(description = "Message subject") final String subject,
      @ToolParam(description = "Message body") final String message,
      final ToolContext toolContext) {
    String sessionId = (String) toolContext.getContext().get("sessionId");

    if (contactTracker.hasSubmitted(sessionId)) {
      return "A contact message has already been sent in this chat session. "
          + "Only one message per session is allowed.";
    }

    if (isBlank(firstName) || isBlank(lastName) || isBlank(email)
        || isBlank(subject) || isBlank(message)) {
      return "All fields are required: first name, last name, email, subject, and message.";
    }

    if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
      return "Invalid email address. Please provide a valid email.";
    }

    ContactSubmission submission = new ContactSubmission(
        firstName.trim(), lastName.trim(), email.trim(),
        subject.trim(), message.trim(), "AI Chat");

    try {
      contactService.submitFromChat(submission);
      contactTracker.markSubmitted(sessionId);
      return "Message sent successfully to Simon. He will respond to "
          + email.trim() + " soon.";
    } catch (EmailDeliveryException e) {
      LOG.error("Contact form submission failed for session: {}", sessionId, e);
      return "Failed to send message. Please try again.";
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
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
