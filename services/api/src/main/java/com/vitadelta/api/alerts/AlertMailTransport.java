package com.vitadelta.api.alerts;

import com.vitadelta.api.mail.EmailDeliveryException;
import com.vitadelta.api.mail.EmailMessage;

/**
 * Delivers one alert or cap e-mail synchronously, with a {@code List-Unsubscribe} header pointing
 * to the notification preferences (section 8.7). It is called by the outbox processor outside any
 * transaction or row lock.
 */
public interface AlertMailTransport {

  /**
   * Delivers {@code message}.
   *
   * @param unsubscribeUrl absolute https link of the notification preferences page
   * @throws EmailDeliveryException when the message cannot be delivered
   */
  void send(EmailMessage message, String unsubscribeUrl);
}
