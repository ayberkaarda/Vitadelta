package com.vitadelta.api.mail;

import com.vitadelta.api.common.logging.EmailMasking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Asynchronous delivery of identity and plan e-mails (project specification sections 8.2 and 8.7).
 * Rendering and sending always run on the dispatcher's own bounded thread pool, never on the
 * caller's thread. A full queue drops the mail; rendering and SMTP failures are logged with a
 * masked address and counted. None of these outcomes ever reaches the caller as an exception, so
 * auth responses do not depend on the state of the mail server.
 *
 * <p>Metrics: {@code vitadelta.mail.sent}, {@code vitadelta.mail.failed} (tags {@code template}),
 * {@code vitadelta.mail.dropped} (tags {@code template}, {@code reason} = {@code queue_full} or
 * {@code shutdown}) and the gauge {@code vitadelta.mail.queue.size}.
 */
@Component
public class AuthMailDispatcher implements DisposableBean {

  public static final String SENT = "vitadelta.mail.sent";
  public static final String FAILED = "vitadelta.mail.failed";
  public static final String DROPPED = "vitadelta.mail.dropped";
  public static final String QUEUE_SIZE = "vitadelta.mail.queue.size";
  public static final String REASON_QUEUE_FULL = "queue_full";
  public static final String REASON_SHUTDOWN = "shutdown";

  /** Name prefix of the sender threads. */
  public static final String THREAD_PREFIX = "vitadelta-mail-";

  private static final Logger LOG = LoggerFactory.getLogger(AuthMailDispatcher.class);

  private final MailTemplateRenderer renderer;
  private final EmailSender sender;
  private final MeterRegistry registry;
  private final MailDispatchProperties properties;
  private final ThreadPoolExecutor executor;

  public AuthMailDispatcher(
      MailTemplateRenderer renderer,
      EmailSender sender,
      MeterRegistry registry,
      MailDispatchProperties properties) {
    this.renderer = renderer;
    this.sender = sender;
    this.registry = registry;
    this.properties = properties;
    BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    this.executor =
        new ThreadPoolExecutor(
            properties.poolSize(),
            properties.poolSize(),
            0L,
            TimeUnit.MILLISECONDS,
            queue,
            Thread.ofPlatform().name(THREAD_PREFIX, 1).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    registry.gauge(QUEUE_SIZE, queue, BlockingQueue::size);
  }

  /**
   * Queues {@code mail} for asynchronous rendering and delivery.
   *
   * @return true when the mail was queued, false when it was dropped (queue full or shutting down)
   */
  public boolean dispatch(OutgoingMail mail) {
    try {
      executor.execute(new Delivery(mail));
      return true;
    } catch (RejectedExecutionException e) {
      String reason = executor.isShutdown() ? REASON_SHUTDOWN : REASON_QUEUE_FULL;
      drop(mail, reason);
      return false;
    }
  }

  /**
   * Queues {@code mail} once the current transaction commits; nothing is sent when it rolls back.
   * Without an active transaction synchronization the mail is queued immediately.
   */
  public void dispatchAfterCommit(OutgoingMail mail) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      dispatch(mail);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            dispatch(mail);
          }
        });
  }

  /** Stops accepting mails and waits up to the shutdown timeout for queued mails to be sent. */
  @Override
  public void destroy() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(
          properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        abandonQueued();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      abandonQueued();
    }
  }

  private void abandonQueued() {
    List<Runnable> pending = executor.shutdownNow();
    for (Runnable runnable : pending) {
      if (runnable instanceof Delivery delivery) {
        drop(delivery.mail, REASON_SHUTDOWN);
      }
    }
  }

  private void drop(OutgoingMail mail, String reason) {
    Counter.builder(DROPPED)
        .description("E-mails dropped before delivery")
        .tag("template", mail.model().templateName())
        .tag("reason", reason)
        .register(registry)
        .increment();
    LOG.warn(
        "Mail dropped (reason={}, template={}, to={})",
        reason,
        mail.model().templateName(),
        EmailMasking.mask(mail.to()));
  }

  private void deliver(OutgoingMail mail) {
    String template = mail.model().templateName();
    try {
      sender.send(renderer.render(mail));
      Counter.builder(SENT)
          .description("E-mails delivered to the mail server")
          .tag("template", template)
          .register(registry)
          .increment();
    } catch (RuntimeException e) {
      Counter.builder(FAILED)
          .description("E-mails whose rendering or delivery failed")
          .tag("template", template)
          .register(registry)
          .increment();
      LOG.warn(
          "Mail delivery failed (template={}, to={}, error={}, cause={})",
          template,
          EmailMasking.mask(mail.to()),
          e.getClass().getSimpleName(),
          rootCause(e).getClass().getSimpleName());
    }
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private final class Delivery implements Runnable {

    private final OutgoingMail mail;

    private Delivery(OutgoingMail mail) {
      this.mail = mail;
    }

    @Override
    public void run() {
      deliver(mail);
    }
  }
}
