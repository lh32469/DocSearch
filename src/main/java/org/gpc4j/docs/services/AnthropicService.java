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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AIService} implementation that submits prompts to the Anthropic
 * Messages API and returns the response text.
 *
 * <p>Both text-only and multimodal (text + image) prompts are supported via
 * the {@code claude-opus-4-8} model. Images are base64-encoded and sent as
 * {@code image} content parts, enabling Claude's vision capabilities.
 * PNG and JPEG formats are auto-detected from the image byte header.
 *
 * <p>The API key is read from the {@code anthropic.api.key} application
 * property. Inject {@code @Qualifier("anthropicService")} to select this
 * provider when multiple implementations are on the classpath.
 */
@Service
public class AnthropicService implements AIService {

  private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);

  private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

  private static final String MODEL = "claude-haiku-4-5-20251001";

  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private static final int MAX_TOKENS = 4096;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String apiKey;

  /**
   * Creates the service with the given Anthropic API key.
   *
   * @param apiKey the {@code x-api-key} header value sent with every
   *               request to the Anthropic Messages API
   */
  public AnthropicService(@Value("${anthropic.api.key}") String apiKey) {

    this.apiKey = apiKey;
    // HTTP/1.1 avoids potential HTTP/2 issues with large multimodal payloads
    this.http = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public String getModel() {

    return MODEL;
  }


  /**
   * {@inheritDoc}
   *
   * <p>When {@link AIPrompt#getImage()} is non-null and non-empty the image
   * bytes are base64-encoded and sent as an {@code image} content part before
   * the text part, enabling Claude vision on the supplied image.
   */
  @Override
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
        .debug("Attaching {} image ({} bytes) to Anthropic request", mimeType,
          prompt.getImage().length);
    }

    content.add(Map.of("type", "text", "text", prompt.getText()));

    Map<String, Object> body = Map
      .of("model", MODEL, "max_tokens", MAX_TOKENS, "messages",
        List.of(Map.of("role", "user", "content", content)));

    String requestJson = MAPPER.writeValueAsString(body);
    log.debug("Sending Anthropic request ({} chars)", requestJson.length());

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

    if (log.isTraceEnabled()) {
      log.trace("Anthropic request response: {}", response);
      log.trace("Anthropic response body: {}", response.body());
    }

    log.debug("Anthropic HTTP {}", response.statusCode());
    if (response.statusCode() != 200) {
      throw new IOException(
        "Anthropic API error " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = MAPPER.readTree(response.body());
    JsonNode text = root.at("/content/0/text");
    if (text.isMissingNode()) {
      throw new IOException(
        "Unexpected Anthropic response structure: " + response.body());
    }
    return new AIResponse(text.asText(), getClass().getSimpleName(), MODEL);
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

}
