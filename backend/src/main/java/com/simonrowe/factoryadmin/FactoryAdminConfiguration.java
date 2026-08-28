package com.simonrowe.factoryadmin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the internal factory proxy configuration. */
@Configuration
@EnableConfigurationProperties(FactoryAdminProperties.class)
public class FactoryAdminConfiguration {
}
