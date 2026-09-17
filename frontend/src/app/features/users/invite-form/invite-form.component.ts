import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';

import { ROLE_LABELS, Role } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { ASSIGNABLE_ROLES } from '../models/user-admin.model';
import { InvitationService } from '../services/invitation.service';

/**
 * User invitation. The company does not appear in the form because it never travels in the
 * body: the backend uses the one from the authenticated administrator, and a field here
 * would only create the illusion that you can invite someone to somewhere else.
 */
@Component({
  selector: 'app-invite-form',
  templateUrl: './invite-form.component.html',
  styleUrls: ['../users.scss'],
})
export class InviteFormComponent {
  readonly roles = ASSIGNABLE_ROLES;
  readonly roleLabels = ROLE_LABELS;

  readonly form: FormGroup = this.formBuilder.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(180)]],
    role: ['VIEWER' as Role, [Validators.required]],
  });

  submitting = false;
  generalError: string | null = null;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly invitationService: InvitationService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
  ) {}

  roleLabel(role: Role): string {
    return this.roleLabels[role];
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.invitationService
      .create({
        name: String(this.form.value.name ?? '').trim(),
        email: String(this.form.value.email ?? '').trim(),
        role: this.form.value.role as Role,
      })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: (invitation) => {
          this.notifications.success(`Convite enviado para ${invitation.email}.`);
          void this.router.navigateByUrl('/users');
        },
        error: (error: unknown) => this.handleError(error),
      });
  }

  cancel(): void {
    void this.router.navigateByUrl('/users');
  }

  private handleError(error: unknown): void {
    const apiError = toApiError(error);
    const unmatched = applyFieldErrors(this.form, apiError);
    this.generalError =
      unmatched.length > 0
        ? unmatched.join(' ')
        : apiError?.message ?? 'Não foi possível enviar o convite. Tente novamente.';
  }
}
