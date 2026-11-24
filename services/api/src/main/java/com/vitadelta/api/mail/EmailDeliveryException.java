package com.vitadelta.api.mail;

/**
 * Delivery of an e-mail failed. The message never contains the recipient address or any part of the
 * e-mail content.
 */
public class EmailDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailDeliveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
