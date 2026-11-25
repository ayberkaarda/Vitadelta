import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../core/api/api-problem';
import { ImportJob, ImportJobDetail } from '../../core/api/api-types';
import { Formatter } from '../../core/format/formatter';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { ImportErrorView, describeImportError } from './import-errors';
import {
  IMPORT_SIZE_HINT_BYTES,
  ImportJobsStore,
  ImportKind,
  TrackedJob,
  hasDetail,
} from './import-jobs.store';

/** One "count · label" part of a job summary. */
interface SummaryPart {
  readonly key: string;
  readonly count: number;
}

const SUMMARY_ORDER = [
  'products',
  'rows',
  'inserted',
  'updated',
  'unchanged',
  'deleted',
  'changedPairs',
];

interface UploadSlot {
  readonly kind: ImportKind;
  readonly accept: string;
  readonly inputId: string;
}

/**
 * `/admin/imports` (project specification §8.9, §9.1; design hand-off §4.10): upload a products
 * JSON document or an offers CSV, follow the job until it succeeds or fails, and read the row
 * errors: row number, code, field and a translated text per allow-listed code. The server's
 * message prose is never rendered.
 */
@Component({
  selector: 'vd-admin-imports-page',
  imports: [
    EmptyStateComponent,
    IconComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    TranslocoPipe,
  ],
  templateUrl: './admin-imports-page.component.html',
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminImportsPageComponent {
  private readonly store = inject(ImportJobsStore);
  protected readonly format = inject(Formatter);

  protected readonly slots: readonly UploadSlot[] = [
    { kind: 'PRODUCTS_JSON', accept: '.json,application/json', inputId: 'import-products' },
    { kind: 'OFFERS_CSV', accept: '.csv,text/csv', inputId: 'import-offers' },
  ];
  protected readonly jobs = this.store.jobs;
  protected readonly uploading = signal<ImportKind | null>(null);
  protected readonly chosen = signal<Readonly<Partial<Record<ImportKind, File>>>>({});
  protected readonly uploadProblems = signal<Readonly<Partial<Record<ImportKind, ApiProblem>>>>({});
  protected readonly dragOver = signal<ImportKind | null>(null);
  /** Job whose details are open: an id, '' for none, `null` for the newest failed job. */
  protected readonly openJobId = signal<string | null>(null);

  protected readonly openJob = computed<TrackedJob | null>(() => {
    const id = this.openJobId();
    if (id === '') {
      return null;
    }
    if (id !== null) {
      return this.jobs().find((entry) => entry.job.id === id) ?? null;
    }
    return this.jobs().find((entry) => entry.job.status === 'FAILED') ?? null;
  });

  protected tooLarge(file: File | undefined): boolean {
    return file !== undefined && file.size > IMPORT_SIZE_HINT_BYTES;
  }

  /** i18n key and parameter of a file size ("184 KB", "3.2 MB"). */
  protected size(bytes: number): { readonly key: string; readonly size: string } {
    if (bytes >= 1024 * 1024) {
      return { key: 'admin.imports.size_mb', size: this.format.amount(bytes / (1024 * 1024), 1) };
    }
    return {
      key: 'admin.imports.size_kb',
      size: this.format.amount(Math.max(1, Math.round(bytes / 1024)), 0),
    };
  }

  protected onFileInput(kind: ImportKind, input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      void this.start(kind, file);
    }
  }

  protected onDragOver(kind: ImportKind, event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(kind);
  }

  protected onDrop(kind: ImportKind, event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(null);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      void this.start(kind, file);
    }
  }

  protected async start(kind: ImportKind, file: File): Promise<void> {
    if (this.uploading() !== null) {
      return;
    }
    this.chosen.update((files) => ({ ...files, [kind]: file }));
    this.uploadProblems.update((problems) => ({ ...problems, [kind]: undefined }));
    this.uploading.set(kind);
    try {
      await this.store.upload(kind, file);
      this.openJobId.set(null);
    } catch (error: unknown) {
      this.uploadProblems.update((problems) => ({ ...problems, [kind]: ApiProblem.from(error) }));
    } finally {
      this.uploading.set(null);
    }
  }

  protected toggleDetails(jobId: string): void {
    this.openJobId.set(this.openJob()?.job.id === jobId ? '' : jobId);
  }

  protected retryPoll(jobId: string): void {
    this.store.poll(jobId);
  }

  protected summary(job: ImportJob): readonly SummaryPart[] {
    const summary = job.summary;
    if (!summary) {
      return [];
    }
    const read = summary['products'] !== undefined ? 'products' : 'rows';
    if (job.status === 'FAILED') {
      return summary[read] === undefined ? [] : [{ key: read, count: summary[read] }];
    }
    return SUMMARY_ORDER.filter((key) => typeof summary[key] === 'number').map((key) => ({
      key,
      count: summary[key],
    }));
  }

  /** Row errors as allow-listed table cells (translated text, never the server's message). */
  protected errorsOf(job: ImportJob | ImportJobDetail): readonly ImportErrorView[] {
    return hasDetail(job) ? job.errors.map((error) => describeImportError(error, job.kind)) : [];
  }
}
