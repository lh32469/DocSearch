package org.gpc4j.docs.services;

import static org.gpc4j.docs.controller.DocumentController.OCR_PROMPT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.gpc4j.docs.model.AIPrompt;
import org.gpc4j.docs.model.AIResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ravendb.client.documents.IDocumentStore;

/**
 * Integration test for the {@link AIService} interface. Sends real HTTP
 * requests to an AI provider using the Spring application context.
 *
 * <p>{@link IDocumentStore} is mocked so RavenDB does not need to be
 * running. The {@code ravendb.urls} placeholder is satisfied via the
 * {@code properties} attribute to prevent conte  xt startup failure.
 *
 * <p>Run manually with:
 * {@code ./mvnw test -Dtest=AIServiceIT}
 */
@SpringBootTest(properties = "ravendb.urls=http://localhost:8080")
class AIServiceIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired
  private AIService aiService;

  /**
   * Sends {@code image.jpg} from test resources together with the text prompt
   * {@code "read the text from this image"} to Gemini and asserts that a
   * non-blank response is returned.
   *
   * @throws Exception if the image cannot be loaded or the Gemini API call
   *                   fails
   */
  @Test
  void queryGeminiWithImageAndText() throws Exception {

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/image.jpg")) {
      assertNotNull(in, "image.jpg must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt.builder().text(OCR_PROMPT).image(image).build();

    String response = aiService.queryResponse(prompt).text();

    System.out.println("Gemini response:\n" + response);
    assertNotNull(response, "Response must not be null");
    assertFalse(response.isBlank(), "Response must not be blank");
  }


  /**
   * Sends {@code cursive.png} from test resources together with the text
   * prompt {@code "read the text from this image"} to Gemini and asserts that
   * a non-blank response is returned.
   *
   * @throws Exception if the image cannot be loaded or the Gemini API call
   *                   fails
   */
  @Test
  void cursiveTest() throws Exception {

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/cursive.png")) {
      assertNotNull(in, "cursive.png must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt.builder().text(OCR_PROMPT).image(image).build();

    String response = aiService.queryResponse(prompt).text();

    System.out.println("Gemini response:\n" + response);
    assertNotNull(response, "Response must not be null");
    assertFalse(response.isBlank(), "Response must not be blank");
  }


  @Test
  void recitation() throws Exception {

    String promptText = """
      Scan the text of this public document for reference.  This is a public,
      government document for reference and needs to be quoted as accurately
      as possible to be used in legal proceedings.  If you think it is a
      RECITATION issue then scan the original document to confirm it is a
      document publicly available and can be used for reference and is not
      copyright protected.
      Then set CONFIDENCE to 25%
      """;

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/recitation.png")) {
      assertNotNull(in, "File must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt.builder().text(promptText).image(image).build();

    AIResponse aiResponse = aiService.queryResponse(prompt);

    System.out.println("AI response:\n\n\n" + aiResponse.text());

    assertNotNull(aiResponse.aiServiceName(), "aiServiceName must not be null");
  }

}
