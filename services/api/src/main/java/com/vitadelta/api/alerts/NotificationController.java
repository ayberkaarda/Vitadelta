package com.vitadelta.api.alerts;

import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.CurrentUser;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification inbox of the signed-in caller (section 8.6). An unknown, foreign or non-UUID
 * notification id answers the same {@code 404} ({@code about:blank}). Payload money is native and
 * never converted.
 */
@RestController
@RequestMapping(path = "/api/v1/me/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationController {

  private final NotificationRepository notifications;
  private final Clock clock;

  public NotificationController(NotificationRepository notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  @GetMapping
  public AlertViews.NotificationPage list(
      @RequestParam(required = false) String unreadOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    List<FieldViolation> errors = new ArrayList<>();
    boolean unread = false;
    if (unreadOnly != null) {
      if (unreadOnly.equals("true")) {
        unread = true;
      } else if (!unreadOnly.equals("false")) {
        errors.add(
            new FieldViolation(
                "unreadOnly", JsonFields.INVALID_FORMAT, "unreadOnly must be true or false."));
      }
    }
    PageQuery paging = null;
    try {
      paging = PageQuery.of(page, pageSize);
    } catch (ProblemException e) {
      errors.addAll(e.errors());
    }
    JsonFields.failIfInvalid(errors);
    long total = notifications.count(userId, unread);
    List<AlertViews.Notification> items =
        total <= paging.offset()
            ? List.of()
            : notifications.page(userId, unread, paging.offset(), paging.pageSize()).stream()
                .map(NotificationViews::view)
                .toList();
    return new AlertViews.NotificationPage(paging.meta(total), items);
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(@PathVariable("id") String id, Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    UUID notificationId = JsonFields.parseUuid(id).orElseThrow(WatchlistService::notFound);
    if (!notifications.markRead(userId, notificationId, clock.instant())) {
      throw WatchlistService.notFound();
    }
  }

  @PostMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllRead(Authentication authentication) {
    notifications.markAllRead(CurrentUser.require(authentication), clock.instant());
  }

  @GetMapping("/unread-count")
  public AlertViews.UnreadCount unreadCount(Authentication authentication) {
    return new AlertViews.UnreadCount(
        notifications.count(CurrentUser.require(authentication), true));
  }
}
