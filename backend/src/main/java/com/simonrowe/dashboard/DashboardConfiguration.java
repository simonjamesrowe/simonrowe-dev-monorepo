package com.simonrowe.dashboard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link DashboardProperties}. */
@Configuration
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfiguration {
}
