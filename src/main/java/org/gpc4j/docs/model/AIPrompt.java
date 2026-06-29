package org.gpc4j.docs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Input payload for the AI query service.
 *
 * <p>At minimum a {@link #text} prompt must be supplied. An optional
 * {@link #image} (raw PNG/JPEG bytes) may be included; when present it is
 * uploaded as an attachment alongside the text prompt.
 *
 * <p>When {@link #apiKey} is set the service implementation should use that
 * specific key for the request rather than its default key-rotation strategy.
 * {@code null} means use the service default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIPrompt {

  /** The text prompt to send to the AI. Must not be null or blank. */
  private String text;

  /**
   * Optional image to attach to the prompt (PNG or JPEG bytes).
   * {@code null} means text-only.
   */
  private byte[] image;

  /**
   * Optional API key override. When non-null, the AI service uses this key
   * instead of its default rotation, allowing per-task key affinity.
   */
  private String apiKey;

}
