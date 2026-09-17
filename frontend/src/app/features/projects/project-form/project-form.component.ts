import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  PROJECT_STATUSES,
  PROJECT_STATUS_LABELS,
  Project,
  ProjectRequest,
  ProjectStatus,
} from '../models/project.model';
import { ProjectService } from '../services/project.service';

/** Criação e edição compartilham o formulário; a rota define o modo. */
@Component({
  selector: 'app-project-form',
  templateUrl: './project-form.component.html',
  styleUrls: ['../projects.scss'],
})
export class ProjectFormComponent implements OnInit {
  readonly statuses = PROJECT_STATUSES;
  readonly statusLabels = PROJECT_STATUS_LABELS;

  readonly form: FormGroup = this.formBuilder.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(140)]],
    description: ['', [Validators.maxLength(2000)]],
    status: ['ACTIVE' as ProjectStatus, [Validators.required]],
  });

  projectId: number | null = null;
  submitting = false;
  generalError: string | null = null;
  /** `null` quando o formulário pode ser exibido. */
  loadState: ViewState | null = null;
  loadErrorMessage: string | null = null;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly projectService: ProjectService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get isEdit(): boolean {
    return this.projectId !== null;
  }

  get title(): string {
    return this.isEdit ? 'Editar projeto' : 'Novo projeto';
  }

  get cancelLink(): unknown[] {
    return this.isEdit ? ['/projects', this.projectId] : ['/projects'];
  }

  ngOnInit(): void {
    const rawId = this.route.snapshot.paramMap.get('id');
    if (rawId === null) {
      return;
    }

    const id = Number(rawId);
    if (!Number.isInteger(id) || id <= 0) {
      this.loadState = 'error';
      this.loadErrorMessage = 'Projeto não encontrado.';
      return;
    }

    this.projectId = id;
    this.loadProject();
  }

  loadProject(): void {
    if (this.projectId === null) {
      return;
    }

    this.loadState = 'loading';
    this.loadErrorMessage = null;
    this.projectService.get(this.projectId).subscribe({
      next: (project) => {
        this.loadState = null;
        this.patchForm(project);
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.loadState = 'error';
        this.loadErrorMessage =
          apiError?.code === 'NOT_FOUND'
            ? 'Projeto não encontrado.'
            : apiError?.message ?? 'Não foi possível carregar o projeto.';
      },
    });
  }

  get notFound(): boolean {
    return this.loadErrorMessage === 'Projeto não encontrado.';
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting) {
      this.form.markAllAsTouched();
      return;
    }

    const request = this.buildRequest();
    this.submitting = true;

    const call =
      this.projectId === null
        ? this.projectService.create(request)
        : this.projectService.update(this.projectId, request);

    call.pipe(finalize(() => (this.submitting = false))).subscribe({
      next: (project) => {
        this.notifications.success(this.isEdit ? 'Projeto atualizado.' : 'Projeto criado.');
        void this.router.navigate(['/projects', project.id]);
      },
      error: (error: unknown) => this.handleError(error),
    });
  }

  private buildRequest(): ProjectRequest {
    const description = String(this.form.value.description ?? '').trim();
    return {
      name: String(this.form.value.name ?? '').trim(),
      description: description || undefined,
      status: this.form.value.status as ProjectStatus,
    };
  }

  private patchForm(project: Project): void {
    this.form.patchValue({
      name: project.name,
      description: project.description ?? '',
      status: project.status,
    });
  }

  /**
   * `fieldErrors` viram erros inline dos controles; só sobra mensagem geral quando
   * nenhum campo do formulário corresponde (ou quando o erro não é de validação).
   */
  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);

    if (unmatched.length > 0) {
      this.generalError = unmatched.join(' ');
      return;
    }

    const handledByControls = (apiError?.fieldErrors?.length ?? 0) > 0;
    this.generalError = handledByControls
      ? null
      : apiError?.message ?? 'Não foi possível salvar o projeto. Tente novamente.';
  }
}
