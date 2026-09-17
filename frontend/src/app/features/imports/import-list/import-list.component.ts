import { Component, OnDestroy, OnInit } from '@angular/core';
import { PageEvent } from '@angular/material/paginator';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  SCAN_FORMAT_LABELS,
  SCAN_IMPORT_DEFAULT_SORT,
  SCAN_IMPORT_SORTABLE_PROPERTIES,
  SCAN_IMPORT_STATUS_ICONS,
  SCAN_IMPORT_STATUS_LABELS,
  ScanFormat,
  ScanImportQuery,
  ScanImportStatus,
  ScanImportSummary,
} from '../models/scan-import.model';
import { ImportService } from '../services/import.service';

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

/**
 * Histórico das importações de varredura.
 *
 * Como nas demais listagens paginadas pelo servidor, os query params da URL são a única
 * fonte de verdade da página, do tamanho e da ordenação; a assinatura da rota é que
 * dispara a busca, então recarregar, voltar ou compartilhar o link restaura exatamente a
 * mesma consulta.
 *
 * Não há filtros: `GET /scan-imports` aceita apenas paginação e ordenação, e um filtro
 * inventado viajaria na URL para ser ignorado pelo servidor.
 */
@Component({
  selector: 'app-import-list',
  templateUrl: './import-list.component.html',
  styleUrls: ['../imports.scss'],
})
export class ImportListComponent implements OnInit, OnDestroy {
  readonly displayedColumns = [
    'originalFilename',
    'projectName',
    'format',
    'status',
    'counters',
    'importedByName',
    'createdAt',
  ];
  readonly pageSizeOptions = [10, DEFAULT_PAGE_SIZE, 50, MAX_PAGE_SIZE];
  readonly formatLabels = SCAN_FORMAT_LABELS;
  readonly statusLabels = SCAN_IMPORT_STATUS_LABELS;
  readonly statusIcons = SCAN_IMPORT_STATUS_ICONS;

  query: ScanImportQuery = { page: 0, size: DEFAULT_PAGE_SIZE, sort: SCAN_IMPORT_DEFAULT_SORT };
  imports: ScanImportSummary[] = [];
  totalElements = 0;
  state: ViewState | null = 'loading';
  loadError: string | null = null;
  canImport = false;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly importService: ImportService,
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get sortActive(): string {
    return this.query.sort.split(',')[0];
  }

  get sortDirection(): 'asc' | 'desc' {
    return this.query.sort.endsWith(',asc') ? 'asc' : 'desc';
  }

  ngOnInit(): void {
    // Mesma licença de criar vulnerabilidade: confirmar uma importação cria várias.
    this.canImport = this.authService.hasRole('ADMIN', 'ANALYST');

    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      this.query = this.parseQuery(params);
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.loadError = null;
    this.importService
      .list(this.query)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (page) => {
          this.imports = page.content;
          this.totalElements = page.totalElements;
          this.state = page.content.length === 0 ? 'empty' : null;
        },
        error: (error: unknown) => {
          this.imports = [];
          this.totalElements = 0;
          this.loadError = toApiError(error)?.message ?? null;
          this.state = 'error';
        },
      });
  }

  onPage(event: PageEvent): void {
    this.patchQueryParams({
      page: event.pageIndex > 0 ? event.pageIndex : null,
      size: event.pageSize === DEFAULT_PAGE_SIZE ? null : event.pageSize,
    });
  }

  onSort(sort: Sort): void {
    const isSortable = (SCAN_IMPORT_SORTABLE_PROPERTIES as readonly string[]).includes(sort.active);
    const value = !sort.direction || !isSortable ? null : `${sort.active},${sort.direction}`;
    this.patchQueryParams({ sort: value, page: null });
  }

  /**
   * Só uma importação aguardando revisão leva a algum lugar: a prévia é a tela de
   * revisar e confirmar, e uma já confirmada ou descartada não tem mais o que revisar.
   */
  isPending(scanImport: ScanImportSummary): boolean {
    return scanImport.status === 'PENDING';
  }

  /** As células da tabela têm contexto `any`; os rótulos passam por aqui pelo tipo. */
  formatLabel(format: ScanFormat): string {
    return this.formatLabels[format];
  }

  statusLabel(status: ScanImportStatus): string {
    return this.statusLabels[status];
  }

  statusIcon(status: ScanImportStatus): string {
    return this.statusIcons[status];
  }

  /** Cor é sempre reforço: o ícone e o texto já identificam a situação. */
  statusClass(status: ScanImportStatus): string {
    return `imports-status--${status.toLowerCase()}`;
  }

  /**
   * Os cinco contadores em uma célula só: separados em colunas, a tabela ficaria larga
   * demais para ser lida, e eles só fazem sentido comparados entre si.
   */
  countersLabel(scanImport: ScanImportSummary): string {
    return (
      `${scanImport.totalFindings} achados · ${scanImport.matchedCount} com ativo · ` +
      `${scanImport.unmatchedCount} sem ativo · ${scanImport.duplicateCount} já registrados · ` +
      `${scanImport.importedCount} importados · ${scanImport.skippedCount} ignorados`
    );
  }

  trackById(_index: number, scanImport: ScanImportSummary): number {
    return scanImport.id;
  }

  /** `null` remove o parâmetro da URL; os demais são mesclados aos existentes. */
  private patchQueryParams(queryParams: Params): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }

  private parseQuery(params: ParamMap): ScanImportQuery {
    return {
      page: Math.max(0, this.toInteger(params.get('page'), 0)),
      size: Math.min(
        MAX_PAGE_SIZE,
        Math.max(1, this.toInteger(params.get('size'), DEFAULT_PAGE_SIZE)),
      ),
      sort: this.parseSort(params.get('sort')),
    };
  }

  /** Mantém apenas `propriedade,direção` que o backend reconhece; o resto vira o padrão. */
  private parseSort(raw: string | null): string {
    const [property, direction] = (raw ?? '').split(',');
    const sortable = (SCAN_IMPORT_SORTABLE_PROPERTIES as readonly string[]).includes(property);
    if (!sortable || (direction !== 'asc' && direction !== 'desc')) {
      return SCAN_IMPORT_DEFAULT_SORT;
    }
    return `${property},${direction}`;
  }

  private toInteger(raw: string | null, fallback: number): number {
    const parsed = Number(raw);
    return raw !== null && Number.isInteger(parsed) ? parsed : fallback;
  }
}
