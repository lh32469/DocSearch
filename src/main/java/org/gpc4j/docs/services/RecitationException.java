package org.gpc4j.docs.services;

import java.io.IOException;

/**
 * Thrown by {@link GeminiClient} when the Gemini API returns a
 * {@code RECITATION} finish reason.
 *
 * <p>RECITATION indicates the model declined to reproduce text it recognises
 * from its training corpus. Callers should record the error on the associated
 * {@link org.gpc4j.docs.model.DocSearchPage} and skip line extraction
 * rather than retrying or falling back to another provider.
 */
public class RecitationException extends IOException {

  /**
   * Constructs an exception with the given detail message.
   *
   * @param message a description of the page or key that triggered RECITATION
   */
  public RecitationException(String message) {

    super(message);
  }


  public RecitationException() {

  }

}
