package org.gpc4j.docs.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test that lists available Gemini models by calling the
 * {@code /v1beta/models} endpoint and printing the raw JSON response.
 *
 * <p>The API key is read from the {@code gemini.api.key} application property.
 * RavenDB does not need to be running.
 *
 * <p>Run manually with: {@code ./mvnw test -Dtest=GeminiModelsIT}
 */
@SpringBootTest(properties = "ravendb.urls=http://localhost:8080")
class GeminiModelsIT {

  private static final String MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models";

  @Value("${gemini.api.keys[0]}")
  private String apiKey;

  /**
   * Fetches the list of available Gemini models and prints each {@code "name"}
   * field to stdout.
   *
   * @throws Exception if the HTTP request fails
   */
  @Test
  void listModels() throws Exception {

    HttpClient http = HttpClient.newHttpClient();

    HttpRequest request = HttpRequest
      .newBuilder()
      .uri(URI.create(MODELS_URL + "?key=" + apiKey))
      .GET()
      .build();

    HttpResponse<String> response = http
      .send(request, HttpResponse.BodyHandlers.ofString());

    System.out.println("HTTP " + response.statusCode());
    response
      .body()
      .lines()
      .filter(l -> l.contains("\"name\""))
      .forEach(System.out::println);
  }

}
