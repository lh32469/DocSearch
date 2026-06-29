package org.gpc4j.docs.model;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * OCR result for a single rendered page of a scanned PDF.
 * Stored as a nested object inside {@link DocSearchDoc}.
 *
 * <p>{@link NoArgsConstructor} is required for RavenDB Jackson deserialization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocSearchPage {

  /**
   * 1-based page index within the source PDF.
   */
  private int pageNumber;

  /**
   * Non-blank text lines extracted from this page.
   */
  @ToString.Exclude
  private List<String> lines;

  /**
   * AI confidence score for this page (0–100).
   */
  private float confidence;

  /**
   * Simple class name of the {@link org.gpc4j.docs.services.AIService}
   * implementation that extracted this page's text, e.g.
   * {@code "GeminiClient"} or {@code "AnthropicService"}. May differ from
   * the document-level value when a per-page fallback used a different
   * provider.
   */
  private String aiServiceName;

  /**
   * Model identifier reported by the AI service for this page, e.g.
   * {@code gemini-3.1-flash-lite} or {@code claude-haiku-4-5-20251001}.
   */
  private String aiModel;

  /**
   * Describes any error message reported by the AI service during the processing
   * of this page. This field will be {@code null} or blank if no error occurred.
   *
   * <p>Populated when the processing fails, providing details for debugging or
   * auditing purposes, such as connection issues, model failure, or invalid input.
   */
  private String aiError;

  /**
   * UTC timestamp recorded when the page image was read and transcribed to
   * text by the AI service.
   */
  private Instant dateTranscribed;

}
