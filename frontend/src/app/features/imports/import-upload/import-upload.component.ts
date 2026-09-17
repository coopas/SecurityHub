import { HttpEventType } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, finalize, takeUntil } from 'rxjs';

import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { ProjectService } from '../../projects/services/project.service';
import {
  MAX_SCAN_FILE_BYTES,
  OPTIONS_PAGE_SIZE,
  ProjectOption,
  SCAN_FORMATS,
  SCAN_FORMAT_ACCEPT,
  SCAN_FORMAT_HINTS,
  SCAN_FORMAT_LABELS,
  ScanFormat,
} from '../models/scan-import.model';
import { ImportService } from '../services/import.service';

/**
 * Upload of the scan report. The screen ends by navigating to the preview: the upload
 * creates nothing beyond an import awaiting review, and reviewing is the next step.
 *
 * Project and format are chosen before the file because both change what the server does
 * with it — the format decides the parser, and the project decides among which assets
 * the findings will be looked for.
 */
@Component({
  selector: 'app-import-upload',
  templateUrl: './import-upload.component.html',
  styleUrls: ['../imports.scss'],
})
export class ImportUploadComponent implements OnInit, OnDestroy {
  readonly formats = SCAN_FORMATS;
  readonly formatLabels = SCAN_FORMAT_LABELS;
  readonly formatHints = SCAN_FORMAT_HINTS;
  readonly maxFileBytes = MAX_SCAN_FILE_BYTES;

  readonly form: FormGroup = this.formBuilder.group({
    projectId: [null as number | null, [Validators.required]],
    format: ['NMAP_XML' as ScanFormat, [Validators.required]],
  });

  projects: ProjectOption[] = [];
  /** `null` when the form can be displayed. */
  loadState: ViewState | null = 'loading';
  loadErrorMessage: string | null = null;

  file: File | null = null;
  uploading = false;
  progress = 0;
  errorMessage: string | null = null;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly importService: ImportService,
    private readonly projectService: ProjectService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
  ) {}

  /** No project, no import: the backend requires `projectId` and validates it in the tenant. */
  get hasNoProjects(): boolean {
    return this.loadState === null && this.projects.length === 0;
  }

  get selectedFormat(): ScanFormat {
    return this.form.value.format as ScanFormat;
  }

  /** A hint to the system picker; the server is the one who decides if the file is any good. */
  get accept(): string {
    return SCAN_FORMAT_ACCEPT[this.selectedFormat];
  }

  get formatHint(): string {
    return this.formatHints[this.selectedFormat];
  }

  get canSubmit(): boolean {
    return this.form.valid && this.file !== null && !this.uploading;
  }

  ngOnInit(): void {
    this.loadProjects();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadProjects(): void {
    this.loadState = 'loading';
    this.loadErrorMessage = null;
    this.projectService
      .list({ page: 0, size: OPTIONS_PAGE_SIZE, sort: 'name,asc' })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (page) => {
          this.projects = page.content.map((project) => ({ id: project.id, name: project.name }));
          this.loadState = null;
        },
        error: (error: unknown) => {
          this.projects = [];
          this.loadState = 'error';
          this.loadErrorMessage =
            toApiError(error)?.message ?? 'Não foi possível carregar os projetos.';
        },
      });
  }

  /**
   * The input's value is cleared after reading the file: without that, picking the same
   * file again (after an error, for instance) would fire no event at all.
   */
  onFileSelected(input: HTMLInputElement): void {
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) {
      return;
    }

    this.errorMessage = null;
    // Immediate size check only to spare an upload already known to be refused. The
    // limit that counts is the server's, which reapplies it over what actually arrived
    // and answers `PAYLOAD_TOO_LARGE`; this line protects nothing, it only saves the wait.
    if (file.size > MAX_SCAN_FILE_BYTES) {
      this.file = null;
      this.errorMessage = `O arquivo tem ${this.formatSize(
        file.size,
      )} e o limite é ${this.formatSize(MAX_SCAN_FILE_BYTES)}. Envie um relatório menor.`;
      return;
    }

    this.file = file;
  }

  clearFile(): void {
    this.file = null;
    this.errorMessage = null;
  }

  fileSize(): string {
    return this.file ? this.formatSize(this.file.size) : '';
  }

  submit(): void {
    this.errorMessage = null;

    if (!this.canSubmit) {
      this.form.markAllAsTouched();
      if (!this.file) {
        this.errorMessage = 'Escolha o arquivo do relatório antes de enviar.';
      }
      return;
    }

    const file = this.file as File;
    this.uploading = true;
    this.progress = 0;
    this.importService
      .upload(Number(this.form.value.projectId), this.selectedFormat, file)
      .pipe(
        finalize(() => (this.uploading = false)),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) {
            // `total` only exists when the body is measurable; without it the bar does not move.
            this.progress = event.total ? Math.round((100 * event.loaded) / event.total) : 0;
            return;
          }
          if (event.type === HttpEventType.Response && event.body) {
            this.notifications.success('Relatório enviado. Revise os achados antes de confirmar.');
            void this.router.navigate(['/imports', event.body.id]);
          }
        },
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);

    if (unmatched.length > 0) {
      this.errorMessage = unmatched.join(' ');
      return;
    }

    switch (apiError?.code) {
      case 'PAYLOAD_TOO_LARGE':
        // A 413 can come from the proxy, with no envelope at all, so the message is our own.
        this.errorMessage =
          apiError.message ||
          'O relatório excede o tamanho aceito pelo servidor. Divida a varredura e envie em partes.';
        break;
      case 'BAD_REQUEST':
        // File unreadable for the chosen format, or too many findings in a single
        // report. The server's message says which of the two it is and what to do;
        // rewriting it here would swap an instruction for a guess.
        this.errorMessage = apiError.message;
        break;
      default:
        if ((apiError?.fieldErrors?.length ?? 0) > 0) {
          this.errorMessage = null;
          return;
        }
        this.errorMessage = apiError?.message ?? 'Não foi possível enviar o relatório.';
    }
  }

  private formatSize(bytes: number): string {
    const kilobytes = bytes / 1024;
    return kilobytes < 1024
      ? `${Math.max(1, Math.round(kilobytes))} KB`
      : `${(kilobytes / 1024).toFixed(1)} MB`;
  }
}
