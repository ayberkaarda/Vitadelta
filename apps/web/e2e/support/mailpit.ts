import { expect } from '@playwright/test';

import { e2eEnv } from './env';

/** One message as listed by the Mailpit API. */
export interface MailSummary {
  readonly ID: string;
  readonly Subject: string;
  readonly Created: string;
}

interface SearchResponse {
  readonly messages: readonly MailSummary[] | null;
}

interface MessageResponse {
  readonly Text: string;
  readonly HTML: string;
  readonly Subject: string;
}

async function mailpit<T>(path: string): Promise<T> {
  const response = await fetch(`${e2eEnv().mailpitURL}/api/v1${path}`);
  if (!response.ok) {
    throw new Error(`Mailpit ${path} answered ${response.status}`);
  }
  return (await response.json()) as T;
}

/** Messages sent to `address`, newest first. */
export async function messagesTo(address: string): Promise<readonly MailSummary[]> {
  const query = encodeURIComponent(`to:"${address}"`);
  const result = await mailpit<SearchResponse>(`/search?query=${query}&limit=50`);
  return result.messages ?? [];
}

/** Plain-text body of a message. */
export async function messageText(id: string): Promise<string> {
  return (await mailpit<MessageResponse>(`/message/${encodeURIComponent(id)}`)).Text;
}

/**
 * Waits until a message to `address` whose subject matches `subject` arrives (the outbox sends
 * asynchronously) and returns its plain-text body.
 */
export async function waitForMail(
  address: string,
  subject: RegExp,
  timeout = 60_000,
): Promise<string> {
  let found: MailSummary | undefined;
  await expect
    .poll(
      async () => {
        found = (await messagesTo(address)).find((message) => subject.test(message.Subject));
        return found !== undefined;
      },
      { timeout, intervals: [500, 1_000, 2_000], message: `mail "${subject}" to ${address}` },
    )
    .toBe(true);
  return messageText((found as MailSummary).ID);
}

/** The first link of `text` that points at `path` (for example `/auth/verify`). */
export function linkIn(text: string, path: string): string {
  const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`https?://[^\\s"<>]+${escaped}[^\\s"<>]*`).exec(text);
  if (!match) {
    throw new Error(`No ${path} link in the e-mail:\n${text}`);
  }
  return match[0];
}
