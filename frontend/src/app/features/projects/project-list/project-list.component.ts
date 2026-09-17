import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { Subject, debounceTime, filter, map, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { NotificationService } from '../../../core/services/notification.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  PROJECT_SORTABLE_PROPERTIES,
  PROJECT_STATUSES,
  PROJECT_STATUS_LABELS,
  Project,
  ProjectQuery,
  ProjectSortProperty,
  ProjectStatus,
} from '../models/project.model';
import { ProjectService } from '../services/project.service';

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;
export const DEFAULT_SORT = 'createdAt,desc';
export const SEARCH_DEBOUNCE_MS = 350;

/**
 * Listagem de projetos. Os query params da URL são a única fonte de verdade dos
 * filtros, da paginação e da ordenação: qualquer interação navega e a navegação
 * é que dispara a busca, de modo que recarregar ou voltar restaura a mesma tela.
 */
@Component({
  selector: 'app-project-list',
  templateUrl: './project-list.component.html',
  styleUrls: ['../projects.scss'],
})
export class ProjectListComponent implements OnInit, OnDestroy {
  readonly statuses = PROJECT_STATUSES;
  readonly statusLabels = PROJECT_STATUS_LABELS;
  readonly pageSizeOptions = [10, 20, 50, MAX_PAGE_SIZE];

  readonly searchControl = new FormControl<string>('', { nonNullable: true });
  readonly statusControl = new FormControl<ProjectStatus | ''>('', { nonNullable: true });

  query: ProjectQuery = { page: 0, size: DEFAULT_PAGE_SIZE, sort: DEFAULT_SORT };
  projects: Project[] = [];
  totalElements = 0;
  state: ViewState | null = 'loading';
  isAdmin = false;
  actionError: string | null = null;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly projectService: ProjectService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get displayedColumns(): string[] {
    const columns = ['name', 'status', 'assetCount', 'createdAt'];
    return this.isAdmin ? [...columns, 'actions'] : columns;
  }

  get sortActive(): string {
    return this.query.sort.split(',')[0];
  }

  get sortDirection(): 'asc' | 'desc' {
    return this.query.sort.endsWith(',asc') ? 'asc' : 'desc';
  }

  get hasFilters(): boolean {
    return !!this.query.search || !!this.query.status;
  }

  get emptyMessage(): string {
    return this.hasFilters
      ? 'Nenhum projeto encontrado para os filtros aplicados.'
      : 'Nenhum projeto cadastrado ainda.';
  }

  ngOnInit(): void {
    this.isAdmin = this.authService.hasRole('ADMIN');

    this.searchControl.valueChanges
      .pipe(
        debounceTime(SEARCH_DEBOUNCE_MS),
        map((value) => value.trim()),
        // Comparado com o filtro já aplicado, e não com a emissão anterior do próprio
        // stream: a rota reescreve o controle com emitEvent: false, então um
        // distinctUntilChanged guardaria um valor que o usuário já não vê e engoliria a
        // reaplicação de um termo idêntico depois de limpar os filtros.
        filter((search) => search !== (this.query.search ?? '')),
        takeUntil(this.destroy$),
      )
      .subscribe((search) => this.patchQueryParams({ search: search || null, page: null }));

    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      this.query = this.parseQuery(params);
      this.searchControl.setValue(this.query.search ?? '', { emitEvent: false });
      this.statusControl.setValue(this.query.status ?? '', { emitEvent: false });
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.projectService.list(this.query).subscribe({
      next: (page) => {
        this.projects = page.content;
        this.totalElements = page.totalElements;
        this.state = page.content.length === 0 ? 'empty' : null;
      },
      error: () => {
        this.projects = [];
        this.totalElements = 0;
        this.state = 'error';
      },
    });
  }

  onStatusChange(status: ProjectStatus | ''): void {
    this.patchQueryParams({ status: status || null, page: null });
  }

  onPage(event: PageEvent): void {
    this.patchQueryParams({
      page: event.pageIndex > 0 ? event.pageIndex : null,
      size: event.pageSize === DEFAULT_PAGE_SIZE ? null : event.pageSize,
    });
  }

  onSort(sort: Sort): void {
    const isSortable = (PROJECT_SORTABLE_PROPERTIES as readonly string[]).includes(sort.active);
    const value = !sort.direction || !isSortable ? null : `${sort.active},${sort.direction}`;
    this.patchQueryParams({ sort: value, page: null });
  }

  clearFilters(): void {
    this.patchQueryParams({ search: null, status: null, page: null });
  }

  confirmDelete(project: Project): void {
    const data: ConfirmDialogData = {
      title: 'Excluir projeto',
      message: `Excluir o projeto "${project.name}"? Esta ação não pode ser desfeita.`,
      confirmLabel: 'Excluir',
      destructive: true,
    };

    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(takeUntil(this.destroy$))
      .subscribe((confirmed) => {
        if (confirmed) {
          this.delete(project);
        }
      });
  }

  /** A célula da tabela tem contexto `any`; o rótulo passa por aqui para manter o tipo. */
  statusLabel(status: ProjectStatus): string {
    return this.statusLabels[status];
  }

  trackById(_index: number, project: Project): number {
    return project.id;
  }

  private delete(project: Project): void {
    this.actionError = null;
    this.projectService.delete(project.id).subscribe({
      next: () => {
        this.notifications.success('Projeto excluído.');
        // Excluir o último item da página traria uma página vazia: volta uma página.
        if (this.projects.length === 1 && this.query.page > 0) {
          this.patchQueryParams({ page: this.query.page - 1 || null });
          return;
        }
        this.load();
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.actionError = apiError?.message ?? 'Não foi possível excluir o projeto.';
      },
    });
  }

  /** `null` remove o parâmetro da URL; os demais são mesclados aos existentes. */
  private patchQueryParams(queryParams: Params): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }

  private parseQuery(params: ParamMap): ProjectQuery {
    const search = (params.get('search') ?? '').trim();
    const status = params.get('status') as ProjectStatus | null;

    return {
      page: Math.max(0, this.toInteger(params.get('page'), 0)),
      size: Math.min(
        MAX_PAGE_SIZE,
        Math.max(1, this.toInteger(params.get('size'), DEFAULT_PAGE_SIZE)),
      ),
      sort: this.parseSort(params.get('sort')),
      search: search || undefined,
      status: status && PROJECT_STATUSES.includes(status) ? status : undefined,
    };
  }

  /** Mantém apenas `propriedade,direção` aceitos pelo backend; o resto vira o padrão. */
  private parseSort(raw: string | null): string {
    const [property, direction] = (raw ?? '').split(',');
    const sortable = (PROJECT_SORTABLE_PROPERTIES as readonly string[]).includes(property);
    if (!sortable || (direction !== 'asc' && direction !== 'desc')) {
      return DEFAULT_SORT;
    }
    return `${property as ProjectSortProperty},${direction}`;
  }

  private toInteger(raw: string | null, fallback: number): number {
    const parsed = Number(raw);
    return raw !== null && Number.isInteger(parsed) ? parsed : fallback;
  }
}
