package org.gpc4j.docs.model;

import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * A named, enable-able collection of API keys belonging to a single user.
 *
 * <p>Intended to be stored as a RavenDB document so that keys can be managed
 * at runtime without redeploying the application. Subclasses (e.g.
 * {@link GeminiApiKeys}) represent provider-specific key sets and each get
 * their own RavenDB collection via the subclass name embedded in
 * {@link #getId()}.
 */
@Data
public class ApiKeys {

  /**
   * When {@code false} this key set is ignored by all scheduling components.
   * Defaults to {@code true}.
   */
  private boolean enabled = true;

  /**
   * Identifies the owner of this key set; also forms part of the RavenDB
   * document ID (e.g. {@code "GeminiApiKeys/alice"}).
   */
  private String user;

  /**
   * The raw API key strings for this user and provider. Never serialised into
   * log output; treat as secret.
   */
  private List<String> keys = new LinkedList<>();

  /**
   * Returns the RavenDB document ID for this key set, derived from the
   * concrete subclass name and {@link #getUser()}.
   *
   * <p>The {@code @JsonIgnore} annotation prevents the synthetic getter from
   * being stored as a field in the document.
   *
   * @return a string of the form {@code "<ClassName>/<user>"}
   */
  @JsonIgnore
  public String getId() {

    return getClass().getSimpleName() + "/" + user;
  }

}
