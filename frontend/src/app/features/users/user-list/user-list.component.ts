import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MatPaginator } from '@angular/material/paginator';
import { MatSelectChange } from '@angular/material/select';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { Observable, Subject, takeUntil } from 'rxjs';

import { ROLE_LABELS, Role, User } from '../../../core/models';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { toApiError } from '../../../core/utils/api-error.util';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { INVITATION_STATUS_LABELS, Invitation } from '../models/invitation.model';
import { ASSIGNABLE_ROLES } from '../models/user-admin.model';
import { InvitationService } from '../services/invitation.service';
import { UserService } from '../services/user.service';

export const USERS_PAGE_SIZE = 10;

/**
 * Administration of the company's users.
 *
 * A deliberate departure from `AssetListComponent`: here the pagination, the sorting and the
 * search are client-side (`MatTableDataSource` + `MatPaginator` + `MatSort` +
 * `filterPredicate`), not URL-driven. `GET /users` is not paginated — it returns a plain
 * array with every user of the company, which is a small list and which the backend already
 * sorts by name. With everything in memory, mirroring filters in the URL would mean
 * navigating to redo work the browser would do on its own, and reloading the page would
 * fetch everything again anyway. The day the endpoint becomes paginated, this component
 * falls in line with the assets one.
 */
@Component({
  selector: 'app-user-list',
  templateUrl: './user-list.component.html',
  styleUrls: ['../users.scss'],
})
export class UserListComponent implements OnInit, OnDestroy {
  readonly displayedColumns = ['name', 'email', 'role', 'active', 'lastLoginAt'];
  readonly invitationColumns = ['name', 'email', 'role', 'expiresAt', 'actions'];
  readonly pageSizeOptions = [10, 25, 50];
  readonly roles = ASSIGNABLE_ROLES;
  readonly roleLabels = ROLE_LABELS;
  readonly invitationStatusLabels = INVITATION_STATUS_LABELS;

  readonly dataSource = new MatTableDataSource<User>([]);
  readonly searchControl = new FormControl<string>('', { nonNullable: true });

  state: ViewState | null = 'loading';
  invitationsState: ViewState | null = 'loading';
  invitations: Invitation[] = [];
  actionError: string | null = null;

  private readonly destroy$ = new Subject<void>();
  private currentUserId: number | null = null;

  /**
   * Via setter, and not via `AfterViewInit`: the table lives inside a state `*ngIf`, so the
   * paginator only exists once the load has finished — `AfterViewInit` would arrive too
   * early and find `undefined`.
   */
  @ViewChild(MatPaginator)
  set paginator(paginator: MatPaginator | undefined) {
    if (paginator) {
      this.dataSource.paginator = paginator;
    }
  }

  @ViewChild(MatSort)
  set sort(sort: MatSort | undefined) {
    if (sort) {
      this.dataSource.sort = sort;
    }
  }

  constructor(
    private readonly userService: UserService,
    private readonly invitationService: InvitationService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
  ) {}

  get pendingInvitations(): Invitation[] {
    return this.invitations.filter((invitation) => invitation.status === 'PENDING');
  }

