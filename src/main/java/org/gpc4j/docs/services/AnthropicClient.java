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
 * Low-level HTTP client for the Anthropic Messages API.
 *
 * <p>A single API key is bound at construction time and used for every
 * request. Unlike {@link AnthropicService} this class is not a Spring bean
 * and has no key rotation; it is intended for use cases where one instance
 * per key runs concurrently, such as a fallback within
 * {@link org.gpc4j.docs.tasks.PageRefreshTaskKey}.
 *
 * <p>Both text-only and multimodal (text + image) prompts are supported.
 * Images are base64-encoded and sent as {@code image} content parts.
 * PNG and JPEG formats are auto-detected from the image byte header.
 *
 * <p>This class is not a Spring {@code @Component}. Callers are responsible
 * for instantiation.
 */
public class AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

  private static final String MODEL = "claude-haiku-4-5-20251001";

  private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private static final int MAX_TOKENS = 4096;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String apiKey;

  /**
   * Constructs a client bound to the given Anthropic API key.
   *
   * <p>The HTTP client is forced to HTTP/1.1 to avoid potential issues with
   * large multimodal payloads over HTTP/2.
   *
   * @param apiKey the {@code x-api-key} header value sent with every request
   */
  public AnthropicClient(String apiKey) {

    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
  }


  /**
   * Submits {@code prompt} to the Anthropic Messages API and returns the
   * response text along with AI provenance metadata.
   *
   * <p>When {@link AIPrompt#getImage()} is non-null and non-empty the image
   * bytes are base64-encoded and prepended as an {@code image} content part,
   * enabling Claude vision on the supplied image.
   *
   * @param prompt the text (and optional image) to send
   * @return the AI response containing extracted text and provenance fields
   * @throws IOException if the request fails, is interrupted, or Anthropic
   *                     returns a non-200 status or unexpected response
   *                     structure
   */
  public AIResponse queryResponse(AIPrompt prompt) throws IOException {

    List<Map<String, Object>> content = new LinkedList<>();

    if (prompt.getImage() != null && prompt.getImage().length > 0) {
      String mimeType = detectMimeType(prompt.getImage());
      String b64 = Base64.getEncoder().encodeToString(prompt.getImage());
      content
        .add(Map
          .of("type", "image", "source",
            Map.of("type", "base64", "media_type", mimeType, "data", b64)));
      log
        .debug("AnthropicClient[{}]: attaching {} image ({} bytes)", keyHint(),
          mimeType, prompt.getImage().length);
    }

    content.add(Map.of("type", "text", "text", prompt.getText()));

    Map<String, Object> body = Map
      .of("model", MODEL, "max_tokens", MAX_TOKENS, "messages",
        List.of(Map.of("role", "user", "content", content)));

    String requestJson = MAPPER.writeValueAsString(body);
    log
      .debug("AnthropicClient[{}]: sending request ({} chars)", keyHint(),
        requestJson.length());

    HttpRequest request = HttpRequest
      .newBuilder()
      .uri(URI.create(ANTHROPIC_URL))
      .header("Content-Type", "application/json")
      .header("x-api-key", apiKey)
      .header("anthropic-version", ANTHROPIC_VERSION)
      .POST(HttpRequest.BodyPublishers.ofString(requestJson))
      .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Anthropic API request interrupted", e);
    }

    log.debug("AnthropicClient[{}]: HTTP {}", keyHint(), response.statusCode());

    if (response.statusCode() != 200) {
      log
        .warn("AnthropicClient[{}]: error {} body: {}", keyHint(),
          response.statusCode(), response.body());
      throw new IOException(
        "Anthropic API error " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = MAPPER.readTree(response.body());
    JsonNode text = root.at("/content/0/text");
    if (text.isMissingNode()) {
      throw new IOException(
        "Unexpected Anthropic response structure: " + response.body());
    }

    if (log.isTraceEnabled()) {
      log.trace("AnthropicClient[{}]: response: {}", keyHint(), response.body());
    }

    return new AIResponse(text.asText(), AnthropicClient.class.getSimpleName(),
      MODEL);
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
