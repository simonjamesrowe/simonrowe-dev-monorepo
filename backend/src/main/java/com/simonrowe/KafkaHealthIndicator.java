package com.simonrowe;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

  private final KafkaAdmin kafkaAdmin;

  public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
    this.kafkaAdmin = kafkaAdmin;
  }

  @Override
  public Health health() {
    try (AdminClient client = AdminClient.create(
        kafkaAdmin.getConfigurationProperties())) {
      DescribeClusterResult result = client.describeCluster();
      String clusterId = result.clusterId().get(5, TimeUnit.SECONDS);
      Collection<Node> nodes = result.nodes().get(5, TimeUnit.SECONDS);
      Health.Builder builder = Health.up()
          .withDetail("clusterId", clusterId);
      if (!nodes.isEmpty()) {
        builder.withDetail("brokerId",
            nodes.iterator().next().idString());
      }
      return builder.build();
    } catch (Exception ex) {
      return Health.down(ex).build();
    }
  }
}
