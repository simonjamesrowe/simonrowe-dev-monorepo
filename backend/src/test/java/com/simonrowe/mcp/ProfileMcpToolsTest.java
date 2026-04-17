package com.simonrowe.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.aggregation.AggregatedArticleRepository;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class ProfileMcpToolsTest {

  @Mock
  private ProfileService profileService;

  @Mock
  private SearchService searchService;

  @Mock
  private JobService jobService;

  @Mock
  private SkillGroupService skillGroupService;

  @Mock
  private BlogService blogService;

  @Mock
  private AdminCodeExampleRepository codeExampleRepository;

  @Mock
  private AggregatedArticleRepository articleRepository;

  @Mock
  private AggregatedEventRepository eventRepository;

  @Mock
  private ContactService contactService;

  @Mock
  private ChatContactTracker contactTracker;

  @InjectMocks
  private ProfileMcpTools profileMcpTools;

  @Test
  void getProfileDelegatesToProfileService() {
    final ProfileResponse expectedResponse = sampleProfileResponse();
    given(profileService.getProfile()).willReturn(expectedResponse);

    final ProfileResponse result = profileMcpTools.getProfile();

    assertThat(result).isSameAs(expectedResponse);
    verify(profileService).getProfile();
  }

  @Test
  void searchBlogsDelegatesToBlogSearch() {
    final String query = "spring boot";
    final List<BlogSearchResult> expectedResults = List.of();
    given(searchService.blogSearch(query)).willReturn(expectedResults);

    final List<BlogSearchResult> result = profileMcpTools.searchBlogs(query);

    assertThat(result).isSameAs(expectedResults);
    verify(searchService).blogSearch(query);
    verify(searchService, never()).siteSearch(query);
  }

  @Test
  void getJobsWithoutQueryDelegatesToJobService() {
    final List<JobSummaryDto> expectedJobs = List.of(sampleJobSummaryDto());
    given(jobService.getAllJobs()).willReturn(expectedJobs);

    final Object result = profileMcpTools.getJobs(null);

    assertThat(result).isSameAs(expectedJobs);
    verify(jobService).getAllJobs();
  }

  @Test
  void getJobsWithQueryDelegatesToSearchByType() {
    final String query = "kubernetes";
    final List<SearchResult> expectedResults = List.of(
        new SearchResult("DevOps Engineer", null, "/jobs/1"));
    given(searchService.searchByType(query, "job")).willReturn(expectedResults);

    final Object result = profileMcpTools.getJobs(query);

    assertThat(result).isSameAs(expectedResults);
    verify(searchService).searchByType(query, "job");
    verify(jobService, never()).getAllJobs();
  }

  @Test
  void getJobsWithEmptyQueryDelegatesToJobService() {
    final List<JobSummaryDto> expectedJobs = List.of(sampleJobSummaryDto());
    given(jobService.getAllJobs()).willReturn(expectedJobs);

    final Object result = profileMcpTools.getJobs("  ");

    assertThat(result).isSameAs(expectedJobs);
    verify(jobService).getAllJobs();
  }

  @Test
  void getJobsReturnsErrorWhenSearchUnavailable() {
    given(searchService.searchByType("java", "job"))
        .willThrow(new SearchUnavailableException("Search unavailable"));

    final Object result = profileMcpTools.getJobs("java");

    assertThat(result).isEqualTo(
        "Search is temporarily unavailable. Please try again later.");
  }

  @Test
  void getSkillsWithoutQueryDelegatesToSkillGroupService() {
    final List<SkillGroupSummaryDto> expectedSkills = List.of(sampleSkillGroupSummaryDto());
    given(skillGroupService.getAllSkillGroups()).willReturn(expectedSkills);

    final Object result = profileMcpTools.getSkills(null);

    assertThat(result).isSameAs(expectedSkills);
    verify(skillGroupService).getAllSkillGroups();
  }

  @Test
  void getSkillsWithQueryDelegatesToSearchByType() {
    final String query = "react";
    final List<SearchResult> expectedResults = List.of(
        new SearchResult("Frontend", null, "/skills-groups/1"));
    given(searchService.searchByType(query, "skill")).willReturn(expectedResults);

    final Object result = profileMcpTools.getSkills(query);

    assertThat(result).isSameAs(expectedResults);
    verify(searchService).searchByType(query, "skill");
  }

  @Test
  void searchSiteDelegatesToSearchServiceSiteSearch() {
    final String query = "kubernetes";
    final GroupedSearchResponse expectedResponse = emptyGroupedSearchResponse();
    given(searchService.siteSearch(query)).willReturn(expectedResponse);

    final GroupedSearchResponse result = profileMcpTools.searchSite(query);

    assertThat(result).isSameAs(expectedResponse);
    verify(searchService).siteSearch(query);
  }

  @Test
  void getRecentBlogsDelegatesToBlogService() {
    final List<BlogSummaryResponse> expectedBlogs = List.of();
    given(blogService.getLatest(10)).willReturn(expectedBlogs);

    final List<BlogSummaryResponse> result = profileMcpTools.getRecentBlogs();

    assertThat(result).isSameAs(expectedBlogs);
    verify(blogService).getLatest(10);
  }

  @Test
  void getUpcomingEventsWithoutQueryDelegatesDirectly() {
    given(eventRepository.findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
        org.mockito.ArgumentMatchers.any())).willReturn(List.of());

    final Object result = profileMcpTools.getUpcomingEvents(null);

    assertThat(result).isInstanceOf(List.class);
    verify(searchService, never()).searchByType(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("event"));
  }

  @Test
  void getUpcomingEventsWithQueryDelegatesToSearchByType() {
    final String query = "meetup";
    final List<SearchResult> expectedResults = List.of(
        new SearchResult("London Java Meetup", null, "/events/1"));
    given(searchService.searchByType(query, "event")).willReturn(expectedResults);

    final Object result = profileMcpTools.getUpcomingEvents(query);

    assertThat(result).isSameAs(expectedResults);
    verify(searchService).searchByType(query, "event");
  }

  @Test
  void searchNewsWithQueryDelegatesToSearchByType() {
    final String query = "spring";
    final List<SearchResult> expectedResults = List.of(
        new SearchResult("Spring Boot 4.0", null, "https://example.com"));
    given(searchService.searchByType(query, "news")).willReturn(expectedResults);

    final Object result = profileMcpTools.searchNews(query);

    assertThat(result).isInstanceOf(List.class);
    verify(searchService).searchByType(query, "news");
  }

  @Test
  void searchNewsWithoutQueryReturnsLatestArticles() {
    given(articleRepository.findByVisibleTrueOrderByPublishedDateDesc(
        org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
        .willReturn(org.springframework.data.domain.Page.empty());

    final Object result = profileMcpTools.searchNews(null);

    assertThat(result).isInstanceOf(List.class);
    verify(searchService, never()).searchByType(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("news"));
  }

  @Test
  void submitContactFormSuccessfullyDelegatesAndMarksSesison() {
    ToolContext ctx = new ToolContext(Map.of("sessionId", "sess-1"));
    given(contactTracker.hasSubmitted("sess-1")).willReturn(false);

    String result = profileMcpTools.submitContactForm(
        "John", "Doe", "john@example.com", "Hello", "Hi Simon", ctx);

    assertThat(result).contains("Message sent successfully");
    verify(contactService).submitFromChat(new ContactSubmission(
        "John", "Doe", "john@example.com", "Hello", "Hi Simon", "AI Chat"));
    verify(contactTracker).markSubmitted("sess-1");
  }

  @Test
  void submitContactFormRejectsSecondSubmissionInSameSession() {
    ToolContext ctx = new ToolContext(Map.of("sessionId", "sess-1"));
    given(contactTracker.hasSubmitted("sess-1")).willReturn(true);

    String result = profileMcpTools.submitContactForm(
        "John", "Doe", "john@example.com", "Hello", "Hi Simon", ctx);

    assertThat(result).contains("already been sent");
    verifyNoInteractions(contactService);
    verify(contactTracker, never()).markSubmitted("sess-1");
  }

  @Test
  void submitContactFormDoesNotMarkSessionOnEmailFailure() {
    ToolContext ctx = new ToolContext(Map.of("sessionId", "sess-2"));
    given(contactTracker.hasSubmitted("sess-2")).willReturn(false);
    org.mockito.Mockito.doThrow(new EmailDeliveryException("SMTP error", null))
        .when(contactService).submitFromChat(org.mockito.ArgumentMatchers.any());

    String result = profileMcpTools.submitContactForm(
        "Jane", "Doe", "jane@example.com", "Help", "Need help", ctx);

    assertThat(result).contains("Failed to send message");
    verify(contactTracker, never()).markSubmitted("sess-2");
  }

  @Test
  void submitContactFormRejectsInvalidEmail() {
    ToolContext ctx = new ToolContext(Map.of("sessionId", "sess-3"));
    given(contactTracker.hasSubmitted("sess-3")).willReturn(false);

    String result = profileMcpTools.submitContactForm(
        "John", "Doe", "not-an-email", "Hello", "Hi", ctx);

    assertThat(result).contains("Invalid email");
    verifyNoInteractions(contactService);
  }

  @Test
  void submitContactFormRejectsMissingRequiredFields() {
    ToolContext ctx = new ToolContext(Map.of("sessionId", "sess-4"));
    given(contactTracker.hasSubmitted("sess-4")).willReturn(false);

    String result = profileMcpTools.submitContactForm(
        "", "Doe", "john@example.com", "Hello", "Hi", ctx);

    assertThat(result).contains("All fields are required");
    verifyNoInteractions(contactService);
  }

  private static ProfileResponse sampleProfileResponse() {
    return new ProfileResponse(
        "Simon Rowe",
        "Simon",
        "Rowe",
        "Engineering Leader",
        "Passionate about AI native dev",
        "I build great things.",
        null,
        null,
        null,
        null,
        "London",
        "+447909083522",
        "simon.rowe@gmail.com",
        "",
        "/api/resume",
        List.of()
    );
  }

  private static GroupedSearchResponse emptyGroupedSearchResponse() {
    return new GroupedSearchResponse(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static JobSummaryDto sampleJobSummaryDto() {
    return new JobSummaryDto(
        "j-1",
        "Lead Engineer",
        "Upp",
        "https://upp.ai",
        null,
        "2019-04-15",
        null,
        "London",
        "Short desc",
        false,
        true
    );
  }

  private static SkillGroupSummaryDto sampleSkillGroupSummaryDto() {
    return new SkillGroupSummaryDto(
        "g-1",
        "Spring",
        9.5,
        1,
        null,
        null,
        List.of()
    );
  }
}
