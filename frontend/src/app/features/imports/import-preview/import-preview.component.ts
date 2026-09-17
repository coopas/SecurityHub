import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subject, finalize, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { toApiError } from '../../../core/utils/api-error.util';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { AssetService } from '../../assets/services/asset.service';
import {
  SEVERITIES_BY_RISK,
  SEVERITY_ICONS,
  SEVERITY_LABELS,
} from '../../vulnerabilities/models/vulnerability.model';
import {
  AssetOption,
  OPTIONS_PAGE_SIZE,
  SCAN_FINDING_STATUS_ICONS,
  SCAN_FINDING_STATUS_LABELS,
  SCAN_FORMAT_LABELS,
  SCAN_IMPORT_STATUS_ICONS,
  SCAN_IMPORT_STATUS_LABELS,
  ScanFinding,
  ScanFindingStatus,
  ScanImport,
  Severity,
} from '../models/scan-import.model';
import { ImportService } from '../services/import.service';

/** Header counters, in the order the screen shows them. */
export interface CounterItem {
  key: string;
  label: string;
  value: number;
}

/**
 * Import preview: what the scan found, how the server classified each finding and the
 * decision to confirm or discard.
 *
 * Filtering and sorting are client-side (`MatTableDataSource` + `MatSort` +
 * `filterPredicate`), and not URL-driven as in `UserListComponent`: the findings all
 * already came inside `GET /scan-imports/{id}`, so mirroring the filter in the URL would
 * mean navigating to redo work the browser does on its own.
 *
 * A confirmed or discarded import is history: the screen shows it whole, with no action
 * control at all, because every action on it would already be refused with `CONFLICT`.
 */
@Component({
  selector: 'app-import-preview',
  templateUrl: './import-preview.component.html',
  styleUrls: ['../imports.scss'],
})
export class ImportPreviewComponent implements OnInit, OnDestroy {
  readonly displayedColumns = [
    'severity',
    'title',
    'target',
    'status',
    'asset',
    'discoveredAt',
  ];
  readonly severityLabels = SEVERITY_LABELS;
  readonly severityIcons = SEVERITY_ICONS;
  readonly findingStatusLabels = SCAN_FINDING_STATUS_LABELS;
  readonly findingStatusIcons = SCAN_FINDING_STATUS_ICONS;
  readonly importStatusLabels = SCAN_IMPORT_STATUS_LABELS;
  readonly importStatusIcons = SCAN_IMPORT_STATUS_ICONS;
  readonly formatLabels = SCAN_FORMAT_LABELS;

  readonly dataSource = new MatTableDataSource<ScanFinding>([]);
  readonly searchControl = new FormControl<string>('', { nonNullable: true });

  scanImport: ScanImport | null = null;
  state: ViewState | null = 'loading';
  /** The table's own state: a report with no findings still has a header and actions. */
  findingsState: ViewState | null = null;
  errorMessage: string | null = null;
  actionError: string | null = null;

  assets: AssetOption[] = [];
  assetsState: ViewState | null = null;
  /** Id of the finding whose link is being saved; disables only that one picker. */
  savingFindingId: number | null = null;

  submitting = false;
  canImport = false;

  private importId: number | null = null;
  private readonly destroy$ = new Subject<void>();

  @ViewChild(MatSort)
  set sort(sort: MatSort | undefined) {
    if (sort) {
      this.dataSource.sort = sort;
    }
  }

