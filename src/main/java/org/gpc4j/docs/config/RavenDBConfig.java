package org.gpc4j.docs.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.ravendb.client.documents.DocumentStore;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.http.ServerNode;

/**
 * Configuration class for RavenDB.
 */
@Configuration
public class RavenDBConfig {

  private static final Logger log = LoggerFactory.getLogger(RavenDBConfig.class);

  @Value("${ravendb.database}")
  private String databaseName;

  @Value("${ravendb.urls}")
  private List<String> urls;

  @Bean
  public IDocumentStore documentStore() {

    log.info("URLs: {}", urls);

    DocumentStore store = new DocumentStore(urls.toArray(new String[0]),
      databaseName);

    // Map renamed model classes to their original RavenDB collection names so
    // existing documents are found without a database migration.
    //    store.getConventions().setFindCollectionName(clazz -> {
    //      if (clazz == DocSearchDoc.class || clazz == DocSearchPage.class) {
    //        return "EasementDocs";
    //      }
    //      return DocumentConventions.defaultGetCollectionName(clazz);
    //    });

    // Configure Jackson ObjectMapper for proper DateTime handling
    ObjectMapper mapper = store.getConventions().getEntityMapper();
    mapper.registerModule(new JavaTimeModule());

    //    SimpleModule module = new SimpleModule();
    //    // Format Doubles as Strings in to 4 decimal places
    //    module.addSerializer(Double.class, new CustomDoubleSerializer());
    //    mapper.registerModule(module);

    store.initialize();
    return store;
  }


  @Bean(destroyMethod = "close")
  @RequestScope
  public IDocumentSession session(IDocumentStore store) {

    IDocumentSession session = store.openSession();
    if (log.isDebugEnabled()) {
      ServerNode currentNode = session.advanced().getCurrentSessionNode();
      log.debug("Read from node: " + currentNode.getClusterTag());
    }
    return session;
  }

}
