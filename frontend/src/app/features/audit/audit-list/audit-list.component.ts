import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { PageEvent } from '@angular/material/paginator';
import { Sort } from '@angular/material/sort';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  AUDIT_ACTIONS,
  AUDIT_ACTION_ICONS,
  AUDIT_ACTION_LABELS,
  AUDIT_DEFAULT_SORT,
  AUDIT_ENTITY_TYPES,
  AUDIT_SORTABLE_PROPERTIES,
  AuditAction,
  AuditActorOption,
  AuditLog,
  AuditQuery,
  AuditSortProperty,
  auditEntityTypeLabel,
} from '../models/audit.model';
import { AuditActorService } from '../services/audit-actor.service';
import { AuditService } from '../services/audit.service';
import { parseCivilDate, toCivilDate, toLocalDate } from '../utils/audit-date.util';

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

/**
 * The company's audit trail. Read-only by definition: the resource is append-only in the
 * backend and this screen offers no edit or delete action at all.
 *
 * As in the other listings, the URL query params are the only source of truth for the
 * filters, the pagination and the sorting; it is the route subscription that fires the
 * fetch, so reloading, going back or sharing the link restores exactly the same query.
 *
 * There is no free-text search field: `AuditController` accepts only entityType, actorId,
 * action, from and to. An invented `search` would travel in the URL and be ignored by the
 * server, which is worse than not existing.
 */
@Component({
  selector: 'app-audit-list',
  templateUrl: './audit-list.component.html',
  styleUrls: ['../audit.scss'],
})
export class AuditListComponent implements OnInit, OnDestroy {
  readonly actions = AUDIT_ACTIONS;
  readonly actionLabels = AUDIT_ACTION_LABELS;
  readonly entityTypes = AUDIT_ENTITY_TYPES;
  readonly pageSizeOptions = [10, 20, 50, MAX_PAGE_SIZE];
  readonly displayedColumns = ['createdAt', 'actor', 'action', 'entity', 'ipAddress', 'expand'];
  /** Detail row: a stable reference so the array is not recreated on every check. */
  readonly detailColumns = ['detail'];

  readonly entityTypeControl = new FormControl<string>('', { nonNullable: true });
  readonly actorControl = new FormControl<number | ''>('', { nonNullable: true });
  readonly actionControl = new FormControl<AuditAction | ''>('', { nonNullable: true });
  readonly fromControl = new FormControl<Date | null>(null);
  readonly toControl = new FormControl<Date | null>(null);

  query: AuditQuery = { page: 0, size: DEFAULT_PAGE_SIZE, sort: AUDIT_DEFAULT_SORT };
  logs: AuditLog[] = [];
  actors: AuditActorOption[] = [];
  totalElements = 0;
  state: ViewState | null = 'loading';
  loadError: string | null = null;

  private readonly expanded = new Set<number>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly auditService: AuditService,
    private readonly actorService: AuditActorService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get sortActive(): string {
    return this.query.sort.split(',')[0];
  }

  get sortDirection(): 'asc' | 'desc' {
    return this.query.sort.endsWith(',asc') ? 'asc' : 'desc';
  }

  get hasFilters(): boolean {
    return (
      !!this.query.entityType || !!this.query.actorId || !!this.query.action || !!this.query.from || !!this.query.to
    );
  }

  get emptyMessage(): string {
    return this.hasFilters
      ? 'Nenhum registro de auditoria para os filtros aplicados.'
      : 'Nenhum registro de auditoria ainda.';
  }

  /** An inverted range would always return empty; we warn instead of leaving the user guessing. */
  get invalidRange(): boolean {
    return !!this.query.from && !!this.query.to && this.query.from > this.query.to;
  }

  ngOnInit(): void {
    this.loadActors();

    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      this.query = this.parseQuery(params);
      this.entityTypeControl.setValue(this.query.entityType ?? '', { emitEvent: false });
      this.actorControl.setValue(this.query.actorId ?? '', { emitEvent: false });
      this.actionControl.setValue(this.query.action ?? '', { emitEvent: false });
      this.fromControl.setValue(this.query.from ? toLocalDate(this.query.from) : null, {
        emitEvent: false,
      });
      this.toControl.setValue(this.query.to ? toLocalDate(this.query.to) : null, {
        emitEvent: false,
      });
      this.expanded.clear();
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
    this.auditService.list(this.query).subscribe({
      next: (page) => {
        this.logs = page.content;
        this.totalElements = page.totalElements;
        this.state = page.content.length === 0 ? 'empty' : null;
      },
      error: (error: unknown) => {
        this.logs = [];
        this.totalElements = 0;
        this.loadError = toApiError(error)?.message ?? null;
        this.state = 'error';
      },
    });
  }

  onEntityTypeChange(entityType: string): void {
    this.patchQueryParams({ entityType: entityType || null, page: null });
  }

