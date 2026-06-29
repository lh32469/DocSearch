package org.gpc4j.docs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Main entry point for the DocSearch application.
 * <p>
 * Bootstraps the Spring Boot application and configures a custom
 * {@link TaskScheduler} bean used by the background OCR tasks.
 */
@SpringBootApplication
@EnableScheduling
public class DocSearchApplication {

  /** Starts the application. */
  public static void main(String[] args) {

    SpringApplication.run(DocSearchApplication.class, args);
  }


  /**
   * Creates the primary {@link TaskScheduler} for scheduled tasks.
   *
   * @return a {@link ThreadPoolTaskScheduler} with a pool size of 2
   */
  @Bean
  @Primary
  public TaskScheduler taskScheduler() {

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    return scheduler;
  }

}
