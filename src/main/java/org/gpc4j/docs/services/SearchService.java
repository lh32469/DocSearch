package org.gpc4j.docs.services;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.DocSearchPage;
import org.gpc4j.docs.model.PageCard;
import org.gpc4j.docs.model.SearchResultData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.documents.session.QueryStatistics;
import net.ravendb.client.primitives.Reference;

/**
 * Handles RavenDB search queries and related helpers. Results are cached
 * under the {@code searchResults} cache keyed on query string and page number.
 */
@Service
public class SearchService {

  private static final Logger log = LoggerFactory.getLogger(SearchService.class);

  static final int PAGE_SIZE = 15;

  /**
   * Matches the boolean operators {@code AND} and {@code OR} (uppercase,
   * surrounded by whitespace) that separate search terms in the user's query.
   */
  private static final Pattern BOOL_OP = Pattern.compile("\\s+(AND|OR)\\s+");

  private final IDocumentSession session;

  /**
   * Constructs the service with a request-scoped RavenDB session proxy.
   *
   * @param session the RavenDB document session for this request
   */
  public SearchService(IDocumentSession session) {

    this.session = session;
  }


  /**
   * Runs the search query and returns all model data needed by the search
   * template. Results are cached; a cache hit skips all RavenDB I/O.
   *
   * @param q    optional user query; {@code null} or blank returns an empty result set
   * @param page 1-based page number
   * @return fully populated {@link SearchResultData}
   */
  @Cacheable(value = "searchResults", key = "(#q != null ? #q : '') + ':' + #page")
  public SearchResultData query(String q, int page) {

    int totalDocCount = session.query(DocSearchDoc.class).count();
    List<PageCard> cards = new LinkedList<>();
    int totalCount = 0;
    int totalPages = 0;

    if (q != null && !q.isBlank()) {
      if (page < 1) {
        page = 1;
      }

      String rql = buildRql(q);
      log.info("RQL: {} (page {})", rql, page);

      Reference<QueryStatistics> statsRef = new Reference<>();
      List<DocSearchDoc> docs = session
        .advanced()
        .rawQuery(DocSearchDoc.class, rql)
        .statistics(statsRef)
        .skip((page - 1) * PAGE_SIZE)
        .take(PAGE_SIZE)
        .toList();

      totalCount = statsRef.value.getTotalResults();
      totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);

      if (page > totalPages && totalPages > 0) {
        page = totalPages;
      }

      log.info("Query '{}' — {} total, page {}/{}", q, totalCount, page, totalPages);

      for (DocSearchDoc doc : docs) {
        String aiServiceName = null;
        String aiModel = null;
        if (doc.getPages() != null && !doc.getPages().isEmpty()) {
          DocSearchPage firstPage = doc.getPages().get(0);
          aiServiceName = firstPage.getAiServiceName();
          aiModel = firstPage.getAiModel();
        }
        cards
          .add(new PageCard(doc.getId(), doc.getFilename(), "page-1.png", 1,
            doc.getPageCount(), avgConfidence(doc), false, aiServiceName, aiModel));
      }
    }

    int pageStart = totalCount == 0 ? 0 : (page - 1) * PAGE_SIZE + 1;
    int pageEnd = Math.min(page * PAGE_SIZE, totalCount);

