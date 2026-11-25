import { DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { ImportJob, ImportJobDetail } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';

/** Delay between two `GET /admin/imports/{jobId}` calls while a job is running. */
export const IMPORT_POLL_INTERVAL_MS = new InjectionToken<number>('IMPORT_POLL_INTERVAL_MS', {
  providedIn: 'root',
  factory: () => 2000,
});

/** Client-side size hint for uploads (the API enforces its own limit). */
export const IMPORT_SIZE_HINT_BYTES = 20 * 1024 * 1024;

export type ImportKind = ImportJob['kind'];

/** A job started in this browser session, with its detail once known. */
export interface TrackedJob {
  readonly job: ImportJob | ImportJobDetail;
  readonly fileName: string;
  /** Last polling failure; polling stops until {@link ImportJobsStore.poll} is called again. */
  readonly problem: ApiProblem | null;
}

/**
 * Import jobs started by this admin in this session (the contract has no job list). An upload
 * returns the job; the store then polls `GET /admin/imports/{jobId}` until the status is
 * `SUCCEEDED` or `FAILED` and keeps the row errors of the final detail.
 *
 * Jobs, pending polls and answers belong to the session generation they were started in: after
 * a sign-out or an account switch the history reads empty at once, every poll timer is cleared
 * and late answers are dropped.
 */
@Injectable({ providedIn: 'root' })
export class ImportJobsStore {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly interval = inject(IMPORT_POLL_INTERVAL_MS);
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly stateSignal = signal<{
    readonly generation: number;
    readonly jobs: readonly TrackedJob[];
  }>({ generation: this.session.generation(), jobs: [] });

  /** Newest first; empty for a session generation other than the one the jobs belong to. */
  readonly jobs = computed(() => {
    const state = this.stateSignal();
    return state.generation === this.session.generation() ? state.jobs : [];
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearTimers());
  }

  /** Uploads `file` as `kind`, tracks the returned job and starts polling it. */
  async upload(kind: ImportKind, file: File): Promise<ImportJob> {
    this.current();
    const generation = this.session.generation();
    const job = await firstValueFrom(
      kind === 'PRODUCTS_JSON'
        ? this.api.importProducts(file)
        : this.api.importOffers(file, file.name),
    );
    if (!this.current() || generation !== this.stateSignal().generation) {
      return job;
    }
    this.stateSignal.update((state) => ({
      generation: state.generation,
      jobs: [
        { job, fileName: file.name, problem: null },
        ...state.jobs.filter((entry) => entry.job.id !== job.id),
      ],
    }));
    this.poll(job.id);
    return job;
  }

  /** Fetches the job detail after the poll interval, again and again while it is running. */
  poll(jobId: string): void {
    if (!this.current()) {
      return;
    }
    const pending = this.timers.get(jobId);
    if (pending !== undefined) {
      clearTimeout(pending);
    }
    this.patch(jobId, { problem: null });
    const generation = this.stateSignal().generation;
    const timer = setTimeout(() => void this.fetch(jobId, generation), this.interval);
    this.timers.set(jobId, timer);
  }

  private async fetch(jobId: string, generation: number): Promise<void> {
    this.timers.delete(jobId);
    if (!this.current() || generation !== this.stateSignal().generation) {
      return;
    }
    try {
      const detail = await firstValueFrom(this.api.getImportJob(jobId));
      if (!this.current() || generation !== this.stateSignal().generation) {
        return;
      }
      this.patch(jobId, { job: detail, problem: null });
      if (detail.status === 'RUNNING') {
        this.poll(jobId);
      }
    } catch (error: unknown) {
      if (this.current() && generation === this.stateSignal().generation) {
        this.patch(jobId, { problem: ApiProblem.from(error) });
      }
    }
  }

  /**
   * Whether the jobs belong to the current session generation; otherwise forgets them and
   * clears every poll timer.
   */
  private current(): boolean {
    const generation = this.session.generation();
    if (this.stateSignal().generation === generation) {
      return true;
    }
    this.clearTimers();
    this.stateSignal.set({ generation, jobs: [] });
    return false;
  }

  private clearTimers(): void {
    for (const timer of this.timers.values()) {
      clearTimeout(timer);
    }
    this.timers.clear();
  }

  private patch(jobId: string, change: Partial<TrackedJob>): void {
    this.stateSignal.update((state) => ({
      generation: state.generation,
      jobs: state.jobs.map((entry) => (entry.job.id === jobId ? { ...entry, ...change } : entry)),
    }));
  }
}

/** Whether a tracked job carries its final detail with row errors. */
export function hasDetail(job: ImportJob | ImportJobDetail): job is ImportJobDetail {
  return 'errors' in job && Array.isArray(job.errors);
}
