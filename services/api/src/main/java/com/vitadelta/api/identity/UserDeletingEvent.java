package com.vitadelta.api.identity;

import java.util.UUID;

/**
 * Published synchronously inside the transaction that deletes a user (account deletion and the
 * purge of unverified accounts), right before the {@code app_user} row is deleted. Listeners run in
 * the same transaction; a listener that throws rolls the deletion back. The alerts module uses it
 * to delete unprocessed outbox events that reference the user's notifications (section 5.9).
 *
 * @param userId the user being deleted
 */
public record UserDeletingEvent(UUID userId) {}