    return new SearchResultData(totalDocCount, cards, page, totalPages, totalCount,
      pageStart, pageEnd, computePageNumbers(page, totalPages));
  }


  /**
   * Tests whether a single {@link DocSearchPage}'s text matches the user's query.
   * Handles the same AND/OR boolean syntax as {@link #buildRql(String)}.
   * Phrase matching (quoted terms) and wildcards ({@code *}) are also supported.
   *
   * @param page the page whose lines are tested
   * @param q    the raw user query
   * @return {@code true} if the page text satisfies the query
   */
  public boolean queryMatchesPage(DocSearchPage page, String q) {

    if (page.getLines() == null || page.getLines().isEmpty()) {
      return false;
    }

    String pageText = String.join(" ", page.getLines()).toLowerCase();

    List<String> terms = new LinkedList<>();
    List<String> ops = new LinkedList<>();

    Matcher m = BOOL_OP.matcher(q);
    int last = 0;
    while (m.find()) {
      terms.add(q.substring(last, m.start()).trim());
      ops.add(m.group(1).toLowerCase());
      last = m.end();
    }
    terms.add(q.substring(last).trim());

    boolean result = termMatchesText(terms.getFirst(), pageText);
    for (int i = 1; i < terms.size(); i++) {
      boolean termHit = termMatchesText(terms.get(i), pageText);
      if ("and".equals(ops.get(i - 1))) {
        result = result && termHit;
      } else {
        result = result || termHit;
      }
    }
    return result;
  }


  /**
   * Translates a user-entered query into a RavenDB RQL {@code where} clause
   * that searches both the nested {@code pages[].lines} field and the top-level
   * {@code filename} field. Each term matches if it appears in either field, so
   * searching for {@code cursive.png} will return documents whose filename
   * matches as well as those whose page text matches.
   *
   * @param q the raw query string entered by the user
   * @return a complete RQL query string
   */
  String buildRql(String q) {

    List<String> terms = new LinkedList<>();
    List<String> ops = new LinkedList<>();

    Matcher m = BOOL_OP.matcher(q);
    int last = 0;
    while (m.find()) {
      terms.add(q.substring(last, m.start()).trim());
      ops.add(m.group(1).toLowerCase());
      last = m.end();
    }
    terms.add(q.substring(last).trim());

    String collection = session
      .advanced()
      .getDocumentStore()
      .getConventions()
      .getCollectionName(DocSearchDoc.class);
    StringBuilder rql = new StringBuilder("from " + collection + " where ");
    for (int i = 0; i < terms.size(); i++) {
      if (i > 0) {
        rql.append(' ').append(ops.get(i - 1)).append(' ');
      }
      String escaped = terms.get(i).replace("'", "''");
      rql
        .append("(search(pages[].lines, '")
        .append(escaped)
        .append("') or search(filename, '")
        .append(escaped)
        .append("'))");
    }
    return rql.toString();
  }


  /**
   * Tests whether a single query term matches a block of page text. Supports
   * quoted phrases, {@code *} wildcards, and simple case-insensitive substring
   * matching.
   *
   * @param term the query term (may be quoted or contain {@code *})
   * @param text the lowercased page text to test against
   * @return {@code true} if the term matches
   */
  private static boolean termMatchesText(String term, String text) {

    String t = term.toLowerCase();

    if (t.startsWith("\"") && t.endsWith("\"")) {
      return text.contains(t.substring(1, t.length() - 1));
    }

    if (t.contains("*") || t.contains("?")) {
      String pattern = ".*"
        + t.replace(".", "\\.").replace("*", ".*").replace("?", ".") + ".*";
      return text.matches(pattern);
    }

    return text.contains(t);
  }


  /**
   * Computes the list of page-number buttons to display in the pagination bar.
   * Returns at most 7 entries; {@code -1} is used as a sentinel for an ellipsis.
   *
   * @param currentPage 1-based current page number
   * @param totalPages  total number of pages
   * @return ordered list of page numbers and {@code -1} ellipsis sentinels
   */
  static List<Integer> computePageNumbers(int currentPage, int totalPages) {

    List<Integer> nums = new LinkedList<>();

    if (totalPages <= 7) {
      for (int i = 1; i <= totalPages; i++) {
        nums.add(i);
      }
    } else if (currentPage <= 4) {
      for (int i = 1; i <= 5; i++) {
        nums.add(i);
      }
      nums.add(-1);
      nums.add(totalPages);
    } else if (currentPage >= totalPages - 3) {
      nums.add(1);
      nums.add(-1);
      for (int i = totalPages - 4; i <= totalPages; i++) {
        nums.add(i);
      }
    } else {
      nums.add(1);
      nums.add(-1);
      nums.add(currentPage - 1);
      nums.add(currentPage);
      nums.add(currentPage + 1);
      nums.add(-1);
      nums.add(totalPages);
    }

    return nums;
  }


  /**
   * Returns the mean OCR confidence across all pages of a document.
   *
   * @param doc the document to evaluate
   * @return average confidence in the range 0–100, or 0 if no pages
   */
  private static float avgConfidence(DocSearchDoc doc) {

    List<DocSearchPage> pages = doc.getPages();

    if (pages == null || pages.isEmpty()) {
      return 0f;
    }

    float total = 0f;
    for (DocSearchPage p : pages) {
      total += p.getConfidence();
    }
    return total / pages.size();
  }

}
