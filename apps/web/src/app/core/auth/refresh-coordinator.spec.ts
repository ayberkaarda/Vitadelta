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
  REFRESH_LOCK_NAME,
  RefreshCoordinator,
  SESSION_CHANNEL,
  SessionChannelLike,
  SessionMessage,
  WEB_LOCKS,
} from './refresh-coordinator';

/** In-memory stand-in for BroadcastChannel: delivers to every other channel on the bus. */
class FakeBus {
  readonly channels: FakeChannel[] = [];
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
    for (const channel of this.bus.channels) {
      if (channel !== this) {
        channel.deliver(structuredClone(message));
      }
    }
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

/** Exclusive lock manager shared by all "tabs": callbacks run one after another. */
class FakeLocks implements LockManagerLike {
  readonly names: string[] = [];
  private tail: Promise<unknown> = Promise.resolve();

  request<T>(name: string, callback: () => Promise<T>): Promise<T> {
    this.names.push(name);
    const run = this.tail.then(() => callback());
    this.tail = run.catch(() => undefined);
    return run;
  }
}

interface Tab {
  readonly coordinator: RefreshCoordinator;
  readonly channel: FakeChannel;
  readonly calls: Subject<AuthTokenResponse>[];
}

function createTab(bus: FakeBus, locks: LockManagerLike | null): Tab {
  const channel = new FakeChannel(bus);
  const calls: Subject<AuthTokenResponse>[] = [];
  const api = {
    refreshSession: () => {
      const subject = new Subject<AuthTokenResponse>();
      calls.push(subject);
      return subject.asObservable();
    },
  };
  const injector = createEnvironmentInjector(
    [
      { provide: ApiClient, useValue: api },
      { provide: WEB_LOCKS, useValue: locks },
      { provide: SESSION_CHANNEL, useValue: channel },
    ],
    TestBed.inject(EnvironmentInjector),
  );
  const coordinator = runInInjectionContext(injector, () => new RefreshCoordinator());
  return { coordinator, channel, calls };
}

function answer(
  subject: Subject<AuthTokenResponse> | undefined,
  response: AuthTokenResponse,
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
    expect(tab.channel.sent).toEqual([{ type: 'session', response: fixtureTokenResponse('t1') }]);
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
    expect(remoteSeenByB).toEqual([{ type: 'session', response: fixtureTokenResponse('shared') }]);
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

  it('ignores malformed channel messages', () => {
    const bus = new FakeBus();
    const tab = createTab(bus, null);
    const seen: SessionMessage[] = [];
    tab.coordinator.remoteChanges$.subscribe((message) => seen.push(message));
    tab.channel.deliver('hello');
    tab.channel.deliver({ type: 'session', response: { accessToken: 5 } });
    tab.channel.deliver({ type: 'other' });
    tab.channel.deliver(null);
    expect(seen).toEqual([]);
  });
});
