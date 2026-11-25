import {
  EnvironmentInjector,
  createEnvironmentInjector,
  runInInjectionContext,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';

import { fixtureTokenResponse } from '../../testing/api-fixtures';
import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { AuthTokenResponse } from '../api/api-types';
import {
  LockManagerLike,
  REFRESH_HANDSHAKE_TIMEOUT_MS,
  REFRESH_LOCK_NAME,
  RefreshCoordinator,
  SESSION_CHANNEL,
  SessionChannelLike,
  SessionMessage,
  WEB_LOCKS,
} from './refresh-coordinator';

/**
 * In-memory stand-in for BroadcastChannel: delivers to every other channel on the bus, at once
 * or, for a deferred bus, only when the test calls {@link FakeBus.deliverAll}.
 */
class FakeBus {
  readonly channels: FakeChannel[] = [];
  private readonly queue: { readonly to: FakeChannel; readonly data: unknown }[] = [];

  constructor(readonly deferred = false) {}

  send(from: FakeChannel, message: unknown): void {
    for (const channel of this.channels) {
      if (channel !== from) {
        if (this.deferred) {
          this.queue.push({ to: channel, data: structuredClone(message) });
        } else {
          channel.deliver(structuredClone(message));
        }
      }
    }
  }

  deliverAll(): void {
    for (const entry of this.queue.splice(0)) {
      entry.to.deliver(entry.data);
    }
  }
}

class FakeChannel implements SessionChannelLike {
  readonly sent: unknown[] = [];
  private readonly listeners: ((event: MessageEvent<unknown>) => void)[] = [];
  closed = false;

  constructor(private readonly bus: FakeBus) {
    bus.channels.push(this);
  }

  postMessage(message: unknown): void {
    this.sent.push(message);
    this.bus.send(this, message);
  }

  addEventListener(_type: 'message', listener: (event: MessageEvent<unknown>) => void): void {
    this.listeners.push(listener);
  }

  close(): void {
    this.closed = true;
  }

  deliver(data: unknown): void {
    for (const listener of this.listeners) {
      listener(new MessageEvent('message', { data }));
    }
  }
}

/**
 * Exclusive lock manager shared by all "tabs": callbacks run one after another. Accepts both
 * call shapes of the Web Locks API and grants an `ifAvailable` request a `null` lock while
 * the lock is held or requested.
 */
class FakeLocks implements LockManagerLike {
  readonly names: string[] = [];
  private tail: Promise<unknown> = Promise.resolve();
  private pending = 0;

  request<T>(name: string, ...args: unknown[]): Promise<T> {
    const [first, second] = args;
    const options = (typeof first === 'function' ? {} : first) as { ifAvailable?: boolean };
    const callback = (typeof first === 'function' ? first : second) as (
      lock: unknown,
    ) => Promise<T>;
    this.names.push(name);
    if (options.ifAvailable && this.pending > 0) {
      return Promise.resolve().then(() => callback(null));
    }
    this.pending += 1;
    const run = this.tail.then(async () => {
      try {
        return await callback({ name });
      } finally {
        this.pending -= 1;
      }
    });
    this.tail = run.catch(() => undefined);
    return run;
  }
}

interface Tab {
  readonly coordinator: RefreshCoordinator;
  readonly channel: FakeChannel;
  readonly calls: Subject<AuthTokenResponse | null>[];
}

function createTab(bus: FakeBus, locks: LockManagerLike | null, handshakeTimeout = 2000): Tab {
  const channel = new FakeChannel(bus);
  const calls: Subject<AuthTokenResponse | null>[] = [];
  const api = {
    refreshSession: () => {
      const subject = new Subject<AuthTokenResponse | null>();
      calls.push(subject);
      return subject.asObservable();
    },
  };
  const injector = createEnvironmentInjector(
    [
      { provide: ApiClient, useValue: api },
      { provide: WEB_LOCKS, useValue: locks },
      { provide: REFRESH_HANDSHAKE_TIMEOUT_MS, useValue: handshakeTimeout },
      { provide: SESSION_CHANNEL, useValue: channel },
    ],
    TestBed.inject(EnvironmentInjector),
  );
  const coordinator = runInInjectionContext(injector, () => new RefreshCoordinator());
  return { coordinator, channel, calls };
}

function answer(
  subject: Subject<AuthTokenResponse | null> | undefined,
  response: AuthTokenResponse | null,
): void {
  if (!subject) {
    throw new Error('No refresh call was made');
  }
  subject.next(response);
  subject.complete();
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('RefreshCoordinator', () => {
  it('shares one in-flight refresh between concurrent callers of a tab', async () => {
    const tab = createTab(new FakeBus(), new FakeLocks());
    const first = tab.coordinator.refresh();
    const second = tab.coordinator.refresh();
    expect(second).toBe(first);
    await flush();
    expect(tab.calls).toHaveLength(1);

    answer(tab.calls[0], fixtureTokenResponse('t1'));
    await expect(first).resolves.toEqual(fixtureTokenResponse('t1'));
    await expect(second).resolves.toEqual(fixtureTokenResponse('t1'));

    // A later call starts a new refresh.
    const third = tab.coordinator.refresh();
    await flush();
    expect(tab.calls).toHaveLength(2);
    answer(tab.calls[1], fixtureTokenResponse('t2'));
    await expect(third).resolves.toEqual(fixtureTokenResponse('t2'));
  });

  it('runs the API call inside the vd-refresh Web Lock and announces the new session', async () => {
    const locks = new FakeLocks();
    const tab = createTab(new FakeBus(), locks);
    const result = tab.coordinator.refresh();
    await flush();
    answer(tab.calls[0], fixtureTokenResponse('t1'));
    await result;
    expect(locks.names).toEqual([REFRESH_LOCK_NAME]);
    expect(tab.channel.sent).toEqual([
      { type: 'session', response: fixtureTokenResponse('t1'), at: expect.any(Number) },
      {
        type: 'refresh-done',
        completedAt: expect.any(Number),
        outcome: 'ok',
        response: fixtureTokenResponse('t1'),
      },
    ]);
  });

  it('across tabs, a tab waiting for the lock reuses the session another tab obtained', async () => {
    const bus = new FakeBus();
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);
    const remoteSeenByB: SessionMessage[] = [];
    tabB.coordinator.remoteChanges$.subscribe((message) => remoteSeenByB.push(message));

    const resultA = tabA.coordinator.refresh();
    const resultB = tabB.coordinator.refresh();
    await flush();
    expect(tabA.calls).toHaveLength(1);
    expect(tabB.calls).toHaveLength(0);

    answer(tabA.calls[0], fixtureTokenResponse('shared'));
    await expect(resultA).resolves.toEqual(fixtureTokenResponse('shared'));
    await expect(resultB).resolves.toEqual(fixtureTokenResponse('shared'));
    expect(tabB.calls).toHaveLength(0);
    expect(remoteSeenByB).toEqual([
      { type: 'session', response: fixtureTokenResponse('shared'), at: expect.any(Number) },
    ]);
  });

  it('refreshes itself when the remote session predates its own request', async () => {
    const bus = new FakeBus();
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);

    const resultA = tabA.coordinator.refresh();
    await flush();
    answer(tabA.calls[0], fixtureTokenResponse('old'));
    await resultA;

    // B starts after A's announcement: the old session must not be reused.
    const resultB = tabB.coordinator.refresh();
    await flush();
    expect(tabB.calls).toHaveLength(1);
    answer(tabB.calls[0], fixtureTokenResponse('new'));
    await expect(resultB).resolves.toEqual(fixtureTokenResponse('new'));
  });

  it('calls the API directly when Web Locks are not available', async () => {
    const tab = createTab(new FakeBus(), null);
    const result = tab.coordinator.refresh();
    expect(tab.calls).toHaveLength(1);
    answer(tab.calls[0], fixtureTokenResponse('t'));
    await expect(result).resolves.toEqual(fixtureTokenResponse('t'));
  });

  it('falls back to a direct call when the lock request fails', async () => {
    const failing: LockManagerLike = {
      request: () => Promise.reject(new Error('locks disabled')),
    };
    const tab = createTab(new FakeBus(), failing);
    const result = tab.coordinator.refresh();
    await flush();
    expect(tab.calls).toHaveLength(1);
    answer(tab.calls[0], fixtureTokenResponse('t'));
    await expect(result).resolves.toEqual(fixtureTokenResponse('t'));
  });

  it('resolves null when the refresh is rejected', async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ApiClient,
          useValue: {
            refreshSession: () =>
              throwError(
                () => new ApiProblem({ kind: 'problem', status: 401, code: 'unauthorized' }),
              ),
          },
        },
        { provide: WEB_LOCKS, useValue: null },
        { provide: SESSION_CHANNEL, useValue: null },
      ],
    });
    await expect(TestBed.inject(RefreshCoordinator).refresh()).resolves.toBeNull();
  });

  it('treats 204 (no refresh cookie) as no session without announcing or retrying', async () => {
    const locks = new FakeLocks();
    const tab = createTab(new FakeBus(), locks);
    const remote: SessionMessage[] = [];
    tab.coordinator.remoteChanges$.subscribe((message) => remote.push(message));
    const result = tab.coordinator.refresh();
    await flush();
    answer(tab.calls[0], null);
    await expect(result).resolves.toBeNull();
    await flush();
    expect(tab.calls).toHaveLength(1);
    // Only the lock hand-off report: no session or sign-out message reaches other tabs.
    expect(tab.channel.sent).toEqual([
      { type: 'refresh-done', completedAt: expect.any(Number), outcome: 'none', response: null },
    ]);
    expect(remote).toEqual([]);
  });

  it('a waiting tab takes over a 204 outcome without calling the API', async () => {
    const bus = new FakeBus(true);
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);
    const seenByB: SessionMessage[] = [];
    tabB.coordinator.remoteChanges$.subscribe((message) => seenByB.push(message));

    const resultA = tabA.coordinator.refresh();
    await flush();
    const resultB = tabB.coordinator.refresh();
    await flush();
    answer(tabA.calls[0], null);
    await expect(resultA).resolves.toBeNull();
    await flush();
    bus.deliverAll();
    await flush();
    expect(tabB.calls).toHaveLength(0);
    await expect(resultB).resolves.toBeNull();
    expect(seenByB).toEqual([]);
  });

  it('resolves null without a retry when Web Locks are missing and the API answers 204', async () => {
    const tab = createTab(new FakeBus(), null);
    const result = tab.coordinator.refresh();
    answer(tab.calls[0], null);
    await expect(result).resolves.toBeNull();
    expect(tab.calls).toHaveLength(1);
    expect(tab.channel.sent).toEqual([]);
  });

  it('forwards sign-outs from other tabs and forgets their session', async () => {
    const bus = new FakeBus();
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);
    const seen: SessionMessage[] = [];
    tabB.coordinator.remoteChanges$.subscribe((message) => seen.push(message));

    tabA.coordinator.announceSession(fixtureTokenResponse('x'));
    tabA.coordinator.announceSignedOut();
    expect(seen.map((message) => message.type)).toEqual(['session', 'signed-out']);

    // B must call the API: the remembered session was cleared by the sign-out.
    const result = tabB.coordinator.refresh();
    await flush();
    expect(tabB.calls).toHaveLength(1);
    answer(tabB.calls[0], fixtureTokenResponse('y'));
    await expect(result).resolves.toEqual(fixtureTokenResponse('y'));
  });

  it('a tab that waited for the lock learns the holder outcome even when the broadcast is late', async () => {
    const bus = new FakeBus(true);
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);

    const resultA = tabA.coordinator.refresh();
    await flush();
    const resultB = tabB.coordinator.refresh();
    await flush();
    expect(tabA.calls).toHaveLength(1);

    answer(tabA.calls[0], fixtureTokenResponse('shared'));
    await expect(resultA).resolves.toEqual(fixtureTokenResponse('shared'));
    // B now holds the lock, but A's messages have not been delivered yet.
    await flush();
    bus.deliverAll();
    await flush();
    expect(tabB.calls).toHaveLength(0);
    await expect(resultB).resolves.toEqual(fixtureTokenResponse('shared'));
  });

  it('a waiting tab does not refresh again after the holder was refused', async () => {
    const bus = new FakeBus(true);
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);

    const resultA = tabA.coordinator.refresh();
    await flush();
    const resultB = tabB.coordinator.refresh();
    await flush();
    tabA.calls[0].error(new ApiProblem({ kind: 'problem', status: 401, code: 'unauthorized' }));
    await expect(resultA).resolves.toBeNull();
    await flush();
    bus.deliverAll();
    await flush();
    expect(tabB.calls).toHaveLength(0);
    await expect(resultB).resolves.toBeNull();
  });

  it('does not announce a refresh that completes after another tab signed out', async () => {
    const bus = new FakeBus();
    const locks = new FakeLocks();
    const tabA = createTab(bus, locks);
    const tabB = createTab(bus, locks);
    const seenByB: SessionMessage[] = [];
    tabB.coordinator.remoteChanges$.subscribe((message) => seenByB.push(message));

    const resultA = tabA.coordinator.refresh();
    await flush();
    tabB.coordinator.announceSignedOut();
    answer(tabA.calls[0], fixtureTokenResponse('late'));
    await resultA;
    expect(tabA.channel.sent.filter((m) => (m as SessionMessage).type === 'session')).toEqual([]);
    expect(seenByB).toEqual([]);
  });

  it('ignores a session announced by a refresh that started before a sign-out', () => {
    const tab = createTab(new FakeBus(), null);
    const seen: SessionMessage[] = [];
    tab.coordinator.remoteChanges$.subscribe((message) => seen.push(message));
    tab.channel.deliver({ type: 'signed-out', at: 2000 });
    tab.channel.deliver({ type: 'session', response: fixtureTokenResponse('old'), at: 1000 });
    expect(seen.map((message) => message.type)).toEqual(['signed-out']);
  });

  it('refreshes itself when the lock holder never reports (a closed tab)', async () => {
    const bus = new FakeBus();
    const locks = new FakeLocks();
    const tab = createTab(bus, locks, 10);
    let release: () => void = () => undefined;
    // A tab that takes the lock and is closed before it can report.
    void locks.request(
      REFRESH_LOCK_NAME,
      {},
      () => new Promise<void>((resolve) => (release = resolve)),
    );
    const result = tab.coordinator.refresh();
    await flush();
    release();
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(tab.calls).toHaveLength(1);
    answer(tab.calls[0], fixtureTokenResponse('own'));
    await expect(result).resolves.toEqual(fixtureTokenResponse('own'));
  });

  it('ignores malformed channel messages', () => {
    const bus = new FakeBus();
    const tab = createTab(bus, null);
    const seen: SessionMessage[] = [];
    tab.coordinator.remoteChanges$.subscribe((message) => seen.push(message));
    tab.channel.deliver('hello');
    tab.channel.deliver({ type: 'session', response: { accessToken: 5 } });
    tab.channel.deliver({ type: 'other' });
    tab.channel.deliver({ type: 'signed-out' });
    tab.channel.deliver({ type: 'session', response: fixtureTokenResponse('x') });
    tab.channel.deliver({ type: 'refresh-done', completedAt: 1, outcome: 'ok', response: null });
    tab.channel.deliver(null);
    expect(seen).toEqual([]);
  });
});
