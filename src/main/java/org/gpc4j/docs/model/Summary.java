package org.gpc4j.docs.model;

import java.time.Instant;

import lombok.Data;

/**
 * RavenDB document that captures a point-in-time snapshot of transcription
 * progress across all {@link DocSearchDoc} records.
 *
 * <p>A new {@code Summary} is written to the {@code Summaries} collection each
 * time {@link org.gpc4j.docs.tasks.SummaryTask} runs (hourly), building
 * a history of throughput over time.
 */
@Data
public class Summary {

  /** RavenDB document identifier; assigned by the database on store. */
  private String id;

  /** Total number of pages across all {@link DocSearchDoc} records. */
  private int totalPages;

  /** Number of pages that have been transcribed by an AI service. */
  private long processedPages;

  /** Number of pages not yet transcribed ({@code totalPages - processedPages}). */
  private long remainingPages;

  /** Total number of {@link DocSearchDoc} records in the database. */
  private int totalDocuments;

  /** Percentage of pages transcribed, rounded to one decimal place. */
  private float percentComplete;

  /** UTC timestamp when this snapshot was recorded. */
  private Instant createdAt;

  /**
   * Pages transcribed per hour, computed by dividing the increase in
   * {@link #processedPages} since the previous {@link Summary} snapshot by the
   * elapsed hours between the two snapshots. Zero when no previous snapshot
   * exists.
   */
  private int hourlyRate;

}