  ngOnInit(): void {
    this.currentUserId = this.authService.currentUser?.id ?? null;

    this.dataSource.filterPredicate = (user, filter) =>
      `${user.name} ${user.email} ${this.roleLabels[user.role]}`.toLowerCase().includes(filter);

    this.dataSource.sortingDataAccessor = (user, property): string | number => {
      switch (property) {
        case 'active':
          return user.active ? 1 : 0;
        case 'role':
          return this.roleLabels[user.role];
        case 'lastLoginAt':
          // Never logged in goes to the end of the ascending order, not to the start.
          return user.lastLoginAt ?? '';
        case 'email':
          return user.email;
        default:
          return user.name;
      }
    };

    this.searchControl.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe((term) => this.applyFilter(term));

    this.load();
    this.loadInvitations();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.userService
      .list()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (users) => {
          this.dataSource.data = users;
          this.state = users.length === 0 ? 'empty' : null;
        },
        error: () => {
          this.dataSource.data = [];
          this.state = 'error';
        },
      });
  }

  loadInvitations(): void {
    this.invitationsState = 'loading';
    this.invitationService
      .list()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (invitations) => {
          this.invitations = invitations;
          this.invitationsState = this.pendingInvitations.length === 0 ? 'empty' : null;
        },
        error: () => {
          this.invitations = [];
          this.invitationsState = 'error';
        },
      });
  }

  /** Your own row is never editable: the backend guards would refuse it anyway. */
  isSelf(user: User): boolean {
    return this.currentUserId !== null && user.id === this.currentUserId;
  }

  selfHint(user: User): string {
    return this.isSelf(user) ? 'Você não pode alterar a própria conta por aqui' : '';
  }

  roleLabel(role: Role): string {
    return this.roleLabels[role];
  }

  trackById(_index: number, user: User): number {
    return user.id;
  }

  trackInvitationById(_index: number, invitation: Invitation): number {
    return invitation.id;
  }

  onActiveChange(user: User, event: MatSlideToggleChange): void {
    const desired = event.checked;
    const previous = user.active;

    this.confirm({
      title: desired ? 'Reativar usuário' : 'Desativar usuário',
      message: desired
        ? `Reativar "${user.name}"? A pessoa volta a conseguir entrar na plataforma.`
        : `Desativar "${user.name}"? As sessões abertas dessa conta serão encerradas.`,
      confirmLabel: desired ? 'Reativar' : 'Desativar',
      destructive: !desired,
    }).subscribe((confirmed) => {
      if (!confirmed) {
        this.revertToggle(event, previous);
        return;
      }

      this.actionError = null;
      this.userService.changeActive(user.id, desired).subscribe({
        next: (updated) => {
          this.replace(updated);
          this.notifications.success(updated.active ? 'Usuário reativado.' : 'Usuário desativado.');
        },
        error: (error: unknown) => {
          // 409 from the last active administrator or from self-deactivation. The control
          // is optimistic: without undoing the click, the screen would go on showing a state
          // the server refused, and an F5 would contradict it.
          this.revertToggle(event, previous);
          this.actionError =
            toApiError(error)?.message ?? 'Não foi possível alterar a situação do usuário.';
        },
      });
    });
  }

  onRoleChange(user: User, event: MatSelectChange): void {
    const desired = event.value as Role;
    const previous = user.role;
    if (desired === previous) {
      return;
    }

    this.confirm({
      title: 'Alterar papel',
      message: `Alterar o papel de "${user.name}" para ${this.roleLabel(
        desired,
      )}? As sessões abertas dessa conta serão encerradas.`,
      confirmLabel: 'Alterar',
    }).subscribe((confirmed) => {
      if (!confirmed) {
        this.revertSelect(event, previous);
        return;
      }

      this.actionError = null;
      this.userService.changeRole(user.id, desired).subscribe({
        next: (updated) => {
          this.replace(updated);
          this.notifications.success('Papel alterado.');
        },
        error: (error: unknown) => {
          this.revertSelect(event, previous);
          this.actionError = toApiError(error)?.message ?? 'Não foi possível alterar o papel.';
        },
      });
    });
  }

  revokeInvitation(invitation: Invitation): void {
    this.confirm({
      title: 'Revogar convite',
      message: `Revogar o convite de "${invitation.email}"? O link enviado deixa de funcionar.`,
      confirmLabel: 'Revogar',
      destructive: true,
    }).subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }

      this.actionError = null;
      this.invitationService.revoke(invitation.id).subscribe({
        next: () => {
          this.notifications.success('Convite revogado.');
          this.loadInvitations();
        },
        error: (error: unknown) => {
          this.actionError = toApiError(error)?.message ?? 'Não foi possível revogar o convite.';
        },
      });
    });
  }

  private applyFilter(term: string): void {
    this.dataSource.filter = term.trim().toLowerCase();
    if (this.dataSource.paginator) {
      this.dataSource.paginator.firstPage();
    }
  }

  private confirm(data: ConfirmDialogData): Observable<boolean | undefined> {
    return this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(takeUntil(this.destroy$));
  }

  /** Undoes the click on the widget itself: the source of truth is still the server. */
  private revertToggle(event: MatSlideToggleChange, previous: boolean): void {
    event.source.checked = previous;
  }

  private revertSelect(event: MatSelectChange, previous: Role): void {
    event.source.value = previous;
  }

  private replace(updated: User): void {
    this.dataSource.data = this.dataSource.data.map((user) =>
      user.id === updated.id ? updated : user,
    );
  }
}
