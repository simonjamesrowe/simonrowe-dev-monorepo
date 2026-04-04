package com.simonrowe.dataops;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "redeploy")
public record RedeployProperties(
    String composeFile,
    List<String> services,
    String dockerBinary,
    int selfRestartDelaySeconds
) {
}
