import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from '../../../core/models';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';
import { passwordsMatchValidator } from '../utils/password-match.validator';

@Component({
  selector: 'app-reset-password',
  templateUrl: './reset-password.component.html',
  styleUrls: ['../auth.scss'],
})
export class ResetPasswordComponent implements OnInit {
  readonly minLength = PASSWORD_MIN_LENGTH;
  readonly maxLength = PASSWORD_MAX_LENGTH;

  /**
   * The limits are the ones from `PasswordResetConfirmRequest`. Repeated here on purpose:
   * the screen avoids a round trip to the server just to state the obvious, and the server
   * is still the one that decides — a tampered form hits the same rule over there.
   */
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

  token = '';
  submitting = false;
  generalError: string | null = null;
  hidePassword = true;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  get hasToken(): boolean {
    return this.token.length > 0;
  }

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';

    if (this.hasToken) {
      // The token is a single-use credential: leaving it in the address bar would spread
      // it through the history, the bookmarks and the Referer header of any external
      // resource the page happened to load. `replaceUrl` also wipes the history entry
      // that already held it.
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: {},
        replaceUrl: true,
      });
    }
  }

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting || !this.hasToken) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.authService
      .confirmPasswordReset({
        token: this.token,
        password: String(this.form.value.password ?? ''),
      })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: () => {
          // The confirmation does not open a session on the backend: logging in is the next step.
          this.notifications.success('Senha redefinida. Entre com a senha nova.');
          void this.router.navigateByUrl('/login');
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
        : apiError?.message ?? 'Não foi possível redefinir a senha. Solicite um link novo.';
  }
}
