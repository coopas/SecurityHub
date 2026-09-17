import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { SharedModule } from '../../shared.module';
import { ConfirmDialogComponent, ConfirmDialogData } from './confirm-dialog.component';

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let component: ConfirmDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<ConfirmDialogComponent, boolean>>;

  const data: ConfirmDialogData = {
    title: 'Excluir projeto',
    message: 'Esta ação não pode ser desfeita.',
    confirmLabel: 'Excluir',
    destructive: true,
  };

  beforeEach(() => {
    dialogRef = jasmine.createSpyObj<MatDialogRef<ConfirmDialogComponent, boolean>>('MatDialogRef', ['close']);

    TestBed.configureTestingModule({
      imports: [SharedModule, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    });

    fixture = TestBed.createComponent(ConfirmDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('exibe título, mensagem e rótulos', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Excluir projeto');
    expect(text).toContain('Esta ação não pode ser desfeita.');
    expect(component.confirmLabel).toBe('Excluir');
    expect(component.cancelLabel).toBe('Cancelar');
  });

  it('fecha com true ao confirmar e false ao cancelar', () => {
    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith(true);

    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });
});
