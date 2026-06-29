package org.gpc4j.docs.tasks;

import static org.gpc4j.docs.controller.DocumentController.CONFIDENCE_PATTERN;
import static org.gpc4j.docs.controller.DocumentController.OCR_PROMPT;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

import org.gpc4j.docs.model.AIPrompt;
import org.gpc4j.docs.model.AIResponse;
import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.DocSearchPage;
import org.gpc4j.docs.services.AnthropicClient;
import org.gpc4j.docs.utils.LockWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Background task that retranscribes {@link DocSearchPage} instances whose
 * previous Gemini attempt ended with a {@code RECITATION} finish reason.
 *
 * <p>On each invocation the task queries for one {@link DocSearchDoc} that
 * contains at least one page with {@code aiError = "RECITATION"}, then
 * submits that page's image to Anthropic Claude via {@link AnthropicClient}.
 * On success the page lines are updated and {@code aiError} is cleared.
 *
 * <p>Distributed mutual exclusion is achieved via RavenDB Compare-Exchange
 * using the same {@code "PageLock/<docId>"} key space as
 * {@link PageRefreshTaskKey}, so Gemini and Anthropic tasks cannot process the
 * same document concurrently on any cluster node. The lock is always released
 * in a {@code finally} block.
 *
 * <p>Active only under the {@code k8s} Spring profile. The Anthropic API key
 * is injected from the {@code anthropic.api.key} application property.
 */
@Profile({"k8s"})
@Component
public class AnthropicPageRefreshTask {

  private static final Logger log = LoggerFactory
    .getLogger(AnthropicPageRefreshTask.class);

  private final IDocumentStore store;
  private final AnthropicClient anthropicClient;

  /**
   * Constructs the task. Spring injects both parameters automatically.
   *
   * @param store  the singleton RavenDB document store; sessions are opened
   *               per-run because this task runs outside an HTTP request scope
   * @param apiKey the value of the {@code anthropic.api.key} property,
   *               injected via {@code @Value}
   */
  public AnthropicPageRefreshTask(IDocumentStore store,
    @Value("${anthropic.api.key}") String apiKey) {

    this.store = store;
    this.anthropicClient = new AnthropicClient(apiKey);
  }


  /**
   * Queries for one {@link DocSearchDoc} with a {@code RECITATION} page and
   * retranscribes it via Anthropic Claude. Runs every 10 minutes.
   *
   * <p>Steps per invocation:
   * <ul>
   *   <li>Query RavenDB for a document whose pages contain
   *       {@code aiError = "RECITATION"}; if none found, return.</li>
   *   <li>Acquire the cluster-wide Compare-Exchange lock for that document; skip
   *       if another node holds it.</li>
   *   <li>Call {@link #processDoc} to retranscribe the flagged page and save.</li>
   * </ul>
   *
   * <p>All exceptions are caught and logged so that subsequent scheduled
   * invocations are not affected.
   */
  @Scheduled(cron = "0 0/10 * * * *")
  public void run() {

    log
      .debug("AnthropicPageRefreshTask[{}]: searching for RECITATION pages",
        keyHint());

    try (IDocumentSession session = store.openSession()) {

      DocSearchDoc doc = session
        .query(DocSearchDoc.class)
        .whereEquals("pages[].aiError", "RECITATION")
        .take(5)
        .randomOrdering()
        .firstOrDefault();

      if (doc == null) {
        log
          .debug("AnthropicPageRefreshTask[{}]: no RECITATION pages found",
            keyHint());
        return;
      }

      try (LockWrapper lock = new LockWrapper(doc.getId(), store)) {
        if (lock.isSuccessful()) {
          processDoc(session, doc);
        } else {
          log
            .debug(
              "AnthropicPageRefreshTask[{}]: '{}' already locked by another node,"
                + " skipping",
              keyHint(), doc.getId());
        }
      }

    } catch (Exception e) {
      log.error("AnthropicPageRefreshTask[{}] failed unexpectedly", keyHint(), e);
    }
  }


  /**
   * Finds the first {@link DocSearchPage} in {@code doc} with
   * {@code aiError = "RECITATION"}, retranscribes it via Anthropic Claude,
   * and saves the session.
   *
   * <p>Called only after the caller has successfully acquired the cluster-wide
   * Compare-Exchange lock for this document.
   *
   * @param session open RavenDB session
   * @param doc     the document to process; mutated in place
   * @throws IOException if the attachment fetch or AI call fails
   */
  void processDoc(IDocumentSession session, DocSearchDoc doc) throws IOException {

    DocSearchPage page = doc
      .getPages()
      .stream()
      .filter(p -> "RECITATION".equals(p.getAiError()))
      .findFirst()
      .orElse(null);

    if (page == null) {
      log
        .debug(
          "AnthropicPageRefreshTask[{}]: no RECITATION page in '{}' (index stale?)",
          keyHint(), doc.getId());
      return;
    }

    log
      .debug("AnthropicPageRefreshTask[{}]: retranscribing page {} of '{}'",
        keyHint(), page.getPageNumber(), doc.getId());

    refreshPage(session, doc, page);
    session.saveChanges();

    log
      .info(
        "AnthropicPageRefreshTask[{}]: retranscribed page {}/{} of '{}' via {}/{}",
        keyHint(), page.getPageNumber(), doc.getPageCount(), doc.getId(),
        page.getAiServiceName(), page.getAiModel());
  }


  /**
   * Fetches the page-image attachment, submits it to {@link AnthropicClient},
   * and updates {@code page} in place with the transcribed text, confidence,
   * and AI provenance fields. Clears {@link DocSearchPage#getAiError()} on
   * success.
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

      AIResponse aiResponse = anthropicClient.queryResponse(prompt);
      log
        .debug("AnthropicPageRefreshTask[{}]: page {} returned {} chars via {}",
          keyHint(), page.getPageNumber(), aiResponse.text().length(),
          aiResponse.aiServiceName());

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
      page.setAiError(null);
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

    return anthropicClient.keyHint();
  }

}
