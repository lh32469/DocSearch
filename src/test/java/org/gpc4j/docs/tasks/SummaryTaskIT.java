package org.gpc4j.docs.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = "ravendb.urls=http://192.168.0.5:8080")
public class SummaryTaskIT {

  @Autowired
  SummaryTask summaryTask;

  @Test
  public void summaryTask() {
    summaryTask.summarize();
  }

}
