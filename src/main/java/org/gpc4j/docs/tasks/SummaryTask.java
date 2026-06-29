package org.gpc4j.docs.tasks;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.DocSearchPage;
import org.gpc4j.docs.model.Summary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Hourly reporting task that logs transcription progress across all
 * {@link DocSearchDoc} documents: how many pages have been processed, how many
 * remain, and the percentage complete.
 *
 * <p>Active only under the {@code k8s} or {@code test} Spring profile.
 */
@Profile({"k8s", "test"})
@Component
public class SummaryTask {

  private static final Logger log = LoggerFactory.getLogger(SummaryTask.class);

  private final IDocumentStore store;

  /**
   * Constructs the task with the RavenDB document store.
   *
   * @param store the singleton RavenDB document store
   */
  public SummaryTask(IDocumentStore store) {

    this.store = store;
  }


  /**
   * Queries all {@link DocSearchDoc} records, tallies transcribed and
   * untranscribed {@link DocSearchPage} entries, logs a one-line progress
   * summary at INFO level, and persists a {@link Summary} snapshot to RavenDB.
   *
   * <p>A page is considered transcribed when its {@code aiServiceName} field is
   * non-null and non-blank. Pages with {@code aiError = "RECITATION"} that have
   * not been retranscribed are counted as remaining.
   *
   * <p>{@link Summary#getHourlyRate()} is calculated by comparing
   * {@code processedPages} against the most recent previous {@link Summary}
   * snapshot and dividing the delta by the elapsed hours between the two
   * snapshots.
   *
   * <p>Each {@link Summary} document is written with a RavenDB {@code @expires}
   * metadata entry set two days in the future so old snapshots are automatically
   * purged by the RavenDB Document Expiration extension, keeping the collection
   * small.
   *
   * <p>Runs on the hour, every hour ({@code cron = "0 0 * * * *"}).
   */
  @Scheduled(cron = "0 0 * * * *")
  public void summarize() {

    try (IDocumentSession session = store.openSession()) {

      List<DocSearchDoc> docs = session.query(DocSearchDoc.class).toList();

      int totalPages = docs.stream().mapToInt(DocSearchDoc::getPageCount).sum();

      long processedPages = docs
        .stream()
        .filter(d -> d.getPages() != null)
        .flatMap(d -> d.getPages().stream())
        .filter(p -> p.getAiServiceName() != null && !p.getAiServiceName().isBlank())
        .count();

      long remainingPages = totalPages - processedPages;

      float percentComplete = totalPages > 0 ? processedPages * 100.0f / totalPages
        : 0.0f;
      String pct = String.format("%.1f", percentComplete);

      // Compute hourly rate by comparing against the most recent previous snapshot.
      Summary previous = session
        .query(Summary.class)
        .orderByDescending("createdAt")
        .firstOrDefault();

      int hourlyRate = 0;
      if (previous != null && previous.getCreatedAt() != null) {
        long pagesDelta = processedPages - previous.getProcessedPages();
        double hoursDelta = Duration
          .between(previous.getCreatedAt(), Instant.now())
          .toMinutes() / 60.0;
        if (hoursDelta > 0) {
          hourlyRate = (int) Math.round(pagesDelta / hoursDelta);
        }
      }

      log
        .info(
          "Transcription progress: {}/{} pages complete ({}%), {} remaining"
            + " across {} documents, {} pages/hour",
          processedPages, totalPages, pct, remainingPages, docs.size(), hourlyRate);

      Summary summary = new Summary();
      summary.setTotalPages(totalPages);
      summary.setProcessedPages(processedPages);
      summary.setRemainingPages(remainingPages);
      summary.setTotalDocuments(docs.size());
      summary.setPercentComplete(percentComplete);
      summary.setHourlyRate(hourlyRate);
      summary.setCreatedAt(Instant.now());
      session.store(summary);

      // RavenDB built-in expiration: set @expires metadata so the document is
      // automatically deleted after two days, keeping the collection small.
      session
        .advanced()
        .getMetadataFor(summary)
        .put("@expires",
          Instant
            .now()
            .plus(2, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.MILLIS)
            .toString());

      session.saveChanges();

    } catch (Exception e) {
      log.error("SummaryTask failed", e);
    }
  }

}
