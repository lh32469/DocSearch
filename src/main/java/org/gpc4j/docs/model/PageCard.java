package org.gpc4j.docs.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Flattened view model representing a single rendered page from an
 * {@link DocSearchDoc} search result. One {@code PageCard} is produced per
 * page so the Thymeleaf template can iterate a single flat list.
 */
@Data
@AllArgsConstructor
public class PageCard {

  /** RavenDB document ID (original PDF filename). */
  private String docId;

  /** Human-readable filename shown in the card footer. */
  private String filename;

  /** Attachment name for this page, e.g. {@code page-2.png}. */
  private String attachmentName;

  /** 1-based page index within the document. */
  private int pageNum;

  /** Total pages in the document; used to decide whether to show "page N of M". */
  private int totalPages;

  /** Mean OCR confidence for this page (0–100). */
  private float confidence;

  /**
   * Whether this page's text matched the active search query. Used in the
   * document detail view to apply a green highlight frame when the document
   * has more than one page.
   */
  private boolean matched;

  /**
   * Simple class name of the {@link org.gpc4j.docs.services.AIService}
   * implementation that extracted this page's text, or {@code null} for legacy
   * documents processed before AI extraction was introduced.
   */
  private String aiServiceName;

  /**
   * Model identifier reported by the AI service, e.g.
   * {@code gemini-3.1-flash-lite}, or {@code null} for legacy documents.
   */
  private String aiModel;

}
