import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, finalize, forkJoin, of } from 'rxjs';

import { PageResponse } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { Project } from '../../projects/models/project.model';
import { ProjectService } from '../../projects/services/project.service';
import {
  ASSET_TYPES,
  ASSET_TYPE_ICONS,
  ASSET_TYPE_LABELS,
  Asset,
  AssetRequest,
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

/** Creation and editing share the form; the route defines the mode. */
@Component({
  selector: 'app-asset-form',
  templateUrl: './asset-form.component.html',
  styleUrls: ['../assets.scss'],
})
export class AssetFormComponent implements OnInit {
  readonly types = ASSET_TYPES;
  readonly environments = ENVIRONMENTS;
  readonly criticalities = CRITICALITIES;
  readonly typeLabels = ASSET_TYPE_LABELS;
  readonly typeIcons = ASSET_TYPE_ICONS;
  readonly environmentLabels = ENVIRONMENT_LABELS;
  readonly environmentIcons = ENVIRONMENT_ICONS;
  readonly criticalityLabels = CRITICALITY_LABELS;
  readonly criticalityIcons = CRITICALITY_ICONS;

  readonly form: FormGroup = this.formBuilder.group({
    projectId: [null as number | null, [Validators.required]],
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(140)]],
    type: ['API' as AssetType, [Validators.required]],
    identifier: ['', [Validators.maxLength(255)]],
    environment: ['PRODUCTION' as Environment, [Validators.required]],
    criticality: ['MEDIUM' as Criticality, [Validators.required]],
    description: ['', [Validators.maxLength(2000)]],
  });

  assetId: number | null = null;
  projects: ProjectOption[] = [];
  submitting = false;
  generalError: string | null = null;
  /** `null` when the form can be displayed. */
  loadState: ViewState | null = 'loading';
  loadErrorMessage: string | null = null;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly assetService: AssetService,
    private readonly projectService: ProjectService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get isEdit(): boolean {
    return this.assetId !== null;
  }

  get title(): string {
    return this.isEdit ? 'Editar ativo' : 'Novo ativo';
  }

  get cancelLink(): unknown[] {
    return this.isEdit ? ['/assets', this.assetId] : ['/assets'];
  }

  get notFound(): boolean {
    return this.loadErrorMessage === 'Ativo não encontrado.';
  }

  /** No project, no asset: the backend requires `projectId` and validates it in the tenant. */
  get hasNoProjects(): boolean {
    return this.loadState === null && this.projects.length === 0;
  }

  ngOnInit(): void {
    const rawId = this.route.snapshot.paramMap.get('id');
    if (rawId !== null) {
      const id = Number(rawId);
      if (!Number.isInteger(id) || id <= 0) {
        this.loadState = 'error';
        this.loadErrorMessage = 'Ativo não encontrado.';
        return;
      }
      this.assetId = id;
    }

    this.loadForm();
  }

  /** Loads the project options and, when editing, the asset, in a single wait. */
  loadForm(): void {
    this.loadState = 'loading';
    this.loadErrorMessage = null;

    const projects$: Observable<PageResponse<Project>> = this.projectService.list({
      page: 0,
      size: PROJECT_OPTIONS_PAGE_SIZE,
      sort: 'name,asc',
    });
    const asset$: Observable<Asset | null> =
      this.assetId === null ? of(null) : this.assetService.get(this.assetId);

    forkJoin({ projects: projects$, asset: asset$ }).subscribe({
      next: ({ projects, asset }) => {
        this.projects = projects.content.map((project) => ({
          id: project.id,
          name: project.name,
        }));
        if (asset) {
          this.ensureProjectOption(asset);
          this.patchForm(asset);
        }
        this.loadState = null;
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        this.loadState = 'error';
        this.loadErrorMessage =
          apiError?.code === 'NOT_FOUND'
            ? 'Ativo não encontrado.'
            : apiError?.message ?? 'Não foi possível carregar o formulário.';
      },
    });
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
      this.assetId === null
        ? this.assetService.create(request)
        : this.assetService.update(this.assetId, request);

    call.pipe(finalize(() => (this.submitting = false))).subscribe({
      next: (asset) => {
        this.notifications.success(this.isEdit ? 'Ativo atualizado.' : 'Ativo criado.');
        void this.router.navigate(['/assets', asset.id]);
      },
      error: (error: unknown) => this.handleError(error),
    });
  }

  private buildRequest(): AssetRequest {
    const description = String(this.form.value.description ?? '').trim();
    const identifier = String(this.form.value.identifier ?? '').trim();
    return {
      projectId: Number(this.form.value.projectId),
      name: String(this.form.value.name ?? '').trim(),
      description: description || undefined,
      type: this.form.value.type as AssetType,
      identifier: identifier || undefined,
      environment: this.form.value.environment as Environment,
      criticality: this.form.value.criticality as Criticality,
    };
  }

  private patchForm(asset: Asset): void {
    this.form.patchValue({
      projectId: asset.projectId,
      name: asset.name,
      type: asset.type,
      identifier: asset.identifier ?? '',
      environment: asset.environment,
      criticality: asset.criticality,
      description: asset.description ?? '',
    });
  }

  /**
   * With more projects than fit in one page, the project of the asset being edited can
   * fall outside the options; without this the selector would open empty and the edit
   * would lose the link.
   */
  private ensureProjectOption(asset: Asset): void {
    if (!this.projects.some((project) => project.id === asset.projectId)) {
      this.projects = [{ id: asset.projectId, name: asset.projectName }, ...this.projects];
    }
  }

  /**
   * `fieldErrors` turn into inline errors on the controls; a general message is only left
   * over when no field of the form matches (or when the error is not a validation one).
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
      : apiError?.message ?? 'Não foi possível salvar o ativo. Tente novamente.';
  }
}
