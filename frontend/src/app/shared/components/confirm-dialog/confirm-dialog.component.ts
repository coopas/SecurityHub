import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Destaca a ação como destrutiva (exclusões). */
  destructive?: boolean;
}

/** Confirmação reutilizável, obrigatória antes de qualquer ação destrutiva. */
@Component({
  selector: 'app-confirm-dialog',
  templateUrl: './confirm-dialog.component.html',
  styleUrls: ['./confirm-dialog.component.scss'],
})
export class ConfirmDialogComponent {
  constructor(
    private readonly dialogRef: MatDialogRef<ConfirmDialogComponent, boolean>,
    @Inject(MAT_DIALOG_DATA) readonly data: ConfirmDialogData,
  ) {}

  get confirmLabel(): string {
    return this.data.confirmLabel ?? 'Confirmar';
  }

  get cancelLabel(): string {
    return this.data.cancelLabel ?? 'Cancelar';
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
