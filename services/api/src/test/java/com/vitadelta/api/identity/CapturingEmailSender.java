package com.vitadelta.api.identity;

import com.vitadelta.api.mail.EmailDeliveryException;
import com.vitadelta.api.mail.EmailMessage;
import com.vitadelta.api.mail.EmailSender;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;

/**
 * Records every message the dispatcher hands to the sender; {@link #failing(boolean)} simulates an
 * SMTP outage (the message is recorded as attempted and the send throws).
 */
final class CapturingEmailSender implements EmailSender {

  private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

  private final List<EmailMessage> attempted = new CopyOnWriteArrayList<>();
  private volatile boolean failing;

  @Override
  public void send(EmailMessage message) {
    attempted.add(message);
    if (failing) {
      throw new EmailDeliveryException("simulated SMTP outage", null);
    }
  }

  void failing(boolean value) {
    this.failing = value;
  }

  List<EmailMessage> to(String address) {
    return attempted.stream().filter(m -> m.to().equalsIgnoreCase(address)).toList();
  }

  /** Waits until {@code count} messages to {@code address} were attempted. */
  List<EmailMessage> awaitMessages(String address, int count) {
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> to(address).size() >= count);
    return to(address);
  }

  /** The token of the newest message to {@code address}, waiting for {@code count} messages. */
  String awaitToken(String address, int count) {
    List<EmailMessage> messages = awaitMessages(address, count);
    return token(messages.get(messages.size() - 1)).orElseThrow();
  }

  static Optional<String> token(EmailMessage message) {
    Matcher matcher = TOKEN.matcher(message.textBody());
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }
}
