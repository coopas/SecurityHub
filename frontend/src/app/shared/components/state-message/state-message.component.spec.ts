import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { SharedModule } from '../../shared.module';
import { StateMessageComponent } from './state-message.component';

describe('StateMessageComponent', () => {
  let fixture: ComponentFixture<StateMessageComponent>;
  let component: StateMessageComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SharedModule, NoopAnimationsModule] });
    fixture = TestBed.createComponent(StateMessageComponent);
    component = fixture.componentInstance;
  });

  it('anuncia mudanças de estado para leitores de tela', () => {
    component.state = 'loading';
    fixture.detectChanges();

    const region = (fixture.nativeElement as HTMLElement).querySelector('.sh-state');
    expect(region?.getAttribute('role')).toBe('status');
    expect(region?.getAttribute('aria-live')).toBe('polite');
  });

  it('usa mensagens padrão por estado', () => {
    component.state = 'empty';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nenhum registro encontrado.');

    component.state = 'error';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Não foi possível carregar os dados.',
    );
  });

  it('emite retry apenas no estado de erro', () => {
    const retried = jasmine.createSpy('retry');
    component.retry.subscribe(retried);
    component.showRetry = true;
    component.state = 'empty';
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('button')).toBeNull();

    component.state = 'error';
    fixture.detectChanges();
    element.querySelector('button')?.click();

    expect(retried).toHaveBeenCalled();
  });
});
