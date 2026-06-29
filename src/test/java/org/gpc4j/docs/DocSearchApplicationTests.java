package org.gpc4j.docs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot context load test for {@link DocSearchApplication}.
 */
@SpringBootTest(properties = "ravendb.urls=http://localhost:8080")
class DocSearchApplicationTests {

  @Test
  void contextLoads() {
  }

}
