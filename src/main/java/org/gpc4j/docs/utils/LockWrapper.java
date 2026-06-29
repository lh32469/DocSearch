package org.gpc4j.docs.utils;

import lombok.extern.slf4j.Slf4j;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.compareExchange.CompareExchangeResult;
import net.ravendb.client.documents.operations.compareExchange.DeleteCompareExchangeValueOperation;
import net.ravendb.client.documents.operations.compareExchange.PutCompareExchangeValueOperation;

/**
 * Try-with-resources wrapper around a RavenDB Compare-Exchange distributed lock.
 *
 * <p>On construction an atomic CAS entry is created under the key
 * {@code "PageLock/<lockName>"}. Because the entry is written with index
 * {@code 0} (must-not-exist), the operation succeeds only when no other cluster
 * node holds the lock. Check {@link #isSuccessful()} before doing work.
 *
 * <p>{@link #close()} unconditionally deletes the Compare-Exchange entry using
 * the version index returned at lock time, so the lock is always released when
 * the {@code try}-block exits — even if an exception is thrown.
 *
 * <p>Usage:
 * <pre>{@code
 * try (LockWrapper lock = new LockWrapper(doc.getId(), store)) {
 *   if (lock.isSuccessful()) {
 *     processDoc(session, doc);
 *   }
 * }
 * }</pre>
 */
@Slf4j
public class LockWrapper implements AutoCloseable {

  private final String lockKey;
  private final IDocumentStore store;

  CompareExchangeResult<String> lock;

  /**
   * Attempts to acquire a cluster-wide lock for {@code lockName}.
   *
   * <p>The Compare-Exchange key is {@code "PageLock/<lockName>"}. The
   * attempt is non-blocking: if the entry already exists the operation
   * returns immediately with {@link #isSuccessful()} returning {@code false}.
   *
   * @param lockName the logical resource name; prefixed with {@code "PageLock/"}
   *                 to form the Compare-Exchange key
   * @param store    the RavenDB document store used to submit the CAS operation
   */
  public LockWrapper(String lockName, IDocumentStore store) {

    this.lockKey = "PageLock/" + lockName;
    this.store = store;

    lock = store
      .operations()
      .send(new PutCompareExchangeValueOperation<String>(lockKey, "ASDF", 0L));

    if (!lock.isSuccessful()) {
      log
        .debug("LockWrapper: '{}' already locked by another node, skipping",
          lockKey);
    }
  }


  /**
   * Releases the lock by deleting the Compare-Exchange entry.
   *
   * <p>Always called by the {@code try}-with-resources block, even when the
   * initial lock attempt failed — in that case the delete is a no-op because
   * the index will not match any live entry.
   *
   * @throws Exception if the RavenDB delete operation fails
   */
  @Override
  public void close() throws Exception {

    store
      .operations()
      .send(new DeleteCompareExchangeValueOperation<>(String.class, lockKey,
        lock.getIndex()));
  }


  /**
   * Returns {@code true} if the Compare-Exchange entry was created successfully,
   * meaning this instance holds the lock and the caller may safely process the
   * resource.
   *
   * @return {@code true} if the lock was acquired; {@code false} if another
   *         node already holds it
   */
  public boolean isSuccessful() {

    return lock.isSuccessful();
  }

}
