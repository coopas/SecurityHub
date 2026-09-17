import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH, ROLE_LABELS, Role } from '../../../core/models';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { InvitationPreview } from '../../users/models/invitation.model';
import { InvitationService } from '../../users/services/invitation.service';
import { passwordsMatchValidator } from '../utils/password-match.validator';

@Component({
  selector: 'app-accept-invitation',
  templateUrl: './accept-invitation.component.html',
  styleUrls: ['../auth.scss'],
})
export class AcceptInvitationComponent implements OnInit {
  readonly minLength = PASSWORD_MIN_LENGTH;
  readonly maxLength = PASSWORD_MAX_LENGTH;

  readonly form: FormGroup = this.formBuilder.group(
    {
      password: [
        '',
        [
          Validators.required,
          Validators.minLength(PASSWORD_MIN_LENGTH),
          Validators.maxLength(PASSWORD_MAX_LENGTH),
        ],
      ],
      confirmation: ['', [Validators.required]],
    },
    { validators: passwordsMatchValidator },
  );

  preview: InvitationPreview | null = null;
  loading = true;
  submitting = false;
  previewError: string | null = null;
  generalError: string | null = null;
  hidePassword = true;

  private token = '';

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly invitationService: InvitationService,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';

    if (!this.token) {
      this.loading = false;
      this.previewError = 'Link inválido ou incompleto. Peça um novo convite ao administrador.';
      return;
    }

    // Same reason as the password reset: the invitation token is a single-use credential
    // and must not stay in the history nor travel as a Referer.
    void this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });

    this.invitationService
      .preview(this.token)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (preview) => (this.preview = preview),
        error: (error: unknown) => {
          this.previewError =
            toApiError(error)?.message ?? 'Convite inválido, expirado ou já utilizado.';
        },
      });
  }

  get invitationSummary(): string {
    if (!this.preview) {
      return '';
    }
    return `Você foi convidado para ${this.preview.companyName} como ${this.roleLabel(
      this.preview.role,
    )}`;
  }

  roleLabel(role: Role): string {
    return ROLE_LABELS[role];
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting || !this.preview) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.invitationService
      .accept({ token: this.token, password: String(this.form.value.password ?? '') })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: (response) => {
          // Accepting already returns a ready session: no reason to go through login.
          this.authService.storeSession(response);
          this.notifications.success('Convite aceito. Bem-vindo ao SecurityHub.');
          void this.router.navigateByUrl('/dashboard');
        },
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);
    this.generalError =
      unmatched.length > 0
        ? unmatched.join(' ')
        : apiError?.message ?? 'Não foi possível aceitar o convite. Tente novamente.';
  }
}
