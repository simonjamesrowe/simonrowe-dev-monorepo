package com.simonrowe.mcp;

import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.admin.CodeExample;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.BlogService;
import com.simonrowe.blog.BlogSummaryResponse;
import com.simonrowe.chat.BlogWidgetPayload;
import com.simonrowe.chat.ChatStreamPublisher;
import com.simonrowe.chat.ChatContactTracker;
import com.simonrowe.chat.CodeWidgetPayload;
import com.simonrowe.chat.EmploymentWidgetPayload;
import com.simonrowe.chat.EventWidgetPayload;
import com.simonrowe.chat.NewsWidgetPayload;
import com.simonrowe.chat.SkillsWidgetPayload;
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
  private static final String SKILLS_LABEL = "Looking up Simon's skills";
  private static final String JOBS_LABEL = "Pulling up employment history";
  private static final String CODE_LABEL = "Fetching code examples";
  private static final String BLOGS_LABEL = "Searching blog posts";
  private static final String NEWS_LABEL = "Searching tech news";
  private static final String EVENTS_LABEL = "Finding upcoming events";

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
  private final ChatStreamPublisher streamPublisher;

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
      final ChatContactTracker contactTracker,
      final ChatStreamPublisher streamPublisher) {
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
    this.streamPublisher = streamPublisher;
  }

  @WithSpan
  @Tool(description = "Get Simon's profile — returns his full name, professional title, "
      + "headline summary, detailed bio/description, location, email addresses, phone number, "
      + "and social media links (GitHub, LinkedIn, etc.). Use this for personal or background "
      + "questions about who Simon is.")
  public ProfileResponse getProfile() {
    return profileService.getProfile();
  }

  public List<BlogSearchResult> searchBlogs(final String query) {
    return searchBlogs(query, null);
  }

  @WithSpan
  @Tool(description = "Search Simon's published blog posts by keyword. Returns matching "
      + "blog entries with titles, summaries, tags, and publication dates, ranked by relevance "
      + "with title and tags weighted higher. Use this when asked about topics Simon has "
      + "written about, his blog content, or technical articles. Returns blog posts only.")
  public List<BlogSearchResult> searchBlogs(
      @ToolParam(description = "Search keywords to match against blog titles and content")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, BLOGS_LABEL);
    try {
      List<BlogSearchResult> results = searchService.blogSearch(query);
      publishWidgetIfNotEmpty(sessionId, "blogs", toBlogPayload(results));
      return results;
    } catch (Exception e) {
      LOG.error("Blog search failed for query: {}", query, e);
      return List.of();
    } finally {
      publishToolEnd(sessionId, BLOGS_LABEL);
    }
  }

  public Object getJobs(final String query) {
    return getJobs(query, null);
  }

  @WithSpan
  @Tool(description = "Get Simon's employment history. Returns jobs with company names, "
      + "job titles, date ranges, descriptions, and associated skills/technologies. "
      + "Optionally filter by keyword to find jobs related to a specific technology or role. "
      + "Use this for questions about where Simon has worked or his career progression.")
  public Object getJobs(
      @ToolParam(description = "Optional search keywords to filter jobs by technology, "
          + "role, or company. Pass null or empty for all jobs.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, JOBS_LABEL);
    try {
      if (query != null && !query.isBlank()) {
        try {
          return searchService.searchByType(query, "job");
        } catch (SearchUnavailableException e) {
          return SEARCH_UNAVAILABLE;
        }
      }
      List<JobSummaryDto> jobs = jobService.getAllJobs();
      publishWidgetIfNotEmpty(sessionId, "employment", toEmploymentPayload(jobs));
      return jobs;
    } finally {
      publishToolEnd(sessionId, JOBS_LABEL);
    }
  }

  public Object getSkills(final String query) {
    return getSkills(query, null);
  }

  @WithSpan
  @Tool(description = "Get Simon's technical skill groups with individual skills "
      + "and proficiency ratings (0-10). Optionally filter by keyword to find skills "
      + "related to a specific technology. Use this for questions about what technologies "
      + "Simon knows and how experienced he is with them.")
  public Object getSkills(
      @ToolParam(description = "Optional search keywords to filter skills by technology. "
          + "Pass null or empty for all skill groups.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, SKILLS_LABEL);
    try {
      if (query != null && !query.isBlank()) {
        try {
          return searchService.searchByType(query, "skill");
        } catch (SearchUnavailableException e) {
          return SEARCH_UNAVAILABLE;
        }
      }
      List<SkillGroupSummaryDto> groups = skillGroupService.getAllSkillGroups();
      publishWidgetIfNotEmpty(sessionId, "skills", toSkillsPayload(groups));
      return groups;
    } finally {
      publishToolEnd(sessionId, SKILLS_LABEL);
    }
  }

  public List<BlogSummaryResponse> getRecentBlogs() {
    return getRecentBlogs(null);
  }

  @WithSpan
  @Tool(description = "Get Simon's most recent blog posts, ordered by date (newest first). "
      + "Returns titles, summaries, tags, skills, and publication dates. Use this when asked "
      + "what Simon has been writing about, his latest posts, or recent blogging activity.")
  public List<BlogSummaryResponse> getRecentBlogs(final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, BLOGS_LABEL);
    try {
      List<BlogSummaryResponse> blogs = blogService.getLatest(10);
      publishWidgetIfNotEmpty(sessionId, "blogs", toBlogSummaryPayload(blogs));
      return blogs;
    } finally {
      publishToolEnd(sessionId, BLOGS_LABEL);
    }
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

  public List<Map<String, Object>> getCodeExamples(final String language) {
    return getCodeExamples(language, null);
  }

  @WithSpan
  @Tool(description = "Get code examples from Simon's portfolio. Returns real code "
      + "snippets with titles, descriptions, language, and associated skills. Optionally "
      + "filter by language (java, typescript, yaml, etc.). Use this when asked to show "
      + "code, demonstrate implementation patterns, or provide technical examples.")
  public List<Map<String, Object>> getCodeExamples(
      @ToolParam(description = "Optional language filter: java, typescript, yaml, python, "
          + "kotlin, go, bash. Pass null or empty to return all.")
      final String language,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, CODE_LABEL);
    List<CodeExample> examples;
    try {
      if (language != null && !language.isBlank()) {
        examples = codeExampleRepository.findByLanguage(
            language.toLowerCase().trim(),
            org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
      } else {
        examples = codeExampleRepository.findAll();
      }
      publishWidgetIfNotEmpty(sessionId, "code", toCodePayload(examples));
      return examples.stream()
          .map(this::toCodeExampleSummary)
          .toList();
    } finally {
      publishToolEnd(sessionId, CODE_LABEL);
    }
  }

  public Object searchNews(final String query) {
    return searchNews(query, null);
  }

  @WithSpan
  @Tool(description = "Search aggregated tech news articles from external sources like "
      + "AI Native Dev, Rundown AI, and Spring Blog. Returns recent articles with "
      + "AI-generated summaries and source attribution. Use this when asked about "
      + "recent tech news, industry trends, or what's happening in the tech world.")
  public Object searchNews(
      @ToolParam(description = "Search keywords to match against news article titles "
          + "and summaries. Pass null or empty for latest articles.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, NEWS_LABEL);
    try {
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
      publishWidgetIfNotEmpty(sessionId, "news", toNewsPayload(articles));
      return articles.stream()
          .map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", a.title());
            map.put("summary", a.summary());
            map.put("sourceName", a.sourceName());
            map.put("originalUrl", a.originalUrl());
            map.put("publishedDate", a.publishedDate());
            map.put("imageUrl", a.imageUrl());
            return map;
          })
          .toList();
    } finally {
      publishToolEnd(sessionId, NEWS_LABEL);
    }
  }

  public Object getUpcomingEvents(final String query) {
    return getUpcomingEvents(query, null);
  }

  @WithSpan
  @Tool(description = "Get upcoming tech community events like meetups and conferences. "
      + "Optionally filter by keyword. Returns events with dates, venues, and descriptions. "
      + "Use this when asked about upcoming events, meetups, or tech gatherings.")
  public Object getUpcomingEvents(
      @ToolParam(description = "Optional search keywords to filter events. "
          + "Pass null or empty for all upcoming events.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, EVENTS_LABEL);
    try {
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
      List<AggregatedEvent> limitedEvents = events.stream().limit(10).toList();
      publishWidgetIfNotEmpty(sessionId, "events", toEventPayload(limitedEvents));
      return limitedEvents.stream()
          .map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", e.title());
            map.put("summary", e.summary());
            map.put("sourceName", e.sourceName());
            map.put("originalUrl", e.originalUrl());
            map.put("eventDate", e.eventDate());
            map.put("eventEndDate", e.eventEndDate());
            map.put("venue", e.venue());
            map.put("location", e.location());
            return map;
          })
          .toList();
    } finally {
      publishToolEnd(sessionId, EVENTS_LABEL);
    }
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

  private static String sessionId(final ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    Object value = toolContext.getContext().get("sessionId");
    return value instanceof String id && !id.isBlank() ? id : null;
  }

  private void publishToolStart(final String sessionId, final String label) {
    if (sessionId != null) {
      streamPublisher.toolStart(sessionId, label);
    }
  }

  private void publishToolEnd(final String sessionId, final String label) {
    if (sessionId != null) {
      streamPublisher.toolEnd(sessionId, label);
    }
  }

  private void publishWidgetIfNotEmpty(
      final String sessionId, final String kind, final Object payload) {
    if (sessionId == null || isEmptyPayload(payload)) {
      return;
    }
    streamPublisher.widget(sessionId, kind, payload);
  }

  private static boolean isEmptyPayload(final Object payload) {
    return switch (payload) {
      case SkillsWidgetPayload skills -> skills.groups().isEmpty();
      case EmploymentWidgetPayload employment -> employment.jobs().isEmpty();
      case CodeWidgetPayload code -> code.examples().isEmpty();
      case BlogWidgetPayload blogs -> blogs.posts().isEmpty();
      case NewsWidgetPayload news -> news.articles().isEmpty();
      case EventWidgetPayload events -> events.events().isEmpty();
      default -> payload == null;
    };
  }

  private static SkillsWidgetPayload toSkillsPayload(
      final List<SkillGroupSummaryDto> groups) {
    return new SkillsWidgetPayload(groups.stream()
        .map(group -> new SkillsWidgetPayload.Group(
            group.id(),
            group.name(),
            group.skills() == null ? List.of() : group.skills().stream()
                .map(skill -> new SkillsWidgetPayload.Skill(
                    skill.name(),
                    skill.rating() == null ? null : (int) Math.round(skill.rating())))
                .toList()))
        .toList());
  }

  private static EmploymentWidgetPayload toEmploymentPayload(
      final List<JobSummaryDto> jobs) {
    return new EmploymentWidgetPayload(jobs.stream()
        .map(job -> new EmploymentWidgetPayload.Job(
            job.id(),
            job.company(),
            job.title(),
            job.startDate(),
            job.endDate(),
            job.shortDescription(),
            List.of()))
        .toList());
  }

  private static CodeWidgetPayload toCodePayload(final List<CodeExample> examples) {
    return new CodeWidgetPayload(examples.stream()
        .map(example -> new CodeWidgetPayload.Example(
            example.id(),
            example.title(),
            example.description(),
            example.language(),
            example.code(),
            example.skills() == null ? List.of() : example.skills().stream()
                .map(skill -> skill.name())
                .toList()))
        .toList());
  }

  private static BlogWidgetPayload toBlogSummaryPayload(
      final List<BlogSummaryResponse> blogs) {
    return new BlogWidgetPayload(blogs.stream()
        .map(blog -> new BlogWidgetPayload.Post(
            blog.id(),
            blog.title(),
            blog.shortDescription(),
            blog.tags() == null ? List.of() : blog.tags().stream()
                .map(tag -> tag.name())
                .toList(),
            blog.createdDate() == null ? null : blog.createdDate().toString(),
            blog.url(),
            blog.featuredImageUrl()))
        .toList());
  }

  private static BlogWidgetPayload toBlogPayload(final List<BlogSearchResult> blogs) {
    return new BlogWidgetPayload(blogs.stream()
        .map(blog -> new BlogWidgetPayload.Post(
            null,
            blog.title(),
            blog.shortDescription(),
            List.of(),
            blog.publishedDate() == null ? null : blog.publishedDate().toString(),
            blog.url(),
            blog.image()))
        .toList());
  }

  private static NewsWidgetPayload toNewsPayload(final List<AggregatedArticle> articles) {
    return new NewsWidgetPayload(articles.stream()
        .map(article -> new NewsWidgetPayload.Article(
            article.id(),
            article.title(),
            article.summary(),
            article.sourceName(),
            article.originalUrl(),
            article.publishedDate() == null ? null : article.publishedDate().toString(),
            article.imageUrl()))
        .toList());
  }

  private static EventWidgetPayload toEventPayload(final List<AggregatedEvent> events) {
    return new EventWidgetPayload(events.stream()
        .map(event -> new EventWidgetPayload.Event(
            event.id(),
            event.title(),
            event.summary(),
            event.sourceName(),
            event.originalUrl(),
            event.eventDate() == null ? null : event.eventDate().toString(),
            event.eventEndDate() == null ? null : event.eventEndDate().toString(),
            event.venue(),
            event.location(),
            null))
        .toList());
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
