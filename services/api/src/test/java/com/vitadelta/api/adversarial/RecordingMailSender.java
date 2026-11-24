package com.vitadelta.api.adversarial;

import com.vitadelta.api.mail.EmailDeliveryException;
import com.vitadelta.api.mail.EmailMessage;
import com.vitadelta.api.mail.EmailSender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;

/**
 * Records every e-mail handed to the sender. {@link #failing(boolean)} simulates an SMTP outage:
 * the message is recorded as attempted and the send throws.
 */
public final class RecordingMailSender implements EmailSender {

  static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

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

  /** Waits until at least {@code count} messages to {@code address} were attempted. */
  List<EmailMessage> await(String address, int count) {
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> to(address).size() >= count);
    return to(address);
  }

  /** The token of the newest message to {@code address}, waiting for {@code count} messages. */
  String awaitToken(String address, int count) {
    List<EmailMessage> messages = await(address, count);
    return token(messages.get(messages.size() - 1).textBody());
  }

  static String token(String text) {
    Matcher matcher = TOKEN.matcher(text);
    if (!matcher.find()) {
      throw new AssertionError("no token link in the e-mail");
    }
    return matcher.group(1);
  }
}
