package com.team.dating_backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables application-wide scheduled jobs across feature modules. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
