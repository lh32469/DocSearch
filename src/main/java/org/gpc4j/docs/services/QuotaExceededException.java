package org.gpc4j.docs.services;

import java.io.IOException;

/**
 * Thrown when an AI provider returns HTTP 429 (Too Many Requests), indicating
 * that the current quota or rate limit has been exhausted. Callers may use
 * this to back off before retrying rather than treating it as a permanent
 * failure.
 */
public class QuotaExceededException extends IOException {

  /**
   * Constructs an exception with the given detail message.
   *
   * @param message description of the quota error, typically including the
   *                provider's response body
   */
  public QuotaExceededException(String message) {

    super(message);
  }

}
