package com.team.dating_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class DatingBackendApplication {

  public static void main(String[] args) {
    SpringApplication.run(DatingBackendApplication.class, args);
  }
}
