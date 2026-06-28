package com.simonrowe.migration;

import io.mongock.runner.springboot.EnableMongock;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Mongock, which runs the change units under
 * {@code com.simonrowe.migration.changeunits} on application startup. Each change
 * unit is tracked in MongoDB and executed at most once. Can be turned off with
 * {@code MONGOCK_ENABLED=false}.
 */
@Configuration
@EnableMongock
public class MongockConfig {
}
