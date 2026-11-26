package com.simonrowe.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.dashboard.UptimeReport.Incident;
import com.simonrowe.dashboard.UptimeReport.Incidents;
import com.simonrowe.migration.changeunits.V068CreateUptimeHourIndexes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class AdminDashboardControllerTest extends AbstractIntegrationTest {

  private static final SimpleGrantedAuthority ADMIN =
      new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN");
  private static final SimpleGrantedAuthority VIEWER = new SimpleGrantedAuthority("ROLE_VIEWER");

  @Autowired
  private MongoTemplate mongoTemplate;

  @MockitoBean
  private OutageIssues outageIssues;

  @BeforeEach
  @AfterEach
  void reset() {
    mongoTemplate.getCollection(UptimeHour.COLLECTION).drop();
    V068CreateUptimeHourIndexes.createIndexes(mongoTemplate);
  }

  private static RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("owner")).authorities(ADMIN);
  }

  @Test
  void everyPanelRejectsAnonymousAndNonAdminCallers() throws Exception {
    for (String path : List.of("/api/admin/dashboard/uptime",
        "/api/admin/dashboard/vulnerabilities", "/api/admin/dashboard/content-flow")) {
      mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
      mockMvc.perform(get(path).with(jwt().jwt(j -> j.subject("someone")).authorities(VIEWER)))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void uptimeListsEveryConfiguredHostnameAndTheOutageIssues() throws Exception {
    when(outageIssues.recent()).thenReturn(new Incidents(true, List.of(
        new Incident(306, "Production heartbeat failing: logs",
            Instant.now().minusSeconds(86_400), Instant.now().minusSeconds(3_600), "u"),
        new Incident(12, "Long ago", Instant.parse("2025-01-01T00:00:00Z"),
            Instant.parse("2025-01-02T00:00:00Z"), "v"))));

    mockMvc.perform(get("/api/admin/dashboard/uptime").param("days", "7").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.probing").value(false))
        .andExpect(jsonPath("$.timeZone").value("Europe/London"))
        .andExpect(jsonPath("$.days.length()").value(7))
        .andExpect(jsonPath("$.targets.length()").value(9))
        .andExpect(jsonPath("$.targets[2].name").value("term-time"))
        .andExpect(jsonPath("$.targets[0].days[6].status").value("NO_DATA"))
        // The 2025 issue closed before the window and is left out.
        .andExpect(jsonPath("$.incidents.items.length()").value(1))
        .andExpect(jsonPath("$.incidents.items[0].number").value(306));
  }

  @Test
  void eachHostnameCountsGapsOnlyFromItsOwnFirstProbe() throws Exception {
    when(outageIssues.recent()).thenReturn(new Incidents(true, List.of()));
    Instant thisHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    Instant threeDaysAgo = thisHour.minus(3, ChronoUnit.DAYS);
    mongoTemplate.insert(new UptimeHour(UptimeHour.idFor("www", threeDaysAgo), "www",
        threeDaysAgo, 60, 0, 6000L, null, null));
    mongoTemplate.insert(new UptimeHour(UptimeHour.idFor("term-time", thisHour), "term-time",
        thisHour, 1, 0, 100L, null, null));

    // Yesterday: www was being probed and has nothing, so it is a gap; term-time did not exist.
    mockMvc.perform(get("/api/admin/dashboard/uptime").param("days", "7").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets[0].name").value("www"))
        .andExpect(jsonPath("$.targets[0].days[5].status").value("GAPS"))
        .andExpect(jsonPath("$.targets[2].name").value("term-time"))
        .andExpect(jsonPath("$.targets[2].days[5].status").value("NO_DATA"))
        .andExpect(jsonPath("$.probingSince").value(threeDaysAgo.toString()));
  }

  @Test
  void theDayCountIsClampedToWhatIsKept() throws Exception {
    when(outageIssues.recent()).thenReturn(new Incidents(true, List.of()));

    mockMvc.perform(get("/api/admin/dashboard/uptime").param("days", "5000").with(admin()))
        .andExpect(jsonPath("$.days.length()").value(AdminDashboardController.MAX_DAYS));
  }

  @Test
  void vulnerabilitiesSayNotConfiguredWithoutKeyRatherThanFailing() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard/vulnerabilities").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.projects.length()").value(0));
  }

  @Test
  void contentFlowReturnsTheRequestedWeeks() throws Exception {
    mockMvc.perform(get("/api/admin/dashboard/content-flow").param("weeks", "4").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeks.length()").value(4))
        .andExpect(jsonPath("$.weeks[0].arrived").isNumber());
  }
}
