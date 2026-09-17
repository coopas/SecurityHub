import { Component, OnDestroy, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { toApiError } from '../../../core/utils/api-error.util';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { PROJECT_STATUS_LABELS, Project } from '../models/project.model';
import { ProjectService } from '../services/project.service';

@Component({
  selector: 'app-project-detail',
  templateUrl: './project-detail.component.html',
  styleUrls: ['../projects.scss'],
})
export class ProjectDetailComponent implements OnInit, OnDestroy {
  readonly statusLabels = PROJECT_STATUS_LABELS;

  project: Project | null = null;
  state: ViewState | null = 'loading';
  errorMessage: string | null = null;
  actionError: string | null = null;
  isAdmin = false;
  deleting = false;

  private projectId: number | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly projectService: ProjectService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly dialog: MatDialog,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get notFound(): boolean {
    return this.errorMessage === 'Projeto não encontrado.';
  }

  ngOnInit(): void {
    this.isAdmin = this.authService.hasRole('ADMIN');

    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isInteger(id) || id <= 0) {
      this.state = 'error';
      this.errorMessage = 'Projeto não encontrado.';
      return;
    }

    this.projectId = id;
    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    if (this.projectId === null) {
      return;
    }

    this.state = 'loading';
    this.errorMessage = null;
    this.projectService.get(this.projectId).subscribe({
      next: (project) => {
        this.project = project;
        this.state = null;
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.project = null;
        this.state = 'error';
        this.errorMessage =
          apiError?.code === 'NOT_FOUND'
            ? 'Projeto não encontrado.'
            : apiError?.message ?? 'Não foi possível carregar o projeto.';
      },
    });
  }

  confirmDelete(): void {
    const project = this.project;
    if (!project) {
      return;
    }

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
          this.delete(project.id);
        }
      });
  }

  private delete(id: number): void {
    this.actionError = null;
    this.deleting = true;
    this.projectService.delete(id).subscribe({
      next: () => {
        this.deleting = false;
        this.notifications.success('Projeto excluído.');
        void this.router.navigate(['/projects']);
      },
      error: (error: unknown) => {
        this.deleting = false;
        const apiError = toApiError(error);
        this.actionError = apiError?.message ?? 'Não foi possível excluir o projeto.';
      },
    });
  }
}
