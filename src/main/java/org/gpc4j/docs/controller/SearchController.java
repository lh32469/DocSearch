package org.gpc4j.docs.controller;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.DocSearchPage;
import org.gpc4j.docs.model.PageCard;
import org.gpc4j.docs.model.SearchResultData;
import org.gpc4j.docs.model.Summary;
import org.gpc4j.docs.services.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Controller that serves the Thymeleaf search UI and streams RavenDB document
 * attachments (page images) to the browser. Query logic and caching live in
 * {@link SearchService}.
 */
@Controller
public class SearchController {

  private static final Logger log = LoggerFactory.getLogger(SearchController.class);

  private static final Pattern PAGE_NUM = Pattern.compile("page-(\\d+)");

  private final IDocumentSession session;
  private final SearchService searchService;

  /**
   * Constructs the controller with a request-scoped RavenDB session and the
   * search service.
   *
   * @param session       the RavenDB document session for this request
   * @param searchService the service that executes and caches queries
   */
  public SearchController(IDocumentSession session, SearchService searchService) {

    this.session = session;
    this.searchService = searchService;
  }


  /**
   * Redirects the application root to the search page.
   *
   * @return redirect to {@code /search}
   */
  @GetMapping("/")
  public String root() {

    return "redirect:/search";
  }


  /**
   * Renders the search page. Delegates query execution to
   * {@link SearchService#query} (which is {@code @Cacheable}) and unpacks
   * the result into the Spring MVC model.
   *
   * @param q     optional query string
   * @param page  1-based page number; defaults to 1
   * @param model Spring MVC model populated for the Thymeleaf template
   * @return the logical view name {@code "search"}
   */
  @GetMapping("/search")
  public String search(
    @RequestParam(required = false) String q,
    @RequestParam(defaultValue = "1") int page,
    Model model,
    HttpServletRequest request) {

    String remoteAddr = request.getRemoteAddr();
    log.info("query='{}' page={} from {}", q, page, remoteAddr);

    SearchResultData result = searchService.query(q, page);

    model.addAttribute("query", q != null ? q : "");
    model.addAttribute("totalDocCount", result.totalDocCount());
    model.addAttribute("cards", result.cards());
    model.addAttribute("currentPage", result.currentPage());
    model.addAttribute("totalPages", result.totalPages());
    model.addAttribute("totalCount", result.totalCount());
    model.addAttribute("pageStart", result.pageStart());
    model.addAttribute("pageEnd", result.pageEnd());
    model.addAttribute("pageNumbers", result.pageNumbers());

    Summary latest = session
      .query(Summary.class)
      .orderByDescending("createdAt")
      .firstOrDefault();
    if (latest != null && latest.getPercentComplete() < 100f) {
      model
        .addAttribute("transcriptionPct",
          String.format("%.1f", latest.getPercentComplete()));
    }

    return "search";
  }


  /**
   * Renders the document view page showing all pages of a single
   * {@link DocSearchDoc} as a card grid. When {@code q} is supplied each
   * page is tested against the query individually; pages that match are
   * flagged so the template can apply a green highlight frame.
   *
   * @param docId the RavenDB document ID (original PDF filename)
   * @param q     optional search query carried from the results page; used
   *              to determine which pages matched
   * @param model Spring MVC model populated for the Thymeleaf template
   * @return the logical view name {@code "document"}, or a redirect
   */
  @GetMapping("/document")
  public String document(
    @RequestParam String docId,
    @RequestParam(required = false) String q,
    Model model) {

    DocSearchDoc doc = session.load(DocSearchDoc.class, docId);

    if (doc == null) {
      log.warn("Document not found: {}", docId);
      return "redirect:/search";
    }

    List<PageCard> pages = new LinkedList<>();
    List<DocSearchPage> docPages = doc.getPages();

    if (docPages != null && !docPages.isEmpty()) {
      for (DocSearchPage p : docPages) {
        boolean matched = q != null && !q.isBlank()
          && searchService.queryMatchesPage(p, q);
        pages
          .add(new PageCard(doc.getId(), doc.getFilename(),
            "page-" + p.getPageNumber() + ".png", p.getPageNumber(), docPages.size(),
            p.getConfidence(), matched, p.getAiServiceName(), p.getAiModel()));
      }
    } else {
      // Legacy document without per-page data: fall back to pageCount.
      for (int i = 1; i <= doc.getPageCount(); i++) {
        pages
          .add(new PageCard(doc.getId(), doc.getFilename(), "page-" + i + ".png", i,
            doc.getPageCount(), 0f, false, null, null));
      }
    }

    model.addAttribute("doc", doc);
    model.addAttribute("pages", pages);
    model.addAttribute("query", q != null ? q : "");
    return "document";
  }


