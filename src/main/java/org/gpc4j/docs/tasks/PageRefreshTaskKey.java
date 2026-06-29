package org.gpc4j.docs.tasks;

import static org.gpc4j.docs.controller.DocumentController.CONFIDENCE_PATTERN;
import static org.gpc4j.docs.controller.DocumentController.OCR_PROMPT;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;

import org.gpc4j.docs.model.AIPrompt;
import org.gpc4j.docs.model.AIResponse;
import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.DocSearchPage;
import org.gpc4j.docs.services.GeminiClient;
import org.gpc4j.docs.services.QuotaExceededException;
import org.gpc4j.docs.services.RecitationException;
import org.gpc4j.docs.utils.LockWrapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import lombok.extern.slf4j.Slf4j;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.AttachmentName;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Runnable that transcribes one incomplete {@link DocSearchPage} per invocation
 * using a dedicated Gemini API key, designed to run concurrently across a
 * multi-node cluster.
 *
 * <p>Each instance is bound to a single Gemini key and the
 * {@link org.gpc4j.docs.model.GeminiApiKeys} account it came from.
 * Multiple instances — one per key — run concurrently so each key consumes
 * only its own quota. {@link org.gpc4j.docs.components.ClockScheduler}
 * creates and schedules two instances per key found in enabled
 * {@code GeminiApiKeys} documents.
 *
 * <p>Distributed mutual exclusion is provided by {@link LockWrapper}, which
 * wraps a RavenDB Compare-Exchange CAS entry under
 * {@code "PageLock/<docId>"}. If the entry already exists another node holds
 * the lock and this instance skips cleanly. The lock is always released when
 * the {@code try}-with-resources block exits.
 *
 * <p>On a {@link QuotaExceededException} the task sets a flag and skips one
 * full scheduling turn before resuming, giving the quota time to recover
 * without a hard sleep that would block the shared thread pool.
 *
 * <p>This class is not a Spring {@code @Component};
 * {@link org.gpc4j.docs.components.ClockScheduler}
 * is responsible for instantiation and scheduling.
 */
@Slf4j
public class PageRefreshTaskKey implements Runnable {

  private static final Random RANDOM = new Random();

  private final String account;
  private final IDocumentStore store;
  private final GeminiClient geminiClient;

  /**
   * Represents the timestamp until which the current task is paused.
   * <p>
   * This field is used to temporarily halt the execution of the task when a
   * specific condition, such as API rate-limiting or quota exhaustion, has been
   * encountered. The task will resume only after the current time surpasses the
   * value assigned to this variable.
   */
  private LocalDateTime pausedUntil = LocalDateTime.now();

  /**
   * Tracks the number of consecutive quota exceedances encountered by the Gemini
   * API.
   * <p>
   * This variable is incremented whenever a request to the API results in a
   * quota-exceeded response, and it is reset when the quota is no longer exceeded
   * or the task successfully completes a request. It acts as a guard to prevent
   * excessive or unnecessary request attempts when the API's rate limit has been
   * reached.
   */
  private int quotaExceededCounter;

  /**
   * Constructs a task bound to a single Gemini API key and the account it
   * belongs to.
   *
   * @param account the {@link org.gpc4j.docs.model.GeminiApiKeys#getUser()}
   *                value; used in quota-exceeded log messages to identify which
   *                account was throttled
   * @param store   the singleton RavenDB document store; sessions are opened
   *                per-run because this task runs outside an HTTP request scope
   * @param apiKey  the Gemini API key this instance will use for every request
   */
  public PageRefreshTaskKey(String account, IDocumentStore store, String apiKey) {

    this.account = account;
    this.store = store;
    this.geminiClient = new GeminiClient(apiKey);
  }


  /**
   * Finds one {@link DocSearchDoc} with an incomplete {@link DocSearchPage},
   * acquires a cluster-wide {@link LockWrapper} lock for that document, and
   * transcribes the page using this instance's Gemini API key.
   *
   * <p>Execution is skipped when the logger level is {@code OFF} (controlled
   * via Spring Boot Admin) or when a 3-hour pause is active.
   *
   * <p>After 10 consecutive {@link QuotaExceededException}s the task pauses
   * for 3 hours, then resets the counter.
   *
   * <p>A random jitter of up to 15 seconds is applied before querying RavenDB
   * so that many concurrent instances started at the same time do not all hit
   * the database simultaneously.
   *
   * <p>If the lock cannot be acquired this method returns immediately without
   * logging a warning. The lock is always released when the
   * {@code try}-with-resources block exits.
   */
  @Override
  public void run() {

    if (loggerIsOff()) {
      // Execution disable via Spring Boot Admin logging level adjustment.
      return;
    }

    if (pausedUntil.isAfter(LocalDateTime.now())) {
      log.debug("{}Page refresh task has been paused", label());
      return;
    }

    if (quotaExceededCounter > 10) {
      // Ten QuotaExceededExceptions in a row, we're done.
      log.warn("{}Quota exceeded over 10 times, pausing for 3 hours", label());
      pausedUntil = LocalDateTime.now().plusHours(3);
      quotaExceededCounter = 0;
      return;
    }

    try {
      Thread.sleep(RANDOM.nextInt(15_000));
    } catch (InterruptedException e) {
    }

    try (IDocumentSession session = store.openSession()) {

      DocSearchDoc doc = session
        .query(DocSearchDoc.class)
        .whereExists("pages")
        .andAlso()
        .openSubclause()
        .negateNext()
        .whereExists("pages[].aiServiceName")
        .orElse()
        .whereEquals("pages[].aiServiceName", (Object) null)
        .orElse()
        .whereEquals("pages[].aiServiceName", "")
        .closeSubclause()
        .take(25)
        .randomOrdering()
        .firstOrDefault();

      if (doc == null) {
        log.debug("{}all pages are up to date", label());
        return;
      }

      try (LockWrapper lock = new LockWrapper(doc.getId(), store)) {
        if (lock.isSuccessful()) {
          processDoc(session, doc);
        } else {
          log
            .debug("{}'{}' already locked by another node, skipping", label(),
              doc.getId());
        }
      }

      // Success, reset Quota counter.
      quotaExceededCounter = 0;

    } catch (QuotaExceededException e) {
      quotaExceededCounter++;
      log.warn("{}quota exceeded", label());
    } catch (Exception e) {
      log.error("{}Failed unexpectedly", label(), e);
    }
  }


