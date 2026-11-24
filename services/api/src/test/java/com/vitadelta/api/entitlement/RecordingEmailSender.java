package com.vitadelta.api.entitlement;

import com.vitadelta.api.mail.EmailMessage;
import com.vitadelta.api.mail.EmailSender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;

/** Records every message the mail dispatcher hands over instead of sending it. */
public final class RecordingEmailSender implements EmailSender {

  private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

  @Override
  public void send(EmailMessage message) {
    sent.add(message);
  }

  /** Messages to {@code address}. */
  public List<EmailMessage> to(String address) {
    return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).toList();
  }

  /** Waits until {@code count} messages to {@code address} were handed over. */
  public List<EmailMessage> await(String address, int count) {
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> to(address).size() >= count);
    return to(address);
  }
}
