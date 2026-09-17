import { Component, OnDestroy } from '@angular/core';
import { Subject, finalize, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { filenameFromContentDisposition, saveBlob } from '../../../core/utils/file-download.util';
import { ReportService } from '../services/report.service';

/** Usado quando o `Content-Disposition` não traz um nome aproveitável. */
export const REPORT_FALLBACK_FILENAME = 'relatorio-executivo.pdf';

/**
 * Página do dashboard: o cabeçalho, o botão do relatório executivo e o arranjo das
 * regiões.
 *
 * Cada região carrega os próprios dados e trata o próprio erro. Não há um `forkJoin` de
 * todas as chamadas nem um estado único de tela: uma tendência que falha não pode apagar
 * os cards, e cada painel tem seu próprio "tentar novamente". O relatório segue a mesma
 * regra: ele falha sozinho, dentro do cabeçalho.
 */
@Component({
  selector: 'app-dashboard-page',
  templateUrl: './dashboard-page.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class DashboardPageComponent implements OnDestroy {
  /** O endpoint é de ADMIN e ANALYST; para os demais o botão nem aparece. */
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
          // Com `responseType: 'blob'` o corpo de erro também é um Blob, que nem o
          // `ErrorInterceptor` nem `toApiError` conseguem ler; a mensagem fixa é o que
          // resta, e aqui não há nada acionável além de tentar de novo.
          this.exportError = toApiError(error)?.message ?? 'Não foi possível gerar o relatório.';
        },
      });
  }
}
