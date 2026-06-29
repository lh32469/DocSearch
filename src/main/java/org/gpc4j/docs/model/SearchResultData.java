package org.gpc4j.docs.model;

import java.util.List;

/**
 * Immutable value object returned by {@link org.gpc4j.docs.services.SearchService#query}
 * and cached under the {@code searchResults} cache. Carries every attribute
 * the Thymeleaf search template needs so the controller can unpack it into the
 * {@code Model} without re-querying RavenDB.
 *
 * @param totalDocCount  total number of documents in the collection
 * @param cards          one {@link PageCard} per result document
 * @param currentPage    1-based page number actually served (clamped to valid range)
 * @param totalPages     total number of result pages
 * @param totalCount     total number of matching documents
 * @param pageStart      1-based index of the first result on this page
 * @param pageEnd        1-based index of the last result on this page
 * @param pageNumbers    ordered page-number buttons; {@code -1} is an ellipsis sentinel
 */
public record SearchResultData(int totalDocCount, List<PageCard> cards,
  int currentPage, int totalPages, int totalCount, int pageStart, int pageEnd,
  List<Integer> pageNumbers) {
}
