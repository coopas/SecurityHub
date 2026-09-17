import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { applyFieldErrors, toApiError } from '../../../core/utils/api-error.util';

@Component({
  selector: 'app-register',
  templateUrl: './register.component.html',
  styleUrls: ['../auth.scss'],
})
export class RegisterComponent {
  readonly form: FormGroup = this.formBuilder.group({
    companyName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(180)]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(100)]],
  });

  submitting = false;
  generalError: string | null = null;
  hidePassword = true;

  constructor(
    private readonly formBuilder: FormBuilder,
    private readonly authService: AuthService,
    private readonly notifications: NotificationService,
    private readonly router: Router,
  ) {}

  submit(): void {
    this.generalError = null;

    if (this.form.invalid || this.submitting) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.authService
      .register({
        companyName: String(this.form.value.companyName ?? '').trim(),
        name: String(this.form.value.name ?? '').trim(),
        email: String(this.form.value.email ?? '').trim(),
        password: String(this.form.value.password ?? ''),
      })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: () => {
          this.notifications.success('Empresa cadastrada com sucesso.');
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
        : apiError?.message ?? 'Não foi possível concluir o cadastro. Tente novamente.';
  }
}
