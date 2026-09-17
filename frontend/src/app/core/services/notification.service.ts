import { Injectable } from '@angular/core';
import { MatSnackBar, MatSnackBarConfig } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  constructor(private readonly snackBar: MatSnackBar) {}

  success(message: string): void {
    this.snackBar.open(message, 'Fechar', this.config('sh-snack-success', 4000, 'polite'));
  }

  error(message: string): void {
    this.snackBar.open(message, 'Fechar', this.config('sh-snack-error', 7000, 'assertive'));
  }

  private config(
    panelClass: string,
    duration: number,
    politeness: MatSnackBarConfig['politeness'],
  ): MatSnackBarConfig {
    return {
      duration,
      panelClass: [panelClass],
      politeness,
      horizontalPosition: 'center',
      verticalPosition: 'bottom',
    };
  }
}
