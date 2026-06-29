package org.gpc4j.docs.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.gpc4j.docs.model.AIPrompt;
import org.gpc4j.docs.model.AIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Low-level HTTP client for the Google Gemini generative-language API.
 *
 * <p>A single API key is bound at construction time and used for every
 * request, with no key rotation. This class is intended for use cases
 * — such as {@link org.gpc4j.docs.tasks.PageRefreshTaskKey} — where one
 * instance per key runs concurrently and each instance must consume only
 * its own quota.
 *
 * <p>RECITATION responses (where Gemini declines to reproduce text it
 * recognises from its training corpus) throw {@link RecitationException} so
 * the caller can mark the page with {@code aiError} and skip line extraction.
 *
 * <p>This class is not a Spring bean. Callers are responsible for
 * instantiation.
 */
public class GeminiClient implements AIService {

  private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

  private static final String MODEL = "gemini-3.1-flash-lite";

  private static final String GEMINI_URL = "https://generativelanguage.googleapis.com"
    + "/v1beta/models/" + MODEL + ":generateContent";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String apiKey;

  /**
   * Constructs a client bound to the given Gemini API key.
   *
   * <p>The HTTP client is forced to HTTP/1.1 to avoid 503 responses the
   * Gemini API returns for large multimodal payloads over HTTP/2.
   *
   * @param apiKey the Gemini API key to use for every request
   */
  public GeminiClient(String apiKey) {

    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
  }


  /**
   * {@inheritDoc}
   *
   * <p>When Gemini returns a {@code RECITATION} finish reason an
   * {@link IOException} is thrown so the caller can decide how to handle it
   * (e.g. fall back to a different model or skip the page).
   *
   * @throws RecitationException    if Gemini returns a {@code RECITATION}
   *                                finish reason
   * @throws QuotaExceededException if Gemini returns HTTP 429
   * @throws IOException            if the request fails or Gemini returns an
   *                                unexpected response structure
   */
  @Override
  public AIResponse queryResponse(AIPrompt prompt) throws IOException {

    List<Map<String, Object>> parts = new LinkedList<>();

    if (prompt.getImage() != null && prompt.getImage().length > 0) {
      String mimeType = detectMimeType(prompt.getImage());
      String b64 = Base64.getEncoder().encodeToString(prompt.getImage());
      parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", b64)));
      log
        .debug("GeminiClient[{}]: attaching {} image ({} bytes)", keyHint(),
          mimeType, prompt.getImage().length);
    }

    parts.add(Map.of("text", prompt.getText()));

    Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", parts)));
    String requestJson = MAPPER.writeValueAsString(body);
    log
      .debug("GeminiClient[{}]: sending request ({} chars)", keyHint(),
        requestJson.length());

    HttpRequest request = HttpRequest
      .newBuilder()
      .uri(URI.create(GEMINI_URL))
      .header("Content-Type", "application/json")
      .header("X-goog-api-key", apiKey)
      .POST(HttpRequest.BodyPublishers.ofString(requestJson))
      .build();

    HttpResponse<String> response = send(request);

    JsonNode root = MAPPER.readTree(response.body());
    String finishReason = root.at("/candidates/0/finishReason").asText();

    if ("RECITATION".equals(finishReason)) {
      throw new RecitationException();
    }

    JsonNode text = root.at("/candidates/0/content/parts/0/text");
    if (text.isMissingNode()) {
      throw new IOException(
        "Unexpected Gemini response structure: " + response.body());
    }

    if (log.isTraceEnabled()) {
      log.trace("GeminiClient[{}]: response: {}", keyHint(), response.body());
    }

    return new AIResponse(text.asText(), GeminiClient.class.getSimpleName(), MODEL);
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public String getModel() {

    return MODEL;
  }


  /**
   * Sends {@code request} and retries up to two times on a 503 response,
   * waiting two seconds between attempts. The Gemini API returns 503 under
   * momentary load spikes and asks callers to retry.
   *
   * @param request the prepared HTTP request
   * @return the HTTP response with status 200
   * @throws QuotaExceededException if the response status is 429
   * @throws IOException            if the request fails, is interrupted, or
   *                                returns a non-200 status after all retries
   */
  private HttpResponse<String> send(HttpRequest request) throws IOException {

    int attempts = 0;
    while (true) {
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Gemini API request interrupted", e);
      }

      log.debug("GeminiClient[{}]: HTTP {}", keyHint(), response.statusCode());

      if (response.statusCode() == 429) {
        if (log.isDebugEnabled()) {
          response
            .headers()
            .map()
            .forEach((name, values) -> log
              .warn("GeminiClient[{}]: 429 header {}: {}", keyHint(), name,
                String.join(", ", values)));
          log.debug("GeminiClient[{}]: 429 body: {}", keyHint(), response.body());
        }
        throw new QuotaExceededException("Gemini API error 429: " + response.body());
      }

      if (response.statusCode() != 503 || ++attempts >= 3) {
        if (response.statusCode() != 200) {
          throw new IOException(
            "Gemini API error " + response.statusCode() + ": " + response.body());
        }
        return response;
      }

      log
        .debug("GeminiClient[{}]: 503 (attempt {}); retrying in 2 s", keyHint(),
          attempts);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("Gemini retry interrupted", ie);
      }
    }
  }


  /**
   * Returns {@code "image/png"} when {@code bytes} begins with the PNG magic
   * header ({@code \x89PNG}), {@code "image/jpeg"} otherwise.
   *
   * @param bytes raw image data
   * @return the detected MIME type string
   */
  private String detectMimeType(byte[] bytes) {

    if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
      && bytes[2] == 'N' && bytes[3] == 'G') {
      return "image/png";
    }
    return "image/jpeg";
  }


  /**
   * Returns the last four characters of the API key for use in log messages,
   * avoiding accidental full-key exposure in logs.
   *
   * @return four-character key suffix
   */
  public String keyHint() {

    return apiKey.substring(apiKey.length() - 4);
  }

}
