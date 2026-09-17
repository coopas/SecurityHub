import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { Subject, debounceTime, filter, map, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { ProjectService } from '../../projects/services/project.service';
import {
  ASSET_SORTABLE_PROPERTIES,
  ASSET_TYPES,
  ASSET_TYPE_ICONS,
  ASSET_TYPE_LABELS,
  Asset,
  AssetQuery,
  AssetSortProperty,
  AssetType,
  CRITICALITIES,
  CRITICALITY_ICONS,
  CRITICALITY_LABELS,
  Criticality,
  ENVIRONMENTS,
  ENVIRONMENT_ICONS,
  ENVIRONMENT_LABELS,
  Environment,
  PROJECT_OPTIONS_PAGE_SIZE,
  ProjectOption,
} from '../models/asset.model';
import { AssetService } from '../services/asset.service';

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;
export const DEFAULT_SORT = 'createdAt,desc';
export const SEARCH_DEBOUNCE_MS = 350;

/**
 * Asset listing. The URL query params are the single source of truth for the filters,
 * the pagination and the sorting: any interaction navigates and it is the navigation
 * that triggers the fetch, so reloading or going back restores the same screen.
 */
@Component({
  selector: 'app-asset-list',
  templateUrl: './asset-list.component.html',
  styleUrls: ['../assets.scss'],
})
export class AssetListComponent implements OnInit, OnDestroy {
  readonly types = ASSET_TYPES;
  readonly environments = ENVIRONMENTS;
  readonly criticalities = CRITICALITIES;
  readonly typeLabels = ASSET_TYPE_LABELS;
  readonly environmentLabels = ENVIRONMENT_LABELS;
  readonly criticalityLabels = CRITICALITY_LABELS;
  readonly pageSizeOptions = [10, 20, 50, MAX_PAGE_SIZE];

  readonly searchControl = new FormControl<string>('', { nonNullable: true });
  readonly projectControl = new FormControl<number | ''>('', { nonNullable: true });
  readonly typeControl = new FormControl<AssetType | ''>('', { nonNullable: true });
  readonly environmentControl = new FormControl<Environment | ''>('', { nonNullable: true });
  readonly criticalityControl = new FormControl<Criticality | ''>('', { nonNullable: true });

  query: AssetQuery = { page: 0, size: DEFAULT_PAGE_SIZE, sort: DEFAULT_SORT };
  assets: Asset[] = [];
  projects: ProjectOption[] = [];
  totalElements = 0;
  state: ViewState | null = 'loading';
  isAdmin = false;
  actionError: string | null = null;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly assetService: AssetService,
    private readonly projectService: ProjectService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get displayedColumns(): string[] {
    const columns = [
      'name',
      'type',
      'environment',
      'criticality',
      'project',
      'vulnerabilityCount',
      'createdAt',
    ];
    return this.isAdmin ? [...columns, 'actions'] : columns;
  }

  get sortActive(): string {
    return this.query.sort.split(',')[0];
  }

  get sortDirection(): 'asc' | 'desc' {
    return this.query.sort.endsWith(',asc') ? 'asc' : 'desc';
  }

  get hasFilters(): boolean {
    return (
      !!this.query.search ||
      !!this.query.projectId ||
      !!this.query.type ||
      !!this.query.environment ||
      !!this.query.criticality
    );
  }

  get emptyMessage(): string {
    return this.hasFilters
      ? 'Nenhum ativo encontrado para os filtros aplicados.'
      : 'Nenhum ativo cadastrado ainda.';
  }

  ngOnInit(): void {
    this.isAdmin = this.authService.hasRole('ADMIN');
    this.loadProjects();

    this.searchControl.valueChanges
      .pipe(
        debounceTime(SEARCH_DEBOUNCE_MS),
        map((value) => value.trim()),
        // Compared against the filter already applied, and not against the previous
        // emission of the stream itself: the route rewrites the control with
        // emitEvent: false, so a distinctUntilChanged would hold a value the user no longer
        // sees and would swallow the re-application of an identical term after the filters
        // were cleared.
        filter((search) => search !== (this.query.search ?? '')),
        takeUntil(this.destroy$),
      )
      .subscribe((search) => this.patchQueryParams({ search: search || null, page: null }));

    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      this.query = this.parseQuery(params);
      this.searchControl.setValue(this.query.search ?? '', { emitEvent: false });
      this.projectControl.setValue(this.query.projectId ?? '', { emitEvent: false });
      this.typeControl.setValue(this.query.type ?? '', { emitEvent: false });
      this.environmentControl.setValue(this.query.environment ?? '', { emitEvent: false });
      this.criticalityControl.setValue(this.query.criticality ?? '', { emitEvent: false });
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.assetService.list(this.query).subscribe({
      next: (page) => {
        this.assets = page.content;
        this.totalElements = page.totalElements;
        this.state = page.content.length === 0 ? 'empty' : null;
      },
      error: () => {
        this.assets = [];
        this.totalElements = 0;
        this.state = 'error';
      },
    });
  }

  onProjectChange(projectId: number | ''): void {
    this.patchQueryParams({ projectId: projectId || null, page: null });
  }

  onTypeChange(type: AssetType | ''): void {
    this.patchQueryParams({ type: type || null, page: null });
  }

  onEnvironmentChange(environment: Environment | ''): void {
    this.patchQueryParams({ environment: environment || null, page: null });
  }

  onCriticalityChange(criticality: Criticality | ''): void {
    this.patchQueryParams({ criticality: criticality || null, page: null });
  }

  onPage(event: PageEvent): void {
    this.patchQueryParams({
      page: event.pageIndex > 0 ? event.pageIndex : null,
      size: event.pageSize === DEFAULT_PAGE_SIZE ? null : event.pageSize,
    });
  }

  onSort(sort: Sort): void {
    const isSortable = (ASSET_SORTABLE_PROPERTIES as readonly string[]).includes(sort.active);
    const value = !sort.direction || !isSortable ? null : `${sort.active},${sort.direction}`;
    this.patchQueryParams({ sort: value, page: null });
  }

  clearFilters(): void {
    this.patchQueryParams({
      search: null,
      projectId: null,
      type: null,
      environment: null,
      criticality: null,
      page: null,
    });
  }

  confirmDelete(asset: Asset): void {
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
          this.delete(asset);
        }
      });
  }

  /** The table cells have an `any` context; the labels go through here to keep the type. */
  typeLabel(type: AssetType): string {
    return this.typeLabels[type];
  }

  typeIcon(type: AssetType): string {
    return ASSET_TYPE_ICONS[type];
  }

  environmentLabel(environment: Environment): string {
    return this.environmentLabels[environment];
  }

  environmentIcon(environment: Environment): string {
    return ENVIRONMENT_ICONS[environment];
  }

  criticalityLabel(criticality: Criticality): string {
    return this.criticalityLabels[criticality];
  }

  criticalityIcon(criticality: Criticality): string {
    return CRITICALITY_ICONS[criticality];
  }

  /** Color is always reinforcement: the icon and the text already identify the criticality. */
  criticalityClass(criticality: Criticality): string {
    return `assets-criticality--${criticality.toLowerCase()}`;
  }

  trackById(_index: number, asset: Asset): number {
    return asset.id;
  }

  /**
   * Options for the project filter. A failure here does not block the listing: the filter
   * is simply left with no options, and the `ErrorInterceptor` already warns the user.
   */
  private loadProjects(): void {
    this.projectService
      .list({ page: 0, size: PROJECT_OPTIONS_PAGE_SIZE, sort: 'name,asc' })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (page) => {
          this.projects = page.content.map((project) => ({ id: project.id, name: project.name }));
        },
        error: () => {
          this.projects = [];
        },
      });
  }

  private delete(asset: Asset): void {
    this.actionError = null;
    this.assetService.delete(asset.id).subscribe({
      next: () => {
        this.notifications.success('Ativo excluído.');
        // Deleting the last item on the page would bring an empty page: step back one page.
        if (this.assets.length === 1 && this.query.page > 0) {
          this.patchQueryParams({ page: this.query.page - 1 || null });
          return;
        }
        this.load();
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.actionError = apiError?.message ?? 'Não foi possível excluir o ativo.';
      },
    });
  }

  /** `null` removes the parameter from the URL; the others are merged into the existing ones. */
  private patchQueryParams(queryParams: Params): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }

  private parseQuery(params: ParamMap): AssetQuery {
    const search = (params.get('search') ?? '').trim();
    const projectId = this.toInteger(params.get('projectId'), 0);
    const type = params.get('type') as AssetType | null;
    const environment = params.get('environment') as Environment | null;
    const criticality = params.get('criticality') as Criticality | null;

    return {
      page: Math.max(0, this.toInteger(params.get('page'), 0)),
      size: Math.min(
        MAX_PAGE_SIZE,
        Math.max(1, this.toInteger(params.get('size'), DEFAULT_PAGE_SIZE)),
      ),
      sort: this.parseSort(params.get('sort')),
      search: search || undefined,
      projectId: projectId > 0 ? projectId : undefined,
      type: type && ASSET_TYPES.includes(type) ? type : undefined,
      environment: environment && ENVIRONMENTS.includes(environment) ? environment : undefined,
      criticality: criticality && CRITICALITIES.includes(criticality) ? criticality : undefined,
    };
  }

  /** Keeps only a `property,direction` accepted by the backend; the rest becomes the default. */
  private parseSort(raw: string | null): string {
    const [property, direction] = (raw ?? '').split(',');
    const sortable = (ASSET_SORTABLE_PROPERTIES as readonly string[]).includes(property);
    if (!sortable || (direction !== 'asc' && direction !== 'desc')) {
      return DEFAULT_SORT;
    }
    return `${property as AssetSortProperty},${direction}`;
  }

  private toInteger(raw: string | null, fallback: number): number {
    const parsed = Number(raw);
    return raw !== null && Number.isInteger(parsed) ? parsed : fallback;
  }
}