  /**
   * Ensures {@code doc} has a populated {@link DocSearchPage} list, finds the
   * first page lacking AI provenance, transcribes it, and saves the result.
   *
   * <p>Called only after the caller has successfully acquired the cluster-wide
   * Compare-Exchange lock for this document, so no further concurrency
   * control is needed here.
   *
   * @param session open RavenDB session
   * @param doc     the document to process; mutated in place
   * @throws IOException if the attachment fetch or AI call fails
   */
  void processDoc(IDocumentSession session, DocSearchDoc doc) throws IOException {

    if (doc.getPages() == null || doc.getPages().isEmpty()) {
      log
        .debug("{}'{}' has no pages — initialising from attachments", label(),
          doc.getId());

      AttachmentName[] attachmentNames = session
        .advanced()
        .attachments()
        .getNames(doc);

      List<DocSearchPage> stubs = new LinkedList<>();
      for (int i = 0; i < attachmentNames.length; i++) {
        DocSearchPage stub = new DocSearchPage();
        stub.setPageNumber(i + 1);
        stubs.add(stub);
      }
      doc.setPages(stubs);
    }

    DocSearchPage page = doc
      .getPages()
      .stream()
      .filter(p -> p.getAiServiceName() == null || p.getAiServiceName().isBlank())
      .findFirst()
      .orElse(null);

    if (page == null) {
      log.debug("{}no incomplete page in '{}' (index stale?)", label(), doc.getId());
      return;
    }

    log
      .debug("{}refreshing page {} of '{}'", label(), page.getPageNumber(),
        doc.getId());

    refreshPage(session, doc, page);
    session.saveChanges();

    log
      .info("{}refreshed page {}/{} of '{}' via {}/{}", label(),
        page.getPageNumber(), doc.getPageCount(), doc.getId(),
        page.getAiServiceName(), page.getAiModel());
  }


  /**
   * Fetches the page-image attachment, submits it to {@link GeminiClient},
   * and updates {@code page} in place with the transcribed text, confidence,
   * and AI provenance fields.
   *
   * @param session open RavenDB session used to fetch the attachment
   * @param doc     the parent document
   * @param page    the page to refresh; mutated in place on success
   * @throws IOException if the attachment is missing or the AI call fails
   */
  void refreshPage(IDocumentSession session, DocSearchDoc doc, DocSearchPage page)
    throws IOException {

    String attachmentName = "page-" + page.getPageNumber() + ".png";

    try (CloseableAttachmentResult att = session
      .advanced()
      .attachments()
      .get(doc.getId(), attachmentName)) {

      if (att == null) {
        throw new IOException(
          "Attachment '" + attachmentName + "' not found for '" + doc.getId() + "'");
      }

      byte[] imageBytes = att.getData().readAllBytes();

      AIPrompt prompt = AIPrompt
        .builder()
        .text(OCR_PROMPT)
        .image(imageBytes)
        .build();

      AIResponse aiResponse;
      try {
        aiResponse = geminiClient.queryResponse(prompt);
      } catch (RecitationException e) {
        log
          .info("{}page {} of '{}': RECITATION", label(), page.getPageNumber(),
            doc.getId());
        page.setAiServiceName(GeminiClient.class.getSimpleName());
        page.setAiError("RECITATION");
        page.setDateTranscribed(Instant.now());
        return;
      }

      log
        .debug("{}page {} returned {} chars via {}", label(), page.getPageNumber(),
          aiResponse.text().length(), aiResponse.aiServiceName());

      float confidence = 0f;
      List<String> lines = new LinkedList<>();
      for (String raw : aiResponse.text().split("\n")) {
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
          continue;
        }
        Matcher cm = CONFIDENCE_PATTERN.matcher(trimmed);
        if (cm.find()) {
          confidence = Float.parseFloat(cm.group(1));
        } else {
          lines.add(trimmed);
        }
      }

      page.setLines(lines);
      page.setConfidence(confidence);
      page.setAiServiceName(aiResponse.aiServiceName());
      page.setAiModel(aiResponse.aiModel());
      page.setDateTranscribed(Instant.now());
    }
  }


  /**
   * Returns the last four characters of the API key for use in log messages,
   * avoiding accidental full-key exposure in logs.
   *
   * @return four-character key suffix
   */
  public String keyHint() {

    return geminiClient.keyHint();
  }


  /**
   * Returns a log-line prefix in the form {@code "<account>[<keyHint>]: "}
   * for consistent identification across concurrent task instances.
   *
   * @return log label string
   */
  public String label() {

    return account + "[" + keyHint() + "]: ";
  }


  /**
   * Checks if the logger's effective level is set to OFF.
   *
   * @return true if the logger's effective level is OFF, false otherwise
   */
  private boolean loggerIsOff() {

    Logger logbackLogger = (Logger) log; // cast from slf4j to logback
    return logbackLogger.getEffectiveLevel() == Level.OFF;
  }

}
