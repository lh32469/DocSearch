package org.gpc4j.docs.model;

public record AIResponse(
  /*
   * Text response from AI.
   */
  String text,
  /*
   * Simple class name of the {@link org.gpc4j.docs.services.AIService}
   * implementation that extracted this page's text, e.g.
   * {@code "GeminiClient"} or {@code "AnthropicService"}. May differ from
   * the document-level value when a per-page fallback used a different
   * provider.
   */
  String aiServiceName,

  /*
   * Model identifier reported by the AI service for this page, e.g.
   * {@code gemini-3.1-flash-lite} or {@code claude-haiku-4-5-20251001}.
   */
  String aiModel) {

}
