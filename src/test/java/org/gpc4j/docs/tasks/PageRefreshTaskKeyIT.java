package org.gpc4j.docs.tasks;

import java.time.Duration;

import org.gpc4j.docs.model.DocSearchDoc;
import org.gpc4j.docs.model.GeminiApiKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Integration test for {@link PageRefreshTaskKey}.
 *
 * <p>Requires a live RavenDB instance and valid Gemini API keys. The first
 * key from {@code gemini.api.keys} (decrypted by Jasypt at startup) is used
 * to construct the task under test.
 *
 * <p>Run manually with:
 * {@code ./mvnw test -Dtest=PageRefreshTaskKeyIT}
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "ravendb.urls=http://192.168.0.5:8080")
public class PageRefreshTaskKeyIT {

  @Autowired
  IDocumentStore store;

  PageRefreshTaskKey task;

  /**
   * Builds a {@link PageRefreshTaskKey} from the first key of the first enabled
   * {@link GeminiApiKeys} document in RavenDB, using {@code "JUnit"} as the
   * account name.
   */
  @BeforeEach
  void setUp() {

    try (IDocumentSession session = store.openSession()) {

      String apiKey = session
        .query(GeminiApiKeys.class)
        .whereEquals("enabled", true)
        .firstOrDefault()
        .getKeys()
        .getFirst();

      task = new PageRefreshTaskKey("JUnit", store, apiKey);

    }

  }


  /**
   * Invokes {@link PageRefreshTaskKey#run()} end-to-end: queries for a doc
   * with an incomplete page, acquires the cluster-wide lock, transcribes the
   * page via Gemini, and releases the lock.
   */
  @Test
  void testRun() {

    task.run();
  }


  /**
   * Holds the Spring context alive for 60 seconds to observe scheduled
   * {@link PageRefreshTaskKey} invocations in the log output.
   */
  @Test
  void testScheduling() throws InterruptedException {

    Thread.sleep(Duration.ofSeconds(60).toMillis());
  }


  /**
   * Invokes {@link PageRefreshTaskKey#processDoc} directly against a specific
   * document to verify transcription without going through the query loop.
   *
   * @throws Exception if the attachment fetch or AI call fails
   */
  @Test
  void testProcessDoc() throws Exception {

    try (IDocumentSession session = store.openSession()) {
      DocSearchDoc doc = session
        .query(DocSearchDoc.class)
        .whereEquals("filename", "1081.pdf")
        .firstOrDefault();

      task.processDoc(session, doc);
    }
  }

}
