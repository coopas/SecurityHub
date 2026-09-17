import { Component, OnDestroy } from '@angular/core';
import { Subject, finalize, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { filenameFromContentDisposition, saveBlob } from '../../../core/utils/file-download.util';
import { ReportService } from '../services/report.service';

/** Used when `Content-Disposition` does not bring a usable name. */
export const REPORT_FALLBACK_FILENAME = 'relatorio-executivo.pdf';

/**
 * The dashboard page: the header, the executive report button and the layout of the
 * regions.
 *
 * Each region loads its own data and handles its own error. There is no `forkJoin` over all
 * the calls and no single screen-wide state: a trend that fails cannot wipe out the cards,
 * and each panel has its own "tentar novamente". The report follows the same rule: it fails
 * on its own, inside the header.
 */
@Component({
  selector: 'app-dashboard-page',
  templateUrl: './dashboard-page.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class DashboardPageComponent implements OnDestroy {
  /** The endpoint is for ADMIN and ANALYST; for everyone else the button does not appear. */
  readonly canExportReport = this.authService.hasRole('ADMIN', 'ANALYST');

  exporting = false;
  exportError: string | null = null;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly reportService: ReportService,
    private readonly authService: AuthService,
  ) {}

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  exportReport(): void {
    if (this.exporting) {
      return;
    }

    this.exportError = null;
    this.exporting = true;
    this.reportService
      .executive()
      .pipe(
        finalize(() => (this.exporting = false)),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (response) => {
          if (!response.body) {
            this.exportError = 'O relatório voltou vazio. Tente novamente.';
            return;
          }
          saveBlob(
            response.body,
            filenameFromContentDisposition(
              response.headers.get('Content-Disposition'),
              REPORT_FALLBACK_FILENAME,
            ),
          );
        },
        error: (error: unknown) => {
          // With `responseType: 'blob'` the error body is also a Blob, which neither the
          // `ErrorInterceptor` nor `toApiError` can read; the fixed message is what is
          // left, and there is nothing actionable here beyond trying again.
          this.exportError = toApiError(error)?.message ?? 'Não foi possível gerar o relatório.';
        },
      });
  }
}
