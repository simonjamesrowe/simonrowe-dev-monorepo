package com.simonrowe.employment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.blog.BlogSearchRepository;
import com.simonrowe.common.Image;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "management.health.elasticsearch.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.elasticsearch.uris=http://localhost:9200"
})
@AutoConfigureMockMvc
@Testcontainers
class JobControllerTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JobRepository jobRepository;

  @Autowired
  private SkillGroupRepository skillGroupRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    jobRepository.deleteAll();
    skillGroupRepository.deleteAll();
  }

  @Test
  void getAllJobsReturnsJobsInReverseChronologicalOrder() throws Exception {
    Job job1 = new Job(
        "j-1", "Lead Engineer", "Upp", "https://upp.ai", null,
        "2019-04-15", "2020-05-01", "London",
        "Short desc 1", "Long desc 1", false, true, List.of());
    Job job2 = new Job(
        "j-2", "BSc Computer Science", "University", null, null,
        "2008-09-01", "2011-06-01", "Leeds",
        "Short desc 2", "Long desc 2", true, true, List.of());
    jobRepository.saveAll(List.of(job1, job2));

    mockMvc.perform(get("/api/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("j-1"))
        .andExpect(jsonPath("$[0].title").value("Lead Engineer"))
        .andExpect(jsonPath("$[0].isEducation").value(false))
        .andExpect(jsonPath("$[1].id").value("j-2"))
        .andExpect(jsonPath("$[1].isEducation").value(true));
  }

  @Test
  void getAllJobsReturnsEmptyArrayWhenNoneExist() throws Exception {
    mockMvc.perform(get("/api/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getJobByIdReturnsDetailWithResolvedSkills() throws Exception {
    Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, null, null);
    SkillGroup group = new SkillGroup(
        "g-1", "Spring", null, 9.5, 1, null, List.of(springBoot));
    skillGroupRepository.save(group);

    Job job = new Job(
        "j-1", "Lead Engineer", "Upp", "https://upp.ai", null,
        "2019-04-15", "2020-05-01", "London",
        "Short desc", "Long **markdown** desc", false, true,
        List.of("s-1"));
    jobRepository.save(job);

    mockMvc.perform(get("/api/jobs/j-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("j-1"))
        .andExpect(jsonPath("$.title").value("Lead Engineer"))
        .andExpect(jsonPath("$.longDescription").value("Long **markdown** desc"))
        .andExpect(jsonPath("$.skills.length()").value(1))
        .andExpect(jsonPath("$.skills[0].name").value("Spring Boot"))
        .andExpect(jsonPath("$.skills[0].rating").value(10.0))
        .andExpect(jsonPath("$.skills[0].skillGroupId").value("g-1"));
  }

  @Test
  void getJobByIdReturnsNotFoundForMissingJob() throws Exception {
    mockMvc.perform(get("/api/jobs/nonexistent"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Job not found with id: nonexistent"));
  }

  @Test
  void getJobByIdExcludesLongDescriptionFromSummaryList() throws Exception {
    Job job = new Job(
        "j-1", "Dev", "Company", null, null,
        "2020-01-01", null, "London",
        "Short", "This long description should not appear in list", false, true,
        List.of());
    jobRepository.save(job);

    mockMvc.perform(get("/api/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].longDescription").doesNotExist());
  }

  @Test
  void getGlobalJobAppearsInJobListWithCorrectFields() throws Exception {
    Image globalLogo = new Image(
        "/uploads/global-logo.jpg", "global-logo.jpg", null, null, "image/jpeg", null);
    Job globalJob = new Job(
        "j-global", "Head of Engineering", "Global", "https://global.com", globalLogo,
        "2021-08-01", null, "Holborn, London",
        "Head of Engineering for Commercial Technology at Global.",
        "## Role Overview\n\nFull markdown description",
        false, true, List.of("s-ai-1", "s-cloud-1"));

    Skill claudeCode = new Skill("s-ai-1", "Claude Code", 8.0, 1, "AI coding assistant", null);
    SkillGroup aiGroup = new SkillGroup(
        "g-ai", "AI", "AI tools", 8.0, 1, null, List.of(claudeCode));

    Skill terraform = new Skill("s-cloud-1", "Terraform", 7.0, 16, "IaC tool", null);
    SkillGroup cloudGroup = new SkillGroup(
        "g-cloud", "Cloud", "Cloud skills", 7.0, 4, null, List.of(terraform));

    skillGroupRepository.saveAll(List.of(aiGroup, cloudGroup));
    jobRepository.save(globalJob);

    mockMvc.perform(get("/api/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Head of Engineering"))
        .andExpect(jsonPath("$[0].company").value("Global"))
        .andExpect(jsonPath("$[0].startDate").value("2021-08-01"))
        .andExpect(jsonPath("$[0].endDate").isEmpty())
        .andExpect(jsonPath("$[0].location").value("Holborn, London"))
        .andExpect(jsonPath("$[0].companyImage.url").value("/uploads/global-logo.jpg"));
  }

  @Test
  void getGlobalJobByIdReturnsResolvedSkillsFromMultipleGroups() throws Exception {
    Skill claudeCode = new Skill("s-ai-1", "Claude Code", 8.0, 1, "AI coding assistant", null);
    Skill copilot = new Skill("s-ai-2", "GitHub Copilot", 8.0, 2, "AI pair programming", null);
    SkillGroup aiGroup = new SkillGroup(
        "g-ai", "AI", "AI tools", 8.0, 1, null, List.of(claudeCode, copilot));

    Skill terraform = new Skill("s-cloud-1", "Terraform", 7.0, 16, "IaC tool", null);
    Skill springBoot = new Skill("s-spring-1", "Spring Boot", 10.0, 1, "Boot framework", null);
    SkillGroup cloudGroup = new SkillGroup(
        "g-cloud", "Cloud", null, 7.0, 4, null, List.of(terraform));
    SkillGroup springGroup = new SkillGroup(
        "g-spring", "Spring", null, 10.0, 3, null, List.of(springBoot));

    Skill react = new Skill("s-web-1", "React", 8.0, 1, "UI library", null);
    SkillGroup webGroup = new SkillGroup(
        "g-web", "Web", null, 8.0, 8, null, List.of(react));

    skillGroupRepository.saveAll(List.of(aiGroup, cloudGroup, springGroup, webGroup));

    Job globalJob = new Job(
        "j-global", "Head of Engineering", "Global", "https://global.com", null,
        "2021-08-01", null, "Holborn, London",
        "Short desc", "Long desc",
        false, true,
        List.of("s-ai-1", "s-ai-2", "s-cloud-1", "s-spring-1", "s-web-1"));
    jobRepository.save(globalJob);

    mockMvc.perform(get("/api/jobs/j-global"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Head of Engineering"))
        .andExpect(jsonPath("$.company").value("Global"))
        .andExpect(jsonPath("$.skills.length()").value(5))
        .andExpect(jsonPath("$.longDescription").exists());
  }
}
