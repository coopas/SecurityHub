import { Component, OnDestroy, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  ASSET_TYPE_ICONS,
  ASSET_TYPE_LABELS,
  Asset,
  CRITICALITY_ICONS,
  CRITICALITY_LABELS,
  ENVIRONMENT_ICONS,
  ENVIRONMENT_LABELS,
} from '../models/asset.model';
import { AssetService } from '../services/asset.service';

@Component({
  selector: 'app-asset-detail',
  templateUrl: './asset-detail.component.html',
  styleUrls: ['../assets.scss'],
})
export class AssetDetailComponent implements OnInit, OnDestroy {
  readonly typeLabels = ASSET_TYPE_LABELS;
  readonly typeIcons = ASSET_TYPE_ICONS;
  readonly environmentLabels = ENVIRONMENT_LABELS;
  readonly environmentIcons = ENVIRONMENT_ICONS;
  readonly criticalityLabels = CRITICALITY_LABELS;
  readonly criticalityIcons = CRITICALITY_ICONS;

  asset: Asset | null = null;
  state: ViewState | null = 'loading';
  errorMessage: string | null = null;
  actionError: string | null = null;
  isAdmin = false;
  deleting = false;

  private assetId: number | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly assetService: AssetService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get notFound(): boolean {
    return this.errorMessage === 'Ativo não encontrado.';
  }

  /** Cor é sempre reforço: o ícone e o texto já identificam a criticidade. */
  get criticalityClass(): string {
    return this.asset ? `assets-criticality--${this.asset.criticality.toLowerCase()}` : '';
  }

  ngOnInit(): void {
    this.isAdmin = this.authService.hasRole('ADMIN');

    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(id) || id <= 0) {
      this.state = 'error';
      this.errorMessage = 'Ativo não encontrado.';
      return;
    }

    this.assetId = id;
    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    if (this.assetId === null) {
      return;
    }

    this.state = 'loading';
    this.errorMessage = null;
    this.assetService.get(this.assetId).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.state = null;
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.asset = null;
        this.state = 'error';
        this.errorMessage =
          apiError?.code === 'NOT_FOUND'
            ? 'Ativo não encontrado.'
            : apiError?.message ?? 'Não foi possível carregar o ativo.';
      },
    });
  }

  confirmDelete(): void {
    const asset = this.asset;
    if (!asset) {
      return;
    }

    const data: ConfirmDialogData = {
      title: 'Excluir ativo',
      message: `Excluir o ativo "${asset.name}"? Esta ação não pode ser desfeita.`,
      confirmLabel: 'Excluir',
      destructive: true,
    };

    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(takeUntil(this.destroy$))
      .subscribe((confirmed) => {
        if (confirmed) {
          this.delete(asset.id);
        }
      });
  }

  private delete(id: number): void {
    this.actionError = null;
    this.deleting = true;
    this.assetService.delete(id).subscribe({
      next: () => {
        this.deleting = false;
        this.notifications.success('Ativo excluído.');
        void this.router.navigate(['/assets']);
      },
      error: (error: unknown) => {
        this.deleting = false;
        // 409 aparece quando o ativo já tem vulnerabilidades: a mensagem do servidor
        // explica o motivo melhor que qualquer texto fixo aqui.
        const apiError = toApiError(error);
        this.actionError = apiError?.message ?? 'Não foi possível excluir o ativo.';
      },
    });
  }
}
