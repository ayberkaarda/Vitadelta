package com.vitadelta.api.mail;

import static com.vitadelta.api.mail.MailTestSupport.TOKEN;
import static com.vitadelta.api.mail.MailTestSupport.count;
import static com.vitadelta.api.mail.MailTestSupport.dropped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.vitadelta.api.config.properties.MailSettingsProperties;
import com.vitadelta.api.support.TestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous hand-over, drop, failure and after-commit behaviour of {@link AuthMailDispatcher}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthMailDispatcherTest {

  private static final String ADDRESS =
      "dispatch.user" + TestEnvironment.randomHex(4) + "@example.org";
  private static final String MASKED = "d***@example.org";

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final RecordingSender recording = new RecordingSender();
  private AuthMailDispatcher dispatcher;

  @AfterEach
  void shutDown() {
    recording.release.countDown();
    if (dispatcher != null) {
      dispatcher.destroy();
    }
  }

  @Test
  void neverSendsOnTheCallerThreadAndReturnsWhileSmtpIsBusy() throws InterruptedException {
    recording.block = true;
    dispatcher = dispatcher(recording, 1, 10, Duration.ofSeconds(5));

    long started = System.nanoTime();
    assertThat(dispatcher.dispatch(OutgoingMail.verifyEmail(ADDRESS, Locale.ENGLISH, TOKEN)))
        .isTrue();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(recording.entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(elapsedMillis).isLessThan(1000);
    assertThat(recording.threads).hasSize(1);
    assertThat(recording.threads.getFirst())
        .startsWith(AuthMailDispatcher.THREAD_PREFIX)
        .isNotEqualTo(Thread.currentThread().getName());
    assertThat(recording.messages).isEmpty();

    recording.release.countDown();
    dispatcher.destroy();
    assertThat(recording.messages).hasSize(1);
    assertThat(recording.messages.getFirst().textBody())
        .contains(TestEnvironment.WEB_BASE_URL + "/auth/verify#token=" + TOKEN);
    assertThat(count(registry, AuthMailDispatcher.SENT, VerifyEmailMail.TEMPLATE)).isEqualTo(1);
  }

  @Test
  void fullQueueDropsTheMailWithMetricAndMaskedLogLine(CapturedOutput output)
      throws InterruptedException {
    recording.block = true;
    dispatcher = dispatcher(recording, 1, 1, Duration.ofSeconds(5));

    assertThat(dispatcher.dispatch(OutgoingMail.resetPassword(ADDRESS, Locale.ENGLISH, TOKEN)))
        .isTrue();
    assertThat(recording.entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dispatcher.dispatch(OutgoingMail.resetPassword(ADDRESS, Locale.ENGLISH, TOKEN)))
        .isTrue();
    assertThatCode(() ->
            assertThat(
                    dispatcher.dispatch(OutgoingMail.resetPassword(ADDRESS, Locale.ENGLISH, TOKEN)))
                .isFalse())
        .doesNotThrowAnyException();

    assertThat(dropped(registry, ResetPasswordMail.TEMPLATE, AuthMailDispatcher.REASON_QUEUE_FULL))
        .isEqualTo(1);
    assertThat(registry.get(AuthMailDispatcher.QUEUE_SIZE).gauge().value()).isEqualTo(1);

    recording.release.countDown();
    dispatcher.destroy();
    assertThat(recording.messages).hasSize(2);
    assertThat(output.getAll())
        .contains("Mail dropped")
        .contains(MASKED)
        .doesNotContain(ADDRESS)
        .doesNotContain(TOKEN);
  }

  @Test
  void smtpDownIsCountedAndLoggedButNeverReachesTheCaller(CapturedOutput output)
      throws IOException {
    dispatcher = dispatcher(unreachableSmtp(), 1, 10, Duration.ofSeconds(30));

    assertThatCode(
            () -> {
              assertThat(
                      dispatcher.dispatch(
                          OutgoingMail.verifyEmail(ADDRESS, Locale.forLanguageTag("tr"), TOKEN)))
                  .isTrue();
              assertThat(dispatcher.dispatch(OutgoingMail.alreadyRegistered(ADDRESS, null)))
                  .isTrue();
            })
        .doesNotThrowAnyException();
    dispatcher.destroy();

    assertThat(count(registry, AuthMailDispatcher.FAILED, VerifyEmailMail.TEMPLATE)).isEqualTo(1);
    assertThat(count(registry, AuthMailDispatcher.FAILED, AlreadyRegisteredMail.TEMPLATE))
        .isEqualTo(1);
    assertThat(count(registry, AuthMailDispatcher.SENT, VerifyEmailMail.TEMPLATE)).isZero();
    assertThat(output.getAll())
        .contains("Mail delivery failed")
        .contains("EmailDeliveryException")
        .contains(MASKED)
        .doesNotContain(ADDRESS)
        .doesNotContain(TOKEN);
  }

  @Test
  void renderingFailureIsCountedAsFailure() {
    dispatcher = dispatcher(recording, 1, 10, Duration.ofSeconds(5));
    MailModel missing =
        new MailModel() {
          @Override
          public String templateName() {
            return "missing-template";
          }

          @Override
          public java.util.Map<String, Object> variables(MailLinks links, MailLocale locale) {
            return java.util.Map.of();
          }
        };
    assertThat(dispatcher.dispatch(new OutgoingMail(ADDRESS, Locale.ENGLISH, missing))).isTrue();
    dispatcher.destroy();
    assertThat(count(registry, AuthMailDispatcher.FAILED, "missing-template")).isEqualTo(1);
    assertThat(recording.messages).isEmpty();
  }

  @Test
  void afterCommitHandOverSendsOnlyWhenTheTransactionCommits() {
    dispatcher = dispatcher(recording, 1, 10, Duration.ofSeconds(5));
    TransactionTemplate transactions = new TransactionTemplate(new NoOpTransactionManager());

    transactions.executeWithoutResult(status -> {
      dispatcher.dispatchAfterCommit(
          OutgoingMail.planActivated(
              ADDRESS, Locale.ENGLISH, "PRO", java.time.Instant.parse("2027-01-01T00:00:00Z")));
      assertThat(recording.messages).isEmpty();
    });
    transactions.executeWithoutResult(status -> {
      dispatcher.dispatchAfterCommit(OutgoingMail.planExpired(ADDRESS, Locale.ENGLISH, "PRO"));
      status.setRollbackOnly();
    });
    dispatcher.destroy();

    assertThat(recording.messages).hasSize(1);
    assertThat(recording.messages.getFirst().subject())
        .isEqualTo("Your VitaDelta PRO plan is active");
    assertThat(count(registry, AuthMailDispatcher.SENT, PlanActivatedMail.TEMPLATE)).isEqualTo(1);
    assertThat(count(registry, AuthMailDispatcher.SENT, PlanExpiredMail.TEMPLATE)).isZero();
  }

  @Test
  void afterCommitWithoutTransactionQueuesImmediately() {
    dispatcher = dispatcher(recording, 1, 10, Duration.ofSeconds(5));
    dispatcher.dispatchAfterCommit(OutgoingMail.planExpired(ADDRESS, Locale.ENGLISH, "PRO"));
    dispatcher.destroy();
    assertThat(recording.messages).hasSize(1);
  }

  @Test
  void shutdownDeliversQueuedMailsAndRejectsLaterOnes() {
    dispatcher = dispatcher(recording, 2, 10, Duration.ofSeconds(5));
    for (int i = 0; i < 5; i++) {
      assertThat(dispatcher.dispatch(OutgoingMail.alreadyRegistered(ADDRESS, Locale.ENGLISH)))
          .isTrue();
    }
    dispatcher.destroy();
    assertThat(recording.messages).hasSize(5);

    assertThat(dispatcher.dispatch(OutgoingMail.alreadyRegistered(ADDRESS, Locale.ENGLISH)))
        .isFalse();
    assertThat(
            dropped(registry, AlreadyRegisteredMail.TEMPLATE, AuthMailDispatcher.REASON_SHUTDOWN))
        .isEqualTo(1);
  }

  @Test
  void shutdownTimeoutAbandonsMailsStillQueued() throws InterruptedException {
    recording.block = true;
    dispatcher = dispatcher(recording, 1, 5, Duration.ofMillis(100));
    assertThat(dispatcher.dispatch(OutgoingMail.alreadyRegistered(ADDRESS, Locale.ENGLISH)))
        .isTrue();
    assertThat(recording.entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dispatcher.dispatch(OutgoingMail.planExpired(ADDRESS, Locale.ENGLISH, "PRO")))
        .isTrue();

    dispatcher.destroy();

    assertThat(dropped(registry, PlanExpiredMail.TEMPLATE, AuthMailDispatcher.REASON_SHUTDOWN))
        .isEqualTo(1);
  }

  @Test
  void interruptedShutdownAbandonsQueuedMailsAndKeepsTheInterruptFlag()
      throws InterruptedException {
    recording.block = true;
    dispatcher = dispatcher(recording, 1, 5, Duration.ofSeconds(30));
    assertThat(dispatcher.dispatch(OutgoingMail.alreadyRegistered(ADDRESS, Locale.ENGLISH)))
        .isTrue();
    assertThat(recording.entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dispatcher.dispatch(OutgoingMail.planExpired(ADDRESS, Locale.ENGLISH, "PRO")))
        .isTrue();

    Thread.currentThread().interrupt();
    dispatcher.destroy();

    assertThat(Thread.interrupted()).isTrue();
    assertThat(dropped(registry, PlanExpiredMail.TEMPLATE, AuthMailDispatcher.REASON_SHUTDOWN))
        .isEqualTo(1);
  }

  private AuthMailDispatcher dispatcher(
      EmailSender sender, int pool, int queue, Duration shutdownTimeout) {
    return new AuthMailDispatcher(
        MailTestSupport.renderer(),
        sender,
        registry,
        new MailDispatchProperties(pool, queue, shutdownTimeout));
  }

  /** An SMTP sender pointed at a local port nobody listens on. */
  private static EmailSender unreachableSmtp() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
    mailSender.setHost("127.0.0.1");
    mailSender.setPort(port);
    Properties properties = new Properties();
    properties.setProperty("mail.smtp.connectiontimeout", "2000");
    properties.setProperty("mail.smtp.timeout", "2000");
    mailSender.setJavaMailProperties(properties);
    return new SmtpEmailSender(mailSender, new MailSettingsProperties(TestEnvironment.MAIL_FROM));
  }

  /** Records delivered messages and the thread they were sent on; can block until released. */
  private static final class RecordingSender implements EmailSender {

    final List<EmailMessage> messages = new CopyOnWriteArrayList<>();
    final List<String> threads = new CopyOnWriteArrayList<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean block;

    @Override
    public void send(EmailMessage message) {
      threads.add(Thread.currentThread().getName());
      entered.countDown();
      if (block) {
        try {
          if (!release.await(30, TimeUnit.SECONDS)) {
            throw new EmailDeliveryException("test sender was not released", null);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new EmailDeliveryException("test sender interrupted", e);
        }
      }
      messages.add(message);
    }
  }

  /** A transaction manager without a resource, enough to drive transaction synchronization. */
  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // A resource-free transaction: there is nothing to open.
      assertThat(definition).isNotNull();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertThat(status.isCompleted()).isFalse();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      assertThat(status.isCompleted()).isFalse();
    }
  }
}
