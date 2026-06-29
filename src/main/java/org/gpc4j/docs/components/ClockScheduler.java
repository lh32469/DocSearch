package org.gpc4j.docs.components;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.gpc4j.docs.model.GeminiApiKeys;
import org.gpc4j.docs.tasks.PageRefreshTaskKey;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Schedules {@link PageRefreshTaskKey} instances across all
 * {@link GeminiApiKeys} documents whose {@code user} field matches the
 * {@code clock-scheduler.user} property.
 *
 * <p>For each matching document, up to eight keys are each assigned to one
 * non-overlapping three-hour window of the day. Multiple documents with the
 * same {@code user} value result in parallel sets of tasks covering the same
 * windows with different API keys.
 *
 * <p>Window-to-key mapping (24-hour clock):
 * <ul>
 *   <li>key 0 — 00:00–03:00</li>
 *   <li>key 1 — 03:00–06:00</li>
 *   <li>key 2 — 06:00–09:00</li>
 *   <li>key 3 — 09:00–12:00</li>
 *   <li>key 4 — 12:00–15:00</li>
 *   <li>key 5 — 15:00–18:00</li>
 *   <li>key 6 — 18:00–21:00</li>
 *   <li>key 7 — 21:00–24:00</li>
 * </ul>
 *
 * <p>Each task fires every minute within its window via a {@link CronTrigger}.
 * Active only under the {@code k8s} Spring profile.
 */
@Component
@Profile({"k8s"})
@Slf4j
public class ClockScheduler {

  /**
   * Cron expressions for each three-hour window, indexed 0–7.
   */
  private static final String[] CRON_WINDOWS = {"0 * 0-2 * * *", // 00:00–03:00
    "0 * 3-5 * * *", // 03:00–06:00
    "0 * 6-8 * * *", // 06:00–09:00
    "0 * 9-11 * * *", // 09:00–12:00
    "0 * 12-14 * * *", // 12:00–15:00
    "0 * 15-17 * * *", // 15:00–18:00
    "0 * 18-20 * * *", // 18:00–21:00
    "0 * 21-23 * * *", // 21:00–24:00
  };

  private final IDocumentStore store;
  private final TaskScheduler taskScheduler;

  /**
   * Constructs the scheduler with its required dependencies.
   *
   * @param store         the singleton RavenDB document store used to load the
   *                      {@link GeminiApiKeys} document at startup
   * @param taskScheduler the thread-pool scheduler used to register cron tasks
   */
  public ClockScheduler(IDocumentStore store, TaskScheduler taskScheduler) {

    this.store = store;
    this.taskScheduler = taskScheduler;
  }


  /**
   * Loads all enabled {@link GeminiApiKeys} documents and registers two
   * {@link PageRefreshTaskKey} instances per key per document, each pair bound
   * to the same three-hour cron window. Two instances per window increase
   * throughput under the same quota.
   *
   * <p>Called automatically by Spring after the bean is fully constructed.
   */
  @PostConstruct
  public void scheduleTasks() {

    try (IDocumentSession session = store.openSession()) {

      List<GeminiApiKeys> allKeys = session
        .query(GeminiApiKeys.class)
        .whereEquals("enabled", true)
        .toList();

      if (allKeys.isEmpty()) {
        log.warn("ClockScheduler: no enabled GeminiApiKeys found ");
        return;
      }

      int totalScheduled = 0;

      for (GeminiApiKeys apiKeys : allKeys) {
        int windowCount = Math.min(apiKeys.getKeys().size(), CRON_WINDOWS.length);

        if (apiKeys.getKeys().size() < CRON_WINDOWS.length) {
          log
            .warn(
              "ClockScheduler: document '{}' has {} keys, expected {} — "
                + "only {} windows scheduled",
              apiKeys.getId(), apiKeys.getKeys().size(), CRON_WINDOWS.length,
              windowCount);
        }

        for (int i = 0; i < windowCount; i++) {
          String key = apiKeys.getKeys().get(i);

          // Schedule two tasks per key
          PageRefreshTaskKey task1 = new PageRefreshTaskKey(apiKeys.getUser(), store,
            key);
          taskScheduler.schedule(task1, new CronTrigger(CRON_WINDOWS[i]));
          log
            .info("ClockScheduler: doc='{}' key[{}] window '{}'", apiKeys.getId(),
              task1.keyHint(), CRON_WINDOWS[i]);

          PageRefreshTaskKey task2 = new PageRefreshTaskKey(apiKeys.getUser(), store,
            key);
          taskScheduler.schedule(task2, new CronTrigger(CRON_WINDOWS[i]));
          log
            .info("ClockScheduler: doc='{}' key[{}] window '{}'", apiKeys.getId(),
              task2.keyHint(), CRON_WINDOWS[i]);

          log
            .info(
              "ClockScheduler: {} tasks scheduled across {} documents for user '{}'",
              totalScheduled, apiKeys.getKeys().size(), apiKeys.getUser());
        }

        totalScheduled += windowCount;
      }

    }
  }

}
