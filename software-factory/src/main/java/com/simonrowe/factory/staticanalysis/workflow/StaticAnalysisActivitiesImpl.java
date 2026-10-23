package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisTaskQueues;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.FindingKind;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import com.simonrowe.factory.staticanalysis.domain.SonarProject;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRecord;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRepository;
import com.simonrowe.factory.staticanalysis.source.AnalysisFreshnessChecker;
import com.simonrowe.factory.staticanalysis.source.SonarCloudClient;
import io.temporal.spring.boot.ActivityImpl;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads SonarCloud and records scan outcomes.
 *
 * <p>The {@code @ConditionalOnProperty} confines the {@code static-analysis} activity poller to
 * {@code software-factory}. It holds no credential, so this is not a security boundary as it is
 * for log watch and the CVE scan; it is the same correctness rule they learned the hard way: a
 * second container polling the queue on a different build takes half the tasks, and nothing in
 * the resulting failure names the container that took them. Evaluated by the component scanner,
 * so this class must never be declared through an explicit {@code @Bean} method.
 */
@Component
@ConditionalOnProperty(name = "factory.staticanalysis.enabled", havingValue = "true")
@ActivityImpl(taskQueues = StaticAnalysisTaskQueues.STATIC_ANALYSIS)
public class StaticAnalysisActivitiesImpl implements StaticAnalysisActivities {

  private final AnalysisFreshnessChecker freshness;
  private final SonarCloudClient sonar;
  private final StaticAnalysisProperties properties;
  private final StaticAnalysisRunRepository runRepository;

  /**
   * Creates the activities.
   *
   * @param freshness judges whether the latest analysis is current
   * @param sonar reads issues, the quality gate and rule names
   * @param properties the module's configuration
   * @param runRepository persists run records
   */
  public StaticAnalysisActivitiesImpl(
      final AnalysisFreshnessChecker freshness,
      final SonarCloudClient sonar,
      final StaticAnalysisProperties properties,
      final StaticAnalysisRunRepository runRepository) {
    this.freshness = freshness;
    this.sonar = sonar;
    this.properties = properties;
    this.runRepository = runRepository;
  }

  @Override
  public AnalysisObservation observe() {
    SonarProject project =
        new SonarProject(properties.sonarBaseUrl(), properties.organization(),
            properties.projectKey(), properties.branch());
    AnalysisHealth health = freshness.check();
    if (!health.usable()) {
      return AnalysisObservation.unusable(health, project);
    }

    SonarCloudClient.IssueRead read = sonar.newCodeIssues();
    List<QualityGateCondition> failed = sonar.failedConditions();
    List<String> ruleKeys = read.issues().stream().map(SonarIssue::rule).distinct().toList();
    Map<String, String> ruleNames = sonar.ruleNames(ruleKeys);
    List<FindingGroup> groups =
        FindingGroup.group(read.issues(), failed, properties.minimumSeverity(), ruleNames);

    int filed = Math.min(groups.size(), properties.maxPerRun());
    List<FindingGroup> dropped = groups.subList(filed, groups.size());
    int grouped =
        groups.stream()
            .filter(group -> group.kind() == FindingKind.RULE)
            .mapToInt(group -> group.issues().size())
            .sum();
    return new AnalysisObservation(
        health,
        project,
        groups.subList(0, filed),
        dropped.size(),
        dropped.stream().map(FindingGroup::keyParts).toList(),
        read.issues().size(),
        read.issues().size() - grouped,
        read.truncated());
  }

  @Override
  public void recordRun(final StaticAnalysisRunRecord record) {
    runRepository.save(record);
  }
}