  onActorChange(actorId: number | ''): void {
    this.patchQueryParams({ actorId: actorId || null, page: null });
  }

  onActionChange(action: AuditAction | ''): void {
    this.patchQueryParams({ action: action || null, page: null });
  }

  onFromChange(date: Date | null): void {
    this.patchQueryParams({ from: date ? toCivilDate(date) : null, page: null });
  }

  onToChange(date: Date | null): void {
    this.patchQueryParams({ to: date ? toCivilDate(date) : null, page: null });
  }

  onPage(event: PageEvent): void {
    this.patchQueryParams({
      page: event.pageIndex > 0 ? event.pageIndex : null,
      size: event.pageSize === DEFAULT_PAGE_SIZE ? null : event.pageSize,
    });
  }

  onSort(sort: Sort): void {
    const isSortable = (AUDIT_SORTABLE_PROPERTIES as readonly string[]).includes(sort.active);
    const value = !sort.direction || !isSortable ? null : `${sort.active},${sort.direction}`;
    this.patchQueryParams({ sort: value, page: null });
  }

  clearFilters(): void {
    this.patchQueryParams({
      entityType: null,
      actorId: null,
      action: null,
      from: null,
      to: null,
      page: null,
    });
  }

  isExpanded(log: AuditLog): boolean {
    return this.expanded.has(log.id);
  }

  toggleDetail(log: AuditLog): void {
    if (!this.expanded.delete(log.id)) {
      this.expanded.add(log.id);
    }
  }

  /** The table cells have an `any` context; the labels go through here to keep the typing. */
  actionLabel(action: AuditAction): string {
    return AUDIT_ACTION_LABELS[action] ?? action;
  }

  actionIcon(action: AuditAction): string {
    return AUDIT_ACTION_ICONS[action] ?? 'history';
  }

  /** Color is always reinforcement: the icon and the text already identify the action. */
  actionClass(action: AuditAction): string {
    return `audit-action--${action.toLowerCase().replace(/_/g, '-')}`;
  }

  entityLabel(entityType: string): string {
    return auditEntityTypeLabel(entityType);
  }

  /** A system event (login refused for a nonexistent e-mail) has no actor. */
  actorLabel(log: AuditLog): string {
    return log.actorEmail ?? 'Sistema';
  }

  detailId(log: AuditLog): string {
    return `audit-detail-${log.id}`;
  }

  detailToggleLabel(log: AuditLog): string {
    const verb = this.isExpanded(log) ? 'Ocultar' : 'Ver';
    return `${verb} a comparação de valores de ${this.actionLabel(log.action)} em ${this.entityLabel(
      log.entityType,
    )}${log.entityId ? ` ${log.entityId}` : ''}`;
  }

  /** `null` removes the param from the URL; the rest are merged into the existing ones. */
  private patchQueryParams(queryParams: Params): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }

  private loadActors(): void {
    this.actorService
      .list()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (actors) => {
          this.actors = actors;
        },
        // A failure here leaves the filter with no options, but does not prevent the
        // query; the ErrorInterceptor already warns the user.
        error: () => {
          this.actors = [];
        },
      });
  }

  private parseQuery(params: ParamMap): AuditQuery {
    const entityType = (params.get('entityType') ?? '').trim();
    const actorId = this.toInteger(params.get('actorId'), 0);
    const action = params.get('action') as AuditAction | null;

    return {
      page: Math.max(0, this.toInteger(params.get('page'), 0)),
      size: Math.min(
        MAX_PAGE_SIZE,
        Math.max(1, this.toInteger(params.get('size'), DEFAULT_PAGE_SIZE)),
      ),
      sort: this.parseSort(params.get('sort')),
      // An unknown type is discarded: the backend compares by exact equality and a free
      // value in the URL would only produce an inexplicable empty page.
      entityType: entityType && AUDIT_ENTITY_TYPES.includes(entityType) ? entityType : undefined,
      actorId: actorId > 0 ? actorId : undefined,
      action: action && AUDIT_ACTIONS.includes(action) ? action : undefined,
      from: parseCivilDate(params.get('from')) ?? undefined,
      to: parseCivilDate(params.get('to')) ?? undefined,
    };
  }

  /** Keeps only the `property,direction` the backend accepts; the rest becomes the default. */
  private parseSort(raw: string | null): string {
    const [property, direction] = (raw ?? '').split(',');
    const sortable = (AUDIT_SORTABLE_PROPERTIES as readonly string[]).includes(property);
    if (!sortable || (direction !== 'asc' && direction !== 'desc')) {
      return AUDIT_DEFAULT_SORT;
    }
    return `${property as AuditSortProperty},${direction}`;
  }

  private toInteger(raw: string | null, fallback: number): number {
    const parsed = Number(raw);
    return raw !== null && Number.isInteger(parsed) ? parsed : fallback;
  }
}
