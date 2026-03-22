package com.simonrowe.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.simonrowe.employment.JobService;
import com.simonrowe.employment.JobSummaryDto;
import com.simonrowe.profile.ProfileResponse;
import com.simonrowe.profile.ProfileService;
import com.simonrowe.search.GroupedSearchResponse;
import com.simonrowe.search.SearchService;
import com.simonrowe.skills.SkillGroupService;
import com.simonrowe.skills.SkillGroupSummaryDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
  void searchBlogsDelegatesToSearchServiceSiteSearch() {
    final String query = "spring boot";
    final GroupedSearchResponse expectedResponse = emptyGroupedSearchResponse();
    given(searchService.siteSearch(query)).willReturn(expectedResponse);

    final GroupedSearchResponse result = profileMcpTools.searchBlogs(query);

    assertThat(result).isSameAs(expectedResponse);
    verify(searchService).siteSearch(query);
  }

  @Test
  void getJobsDelegatesToJobService() {
    final List<JobSummaryDto> expectedJobs = List.of(sampleJobSummaryDto());
    given(jobService.getAllJobs()).willReturn(expectedJobs);

    final List<JobSummaryDto> result = profileMcpTools.getJobs();

    assertThat(result).isSameAs(expectedJobs);
    verify(jobService).getAllJobs();
  }

  @Test
  void getSkillsDelegatesToSkillGroupService() {
    final List<SkillGroupSummaryDto> expectedSkills = List.of(sampleSkillGroupSummaryDto());
    given(skillGroupService.getAllSkillGroups()).willReturn(expectedSkills);

    final List<SkillGroupSummaryDto> result = profileMcpTools.getSkills();

    assertThat(result).isSameAs(expectedSkills);
    verify(skillGroupService).getAllSkillGroups();
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
  void searchBlogsPassesQueryUnmodifiedToSearchService() {
    final String query = "react typescript 2025";
    final GroupedSearchResponse expectedResponse = emptyGroupedSearchResponse();
    given(searchService.siteSearch(query)).willReturn(expectedResponse);

    profileMcpTools.searchBlogs(query);

    verify(searchService).siteSearch(query);
  }

  @Test
  void searchSitePassesQueryUnmodifiedToSearchService() {
    final String query = "machine learning";
    final GroupedSearchResponse expectedResponse = emptyGroupedSearchResponse();
    given(searchService.siteSearch(query)).willReturn(expectedResponse);

    profileMcpTools.searchSite(query);

    verify(searchService).siteSearch(query);
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
    return new GroupedSearchResponse(List.of(), List.of(), List.of());
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
