package org.gpc4j.docs.services;

import java.io.IOException;

import org.gpc4j.docs.model.AIPrompt;
import org.gpc4j.docs.model.AIResponse;

/**
 * Contract for submitting prompts to an AI language model and returning the
 * response text.
 *
 * <p>Implementations target specific providers (e.g. {@link GeminiClient}
 * for Google Gemini, {@link OpenAIService} for OpenAI,
 * {@link AnthropicService} for Anthropic Claude). Callers depend only on
 * this interface so the underlying provider can be swapped without changes
 * to the calling code.
 */
public interface AIService {

  /**
   * Submits the specified {@code prompt} to the AI provider and returns a
   * structured response containing metadata (e.g., AI service name, model
   * identifier, and confidence score) along with the AI's text response.
   *
   * @param prompt the input payload including the textual prompt (required) and
   *               an optional image; must not be null
   * @return an {@link AIResponse} containing the AI's response, service name,
   * model identifier, and confidence score; never null on success
   * @throws IOException if the query fails due to network issues or if the
   *                     provider returns an error
   */
  AIResponse queryResponse(AIPrompt prompt) throws IOException;


  /**
   * Returns the model identifier used by this implementation when calling the
   * AI provider (e.g. {@code "gemini-1.5-flash"}, {@code "gpt-4o"},
   * {@code "claude-haiku-4-5-20251001"}).
   *
   * @return the model ID string; never null
   */
  String getModel();

}
