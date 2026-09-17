import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormGroup } from '@angular/forms';

import { ApiError } from '../models';

/** Extrai o envelope de erro padronizado do backend, quando presente. */
export function toApiError(error: unknown): ApiError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = error.error;
  if (body && typeof body === 'object' && 'code' in body && 'message' in body) {
    return body as ApiError;
  }
  return null;
}

/**
 * Move os `fieldErrors` retornados pela API para os controles correspondentes,
 * de modo que apareçam inline no formulário. Retorna os erros sem controle
 * correspondente, que a tela deve exibir como mensagem geral.
 */
export function applyFieldErrors(form: FormGroup, apiError: ApiError | null): string[] {
  const unmatched: string[] = [];
  for (const fieldError of apiError?.fieldErrors ?? []) {
    const control: AbstractControl | null = form.get(fieldError.field);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: fieldError.message });
      control.markAsTouched();
    } else {
      unmatched.push(`${fieldError.field}: ${fieldError.message}`);
    }
  }
  return unmatched;
}
