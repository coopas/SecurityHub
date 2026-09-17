import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormGroup } from '@angular/forms';

import { ApiError } from '../models';

/** Extracts the backend's standardized error envelope, when present. */
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
 * Moves the `fieldErrors` returned by the API onto the matching controls, so that
 * they show up inline in the form. Returns the errors with no matching control,
 * which the screen should display as a general message.
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
