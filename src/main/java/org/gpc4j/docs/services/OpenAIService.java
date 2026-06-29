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
 * {@link AIService} implementation that submits prompts to the OpenAI Chat
 * Completions API and returns the response text.
 *
 * <p>Both text-only and multimodal (text + image) prompts are supported via
 * the {@code gpt-4o} model. Images are base64-encoded and sent as
 * {@code image_url} content parts using a data URI, enabling GPT-4o vision.
 * PNG and JPEG formats are auto-detected from the image byte header.
 *
 * <p>The API key is read from the {@code openai.api.key} application property.
 * Inject {@code @Qualifier("openAIService")} to select this provider
 * specifically when multiple {@link AIService} implementations are on the
 * classpath.
 */
@Service
public class OpenAIService implements AIService {

  private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

  private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

  private static final String MODEL = "gpt-4o";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String apiKey;

  /**
   * Creates the service with the given OpenAI API key.
   *
   * @param apiKey the {@code Authorization: Bearer} value sent with every
   *               request to the OpenAI API
   */
  public OpenAIService(@Value("${openai.api.key}") String apiKey) {

    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
  }


  /** {@inheritDoc} */
  @Override
  public String getModel() {

    return MODEL;
  }


  /**
   * {@inheritDoc}
   *
   * <p>When {@link AIPrompt#getImage()} is non-null and non-empty the image
   * bytes are base64-encoded and sent as an {@code image_url} content part
   * (data URI) before the text part, enabling GPT-4o vision.
   */
  @Override
  public AIResponse queryResponse(AIPrompt prompt) throws IOException {

    List<Map<String, Object>> content = new LinkedList<>();

    if (prompt.getImage() != null && prompt.getImage().length > 0) {
      String mimeType = detectMimeType(prompt.getImage());
      String b64 = Base64.getEncoder().encodeToString(prompt.getImage());
      content
        .add(Map
          .of("type", "image_url", "image_url",
            Map.of("url", "data:" + mimeType + ";base64," + b64)));
      log
        .debug("Attaching {} image ({} bytes) to OpenAI request", mimeType,
          prompt.getImage().length);
    }

    content.add(Map.of("type", "text", "text", prompt.getText()));

    Map<String, Object> body = Map
      .of("model", MODEL, "messages",
        List.of(Map.of("role", "user", "content", content)));

    String requestJson = MAPPER.writeValueAsString(body);
    log.debug("Sending OpenAI request ({} chars)", requestJson.length());

    HttpRequest request = HttpRequest
      .newBuilder()
      .uri(URI.create(OPENAI_URL))
      .header("Content-Type", "application/json")
      .header("Authorization", "Bearer " + apiKey)
      .POST(HttpRequest.BodyPublishers.ofString(requestJson))
      .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("OpenAI API request interrupted", e);
    }

    log.debug("OpenAI HTTP {}", response.statusCode());
    if (response.statusCode() != 200) {
      throw new IOException(
        "OpenAI API error " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = MAPPER.readTree(response.body());
    JsonNode text = root.at("/choices/0/message/content");
    if (text.isMissingNode()) {
      throw new IOException(
        "Unexpected OpenAI response structure: " + response.body());
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