  constructor(
    private readonly importService: ImportService,
    private readonly assetService: AssetService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get notFound(): boolean {
    return this.errorMessage === 'Importação não encontrada.';
  }

  get isPending(): boolean {
    return this.scanImport?.status === 'PENDING';
  }

  /** Only those who can create vulnerabilities decide the fate of a pending import. */
  get canAct(): boolean {
    return this.canImport && this.isPending && !this.submitting;
  }

  /**
   * Findings that will become a vulnerability on confirm: the ones that already have an
   * asset. Those with no asset or already recorded are skipped, and that is what the
   * confirmation has to say out loud, because it is what the person loses by confirming
   * too early.
   */
  get willCreateCount(): number {
    return this.scanImport?.matchedCount ?? 0;
  }

  get willSkipCount(): number {
    if (!this.scanImport) {
      return 0;
    }
    return this.scanImport.unmatchedCount + this.scanImport.duplicateCount;
  }

  get counters(): CounterItem[] {
    const scanImport = this.scanImport;
    if (!scanImport) {
      return [];
    }
    return [
      { key: 'total', label: 'Achados', value: scanImport.totalFindings },
      { key: 'matched', label: 'Com ativo', value: scanImport.matchedCount },
      { key: 'unmatched', label: 'Sem ativo', value: scanImport.unmatchedCount },
      { key: 'duplicate', label: 'Já registrados', value: scanImport.duplicateCount },
      { key: 'imported', label: 'Importados', value: scanImport.importedCount },
      { key: 'skipped', label: 'Ignorados', value: scanImport.skippedCount },
    ];
  }

  ngOnInit(): void {
    this.canImport = this.authService.hasRole('ADMIN', 'ANALYST');

    this.dataSource.filterPredicate = (finding, filter) =>
      [
        finding.title,
        finding.ruleId,
        finding.target,
        finding.cve ?? '',
        finding.assetName ?? '',
        this.findingStatusLabels[finding.status],
        this.severityLabels[finding.severity],
      ]
        .join(' ')
        .toLowerCase()
        .includes(filter);

    this.dataSource.sortingDataAccessor = (finding, property): string | number => {
      switch (property) {
        case 'severity':
          // From the highest risk down to the lowest, and not in alphabetical order,
          // which would mix "Crítica" with "Baixa" with no meaning at all for whoever
          // does triage.
          return SEVERITIES_BY_RISK.length - SEVERITIES_BY_RISK.indexOf(finding.severity);
        case 'target':
          return finding.target;
        case 'status':
          return this.findingStatusLabels[finding.status];
        case 'asset':
          // No asset goes to the end of the ascending order, and not to the beginning.
          return finding.assetName ?? '';
        case 'discoveredAt':
          return finding.discoveredAt;
        default:
          return finding.title;
      }
    };

    this.searchControl.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe((term) => (this.dataSource.filter = term.trim().toLowerCase()));

    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(id) || id <= 0) {
      this.state = 'error';
      this.errorMessage = 'Importação não encontrada.';
      return;
    }

    this.importId = id;
    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    if (this.importId === null) {
      return;
    }

    this.state = 'loading';
    this.errorMessage = null;
    this.importService
      .get(this.importId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (scanImport) => {
          this.apply(scanImport);
          // The assets only serve the picker of the unlinked findings; a closed import
          // has no picker at all to feed.
          if (this.isPending && this.canImport) {
            this.loadAssets(scanImport.projectId);
          }
        },
        error: (error: unknown) => {
          const apiError = toApiError(error);
          this.scanImport = null;
          this.dataSource.data = [];
          this.findingsState = null;
          this.state = 'error';
          this.errorMessage =
            apiError?.code === 'NOT_FOUND'
              ? 'Importação não encontrada.'
              : apiError?.message ?? 'Não foi possível carregar a importação.';
        },
      });
  }

  loadAssets(projectId: number): void {
    this.assetsState = 'loading';
    this.assetService
      .list({ page: 0, size: OPTIONS_PAGE_SIZE, sort: 'name,asc', projectId })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (page) => {
          this.assets = page.content.map((asset) => ({
            id: asset.id,
            name: asset.name,
            projectId: asset.projectId,
            projectName: asset.projectName,
          }));
          this.assetsState = this.assets.length === 0 ? 'empty' : null;
        },
        error: () => {
          // A failure here leaves the pickers empty, but does not hide the preview; the
          // ErrorInterceptor has already warned the user.
          this.assets = [];
          this.assetsState = 'error';
        },
      });
  }

  /** Only a finding with no asset asks for a choice; the server already resolved the rest. */
  needsAsset(finding: ScanFinding): boolean {
    return finding.status === 'UNMATCHED';
  }

  /**
   * `default-property-inclusion: non_null` makes the API omit the key instead of sending
   * null, so what arrives in a finding with no score is `undefined`: comparing with
   * `!== null` would print "· CVSS" with no number. Truthiness would fix that and open
   * another hole, because 0.0 is a valid CVSS and would vanish from the screen.
   */
  hasCvss(finding: ScanFinding): boolean {
    return finding.cvssScore !== null && finding.cvssScore !== undefined;
  }

  isSaving(finding: ScanFinding): boolean {
    return this.savingFindingId === finding.id;
  }

  onAssetSelected(finding: ScanFinding, assetId: number): void {
    if (this.importId === null || this.savingFindingId !== null) {
      return;
    }

    this.actionError = null;
    this.savingFindingId = finding.id;
    this.importService
      .mapFinding(this.importId, finding.id, assetId)
      .pipe(
        finalize(() => (this.savingFindingId = null)),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (updated) => {
          // The row becomes what the server returned: it may have reclassified the
          // finding as already recorded instead of simply linking it.
          this.replaceFinding(updated);
          this.notifications.success(
            updated.assetName
              ? `Achado vinculado a ${updated.assetName}.`
              : 'Achado atualizado.',
          );
        },
        error: (error: unknown) => {
          this.actionError =
            toApiError(error)?.message ?? 'Não foi possível vincular o achado ao ativo.';
        },
      });
  }

  confirmImport(): void {
    const scanImport = this.scanImport;
    if (!scanImport || !this.canAct) {
      return;
    }

    this.confirmDialog({
      title: 'Confirmar importação',
      message:
        `Confirmar "${scanImport.originalFilename}"? ` +
        `${this.vulnerabilityCount(this.willCreateCount)} em ${scanImport.projectName}, e ` +
        `${this.findingCount(this.willSkipCount)} sem ativo ou já registrados ` +
        'serão ignorados. Depois de confirmada, a importação não pode ser revista.',
      confirmLabel: 'Confirmar',
    }).subscribe((confirmed) => {
      if (confirmed) {
        this.confirm();
      }
    });
  }

  discardImport(): void {
    const scanImport = this.scanImport;
    if (!scanImport || !this.canAct) {
      return;
    }

    this.confirmDialog({
      title: 'Descartar importação',
      message:
        `Descartar "${scanImport.originalFilename}"? ` +
        `${this.findingCount(scanImport.totalFindings)} serão perdidos e nenhuma ` +
        'vulnerabilidade será criada. Esta ação não pode ser desfeita.',
      confirmLabel: 'Descartar',
      destructive: true,
    }).subscribe((confirmed) => {
      if (confirmed) {
        this.discard();
      }
    });
  }

  severityLabel(severity: Severity): string {
    return this.severityLabels[severity];
  }

  severityIcon(severity: Severity): string {
    return this.severityIcons[severity];
  }

  /** Color is always reinforcement: the icon and the text already identify severity and status. */
  severityClass(severity: Severity): string {
    return `imports-severity--${severity.toLowerCase()}`;
  }

  findingStatusLabel(status: ScanFindingStatus): string {
    return this.findingStatusLabels[status];
  }

  findingStatusIcon(status: ScanFindingStatus): string {
    return this.findingStatusIcons[status];
  }

  findingStatusClass(status: ScanFindingStatus): string {
    return `imports-finding--${status.toLowerCase()}`;
  }

  trackById(_index: number, finding: ScanFinding): number {
    return finding.id;
  }

  private confirm(): void {
    if (this.importId === null) {
      return;
    }

    this.actionError = null;
    this.submitting = true;
    this.importService
      .confirm(this.importId)
      .pipe(
        finalize(() => (this.submitting = false)),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (scanImport) => {
          this.apply(scanImport);
          this.notifications.success(
            `Importação confirmada: ${this.vulnerabilityCount(scanImport.importedCount)}.`,
          );
        },
        error: (error: unknown) => this.handleActionError(error, 'confirmar'),
      });
  }

  private discard(): void {
    if (this.importId === null) {
      return;
    }

    this.actionError = null;
    this.submitting = true;
    this.importService
      .discard(this.importId)
      .pipe(
        finalize(() => (this.submitting = false)),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: () => {
          this.notifications.success('Importação descartada.');
          void this.router.navigate(['/imports']);
        },
        error: (error: unknown) => this.handleActionError(error, 'descartar'),
      });
  }

  /**
   * `CONFLICT` means the import has already been resolved in another tab or by another
   * person: reloading is what brings the screen back to the truth, and from there it
   * hides the buttons on its own.
   */
  private handleActionError(error: unknown, verb: string): void {
    const apiError = toApiError(error);
    if (apiError?.code === 'CONFLICT') {
      this.actionError =
        apiError.message || 'Esta importação já foi resolvida. A tela foi atualizada.';
      this.load();
      return;
    }
    if (apiError?.code === 'NOT_FOUND') {
      this.actionError = 'Esta importação não existe mais.';
      this.load();
      return;
    }
    this.actionError = apiError?.message ?? `Não foi possível ${verb} a importação.`;
  }

  private confirmDialog(data: ConfirmDialogData): Observable<boolean | undefined> {
    return this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(takeUntil(this.destroy$));
  }

  private replaceFinding(updated: ScanFinding): void {
    const scanImport = this.scanImport;
    if (!scanImport) {
      return;
    }

    const findings = scanImport.findings.map((finding) =>
      finding.id === updated.id ? updated : finding,
    );
    // The counters at the top come from the server and are not recomputed here: they
    // only add up again on the next `GET`, and inventing them would leave the screen
    // disagreeing with the backend.
    this.scanImport = { ...scanImport, findings };
    this.dataSource.data = findings;
  }

  private apply(scanImport: ScanImport): void {
    this.scanImport = scanImport;
    this.dataSource.data = scanImport.findings;
    this.state = null;
    this.findingsState = scanImport.findings.length === 0 ? 'empty' : null;
  }

  /** Verb agreement in the dialog: "1 vulnerabilidade será criada" and not "1 serão". */
  private vulnerabilityCount(count: number): string {
    return count === 1
      ? '1 vulnerabilidade será criada'
      : `${count} vulnerabilidades serão criadas`;
  }

  private findingCount(count: number): string {
    return count === 1 ? '1 achado' : `${count} achados`;
  }
}