  /**
   * Renders the full-page attachment viewer for a single page image. Provides
   * rotate and prev/next navigation controls. Redirects to {@code /search} if
   * the document is not found.
   *
   * @param docId the RavenDB document ID (original PDF filename)
   * @param name  the attachment name, e.g. {@code page-3.png}
   * @param q     optional search query carried from the results page
   * @param model Spring MVC model populated for the Thymeleaf template
   * @return the logical view name {@code "attachment"}, or a redirect
   */
  @GetMapping("/document/attachment")
  public String viewAttachment(
    @RequestParam String docId,
    @RequestParam String name,
    @RequestParam(required = false) String q,
    Model model) {

    DocSearchDoc doc = session.load(DocSearchDoc.class, docId);
    if (doc == null) {
      log.warn("Document not found for attachment view: {}", docId);
      return "redirect:/search";
    }

    int pageNum = 1;
    Matcher m = PAGE_NUM.matcher(name);
    if (m.find()) {
      pageNum = Integer.parseInt(m.group(1));
    }

    int totalPages = (doc.getPages() != null && !doc.getPages().isEmpty())
      ? doc.getPages().size() : doc.getPageCount();

    // pageNum is reassigned above so it is not effectively final; capture it.
    final int finalPageNum = pageNum;

    // Collect text lines for the current page so the template can render them.
    List<String> lines = new LinkedList<>();
    if (doc.getPages() != null) {
      doc
        .getPages()
        .stream()
        .filter(p -> p.getPageNumber() == finalPageNum)
        .findFirst()
        .ifPresent(ep -> {
          if (ep.getLines() != null) {
            lines.addAll(ep.getLines());
          }
        });
    }

    model.addAttribute("docId", docId);
    model.addAttribute("name", name);
    model.addAttribute("filename", doc.getFilename());
    model.addAttribute("pageNum", pageNum);
    model.addAttribute("totalPages", totalPages);
    model
      .addAttribute("prevName",
        pageNum > 1 ? "page-" + (pageNum - 1) + ".png" : null);
    model
      .addAttribute("nextName",
        pageNum < totalPages ? "page-" + (pageNum + 1) + ".png" : null);
    model.addAttribute("query", q != null ? q : "");
    model.addAttribute("lines", lines);

    return "attachment";
  }


  /**
   * Streams a single page-image attachment from RavenDB so the browser can
   * render it inside an {@code <img>} tag. Returns 404 if the document or
   * attachment does not exist.
   *
   * @param docId the RavenDB document ID (original PDF filename)
   * @param name  the attachment name, e.g. {@code page-1.png}
   * @return the raw image bytes with the correct {@code Content-Type}, or 404
   * @throws IOException if reading the attachment stream fails
   */
  @GetMapping("/api/document/attachment")
  @ResponseBody
  public ResponseEntity<byte[]> getAttachment(
    @RequestParam String docId,
    @RequestParam String name) throws IOException {

    // @formatter:off
    try (CloseableAttachmentResult result =
           session.advanced()
             .attachments()
             .get(docId, name)) {
      // @formatter:on

      if (result == null) {
        return ResponseEntity.notFound().build();
      }

      byte[] bytes = result.getData().readAllBytes();
      MediaType contentType = MediaType
        .parseMediaType(result.getDetails().getContentType());

      // @formatter:off
      return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(14, TimeUnit.DAYS))
        .contentType(contentType).body(bytes);
      // @formatter:on

    } catch (Exception e) {
      log
        .warn("Attachment not found: docId='{}' name='{}': {}", docId, name,
          e.getMessage());
      return ResponseEntity.notFound().build();
    }
  }

}
