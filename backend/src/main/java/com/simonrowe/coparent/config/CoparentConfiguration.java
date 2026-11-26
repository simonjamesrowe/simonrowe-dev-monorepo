package com.simonrowe.coparent.config;

import com.simonrowe.coparent.school.SchoolCalendarProperties;
import com.simonrowe.coparent.statement.StatementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers CoParent's configuration without enabling the product itself. */
@Configuration
@EnableConfigurationProperties({CoparentProperties.class, StatementProperties.class,
    SchoolCalendarProperties.class})
public class CoparentConfiguration {
}
