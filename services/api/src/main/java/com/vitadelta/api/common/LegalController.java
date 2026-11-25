package com.vitadelta.api.common;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.config.properties.OperationsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/legal}: the controller contact of the privacy notice ({@code
 * VITADELTA_PRIVACY_CONTACT}, section 15.9). Outside the {@code dev} profile the application does
 * not start without it; a dev stack without a contact answers {@code 500 internal_error}.
 */
@RestController
@RequestMapping("/api/v1")
public class LegalController {

  private static final Logger LOG = LoggerFactory.getLogger(LegalController.class);

  /** Contract schema {@code LegalInfo}. */
  public record LegalInfo(String privacyContact) {}

  private final OperationsProperties operations;

  public LegalController(OperationsProperties operations) {
    this.operations = operations;
  }

  @GetMapping(path = "/legal", produces = MediaType.APPLICATION_JSON_VALUE)
  public LegalInfo legal() {
    if (operations.privacyContact() == null) {
      LOG.warn("GET /legal: VITADELTA_PRIVACY_CONTACT is not set");
      throw ProblemException.of(ProblemCode.INTERNAL_ERROR, "The request could not be processed.");
    }
    return new LegalInfo(operations.privacyContact());
  }
}
