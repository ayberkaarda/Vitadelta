import { DestroyRef, Injectable, InjectionToken, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { ImportJob, ImportJobDetail } from '../../core/api/api-types';

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
 */
@Injectable({ providedIn: 'root' })
export class ImportJobsStore {
  private readonly api = inject(ApiClient);
  private readonly interval = inject(IMPORT_POLL_INTERVAL_MS);
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly jobsSignal = signal<readonly TrackedJob[]>([]);

  /** Newest first. */
  readonly jobs = this.jobsSignal.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      for (const timer of this.timers.values()) {
        clearTimeout(timer);
      }
      this.timers.clear();
    });
  }

  /** Uploads `file` as `kind`, tracks the returned job and starts polling it. */
  async upload(kind: ImportKind, file: File): Promise<ImportJob> {
    const job = await firstValueFrom(
      kind === 'PRODUCTS_JSON'
        ? this.api.importProducts(file)
        : this.api.importOffers(file, file.name),
    );
    this.jobsSignal.update((jobs) => [
      { job, fileName: file.name, problem: null },
      ...jobs.filter((entry) => entry.job.id !== job.id),
    ]);
    this.poll(job.id);
    return job;
  }

  /** Fetches the job detail after the poll interval, again and again while it is running. */
  poll(jobId: string): void {
    const pending = this.timers.get(jobId);
    if (pending !== undefined) {
      clearTimeout(pending);
    }
    this.patch(jobId, { problem: null });
    const timer = setTimeout(() => void this.fetch(jobId), this.interval);
    this.timers.set(jobId, timer);
  }

  private async fetch(jobId: string): Promise<void> {
    this.timers.delete(jobId);
    try {
      const detail = await firstValueFrom(this.api.getImportJob(jobId));
      this.patch(jobId, { job: detail, problem: null });
      if (detail.status === 'RUNNING') {
        this.poll(jobId);
      }
    } catch (error: unknown) {
      this.patch(jobId, { problem: ApiProblem.from(error) });
    }
  }

  private patch(jobId: string, change: Partial<TrackedJob>): void {
    this.jobsSignal.update((jobs) =>
      jobs.map((entry) => (entry.job.id === jobId ? { ...entry, ...change } : entry)),
    );
  }
}

/** Whether a tracked job carries its final detail with row errors. */
export function hasDetail(job: ImportJob | ImportJobDetail): job is ImportJobDetail {
  return 'errors' in job && Array.isArray(job.errors);
}
