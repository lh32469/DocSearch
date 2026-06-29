package org.gpc4j.docs.model;

/**
 * Google Gemini-specific extension of {@link ApiKeys}.
 *
 * <p>Stored in RavenDB under the collection {@code "GeminiApiKeys"} with
 * document IDs of the form {@code "GeminiApiKeys/<user>"}. Each key in
 * {@link ApiKeys#getKeys()} is a Gemini {@code X-goog-api-key} value used by
 * {@link org.gpc4j.docs.services.GeminiClient}.
 */
public class GeminiApiKeys extends ApiKeys {

}
