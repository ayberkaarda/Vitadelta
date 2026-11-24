package com.vitadelta.api.mail;

/**
 * Outgoing e-mail port. Implementations deliver synchronously; callers on a request thread hand
 * messages to {@link AuthMailDispatcher} instead of calling this port directly.
 */
public interface EmailSender {

  /**
   * Delivers {@code message}.
   *
   * @throws EmailDeliveryException when the message cannot be delivered
   */
  void send(EmailMessage message);
}
