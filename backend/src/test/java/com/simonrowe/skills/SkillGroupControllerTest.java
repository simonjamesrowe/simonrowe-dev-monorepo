package com.simonrowe.skills;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.SharedMongoContainer;
import com.simonrowe.blog.BlogSearchRepository;
import com.simonrowe.common.Image;
import com.simonrowe.common.ImageFormat;
import com.simonrowe.common.ImageFormats;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
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

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "management.health.elasticsearch.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.elasticsearch.uris=http://localhost:9200"
})
@AutoConfigureMockMvc
class SkillGroupControllerTest {

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SkillGroupRepository skillGroupRepository;

  @Autowired
  private JobRepository jobRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    jobRepository.deleteAll();
    skillGroupRepository.deleteAll();
  }

  @Test
  void getAllSkillGroupsReturnsGroupsWithSkills() throws Exception {
    Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, "Boot desc", null);
    Skill springMvc = new Skill("s-2", "Spring MVC", 9.0, 2, null, null);
    SkillGroup group = new SkillGroup(
        "g-1", "Spring", "Spring framework", 9.5, 1,
        sampleImage(), List.of(springBoot, springMvc));
    skillGroupRepository.save(group);

    mockMvc.perform(get("/api/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("g-1"))
        .andExpect(jsonPath("$[0].name").value("Spring"))
        .andExpect(jsonPath("$[0].rating").value(9.5))
        .andExpect(jsonPath("$[0].skills.length()").value(2))
        .andExpect(jsonPath("$[0].skills[0].name").value("Spring Boot"))
        .andExpect(jsonPath("$[0].skills[1].name").value("Spring MVC"));
  }

  @Test
  void getAllSkillGroupsReturnsEmptyArrayWhenNoneExist() throws Exception {
    mockMvc.perform(get("/api/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getSkillGroupByIdReturnsDetailWithJobCorrelations() throws Exception {
    Skill springBoot = new Skill("s-1", "Spring Boot", 10.0, 1, "Boot desc", null);
    SkillGroup group = new SkillGroup(
        "g-1", "Spring", null, 9.5, 1, null, List.of(springBoot));
    skillGroupRepository.save(group);

    Job job = new Job(
        "j-1", "Lead Engineer", "Upp", "https://upp.ai", null,
        "2019-04-15", "2020-05-01", "London",
        "Short desc", "Long desc", false, true, List.of("s-1"));
    jobRepository.save(job);

    mockMvc.perform(get("/api/skills/g-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("g-1"))
        .andExpect(jsonPath("$.name").value("Spring"))
        .andExpect(jsonPath("$.skills.length()").value(1))
        .andExpect(jsonPath("$.skills[0].name").value("Spring Boot"))
        .andExpect(jsonPath("$.skills[0].jobs.length()").value(1))
        .andExpect(jsonPath("$.skills[0].jobs[0].title").value("Lead Engineer"))
        .andExpect(jsonPath("$.skills[0].jobs[0].company").value("Upp"));
  }

  @Test
  void getSkillGroupByIdReturnsNotFoundForMissingGroup() throws Exception {
    mockMvc.perform(get("/api/skills/nonexistent"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Skill group not found with id: nonexistent"));
  }

  @Test
  void getAiSkillGroupAppearsWithCorrectDisplayOrderAndSkills() throws Exception {
    Skill claudeCode = new Skill("s-ai-1", "Claude Code", 8.0, 1, "AI coding assistant", null);
    Skill copilot = new Skill("s-ai-2", "GitHub Copilot", 8.0, 2, "AI pair programming", null);
    Skill aiDev = new Skill("s-ai-3", "AI-Assisted Development", 9.0, 3, "AI workflows", null);
    Skill prompts = new Skill("s-ai-4", "Prompt Engineering", 8.0, 4, "LLM prompts", null);
    Skill mcp = new Skill("s-ai-5", "MCP", 7.0, 5, "Model Context Protocol", null);

    SkillGroup aiGroup = new SkillGroup(
        "g-ai", "Artificial Intelligence",
        "Artificial intelligence tools and practices for AI-assisted software development.",
        8.0, 1, null,
        List.of(claudeCode, copilot, aiDev, prompts, mcp));

    SkillGroup springGroup = new SkillGroup(
        "g-spring", "Spring", "Spring framework", 9.5, 3,
        sampleImage(), List.of(new Skill("s-1", "Spring Boot", 10.0, 1, "Boot desc", null)));

    skillGroupRepository.saveAll(List.of(aiGroup, springGroup));

    mockMvc.perform(get("/api/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("Artificial Intelligence"))
        .andExpect(jsonPath("$[0].displayOrder").value(1))
        .andExpect(jsonPath("$[0].rating").value(8.0))
        .andExpect(jsonPath("$[0].skills.length()").value(5))
        .andExpect(jsonPath("$[0].skills[0].name").value("Claude Code"))
        .andExpect(jsonPath("$[0].skills[4].name").value("MCP"));
  }

  @Test
  void getAiSkillGroupByIdShowsGlobalJobCorrelation() throws Exception {
    Skill claudeCode = new Skill("s-ai-1", "Claude Code", 8.0, 1, "AI coding assistant", null);
    SkillGroup aiGroup = new SkillGroup(
        "g-ai", "Artificial Intelligence", "AI tools", 8.0, 1, null, List.of(claudeCode));
    skillGroupRepository.save(aiGroup);

    Job globalJob = new Job(
        "j-global", "Head of Engineering", "Global", "https://global.com", null,
        "2021-08-01", null, "Holborn, London",
        "Short desc", "Long desc", false, true, List.of("s-ai-1"));
    jobRepository.save(globalJob);

    mockMvc.perform(get("/api/skills/g-ai"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Artificial Intelligence"))
        .andExpect(jsonPath("$.skills[0].name").value("Claude Code"))
        .andExpect(jsonPath("$.skills[0].jobs.length()").value(1))
        .andExpect(jsonPath("$.skills[0].jobs[0].title").value("Head of Engineering"))
        .andExpect(jsonPath("$.skills[0].jobs[0].company").value("Global"));
  }

  private static Image sampleImage() {
    ImageFormat thumbnail = new ImageFormat("/uploads/thumbnail_spring.png", 50, 50);
    ImageFormats formats = new ImageFormats(thumbnail, null, null, null);
    return new Image("/uploads/spring.png", "spring.png", 200, 200, "image/png", formats);
  }
}
