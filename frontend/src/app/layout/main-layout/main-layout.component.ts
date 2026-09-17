import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, takeUntil } from 'rxjs';

import { ROLE_LABELS, Role, User } from '../../core/models';
import { AuthService } from '../../core/services/auth.service';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Vazio significa visível para qualquer usuário autenticado. */
  roles: Role[];
}

/** Abaixo deste ponto a navegação vira gaveta sobreposta com botão hambúrguer. */
export const HANDSET_QUERY = '(max-width: 959.98px)';

@Component({
  selector: 'app-main-layout',
  templateUrl: './main-layout.component.html',
  styleUrls: ['./main-layout.component.scss'],
})
export class MainLayoutComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();

  readonly currentUser$: Observable<User | null> = this.authService.currentUser$;
  readonly roleLabels = ROLE_LABELS;

  readonly navItems: NavItem[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/dashboard', roles: [] },
    { label: 'Projetos', icon: 'folder', route: '/projects', roles: [] },
    { label: 'Ativos', icon: 'dns', route: '/assets', roles: [] },
    { label: 'Vulnerabilidades', icon: 'bug_report', route: '/vulnerabilities', roles: [] },
    { label: 'Usuários', icon: 'group', route: '/users', roles: ['ADMIN'] },
    { label: 'Auditoria', icon: 'history', route: '/audit', roles: ['ADMIN'] },
  ];

  isHandset = false;

  constructor(
    private readonly authService: AuthService,
    private readonly breakpointObserver: BreakpointObserver,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.breakpointObserver
      .observe(HANDSET_QUERY)
      .pipe(takeUntil(this.destroy$))
      .subscribe((result) => (this.isHandset = result.matches));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  visibleNavItems(user: User | null): NavItem[] {
    if (!user) {
      return [];
    }
    return this.navItems.filter((item) => item.roles.length === 0 || item.roles.includes(user.role));
  }

  roleLabel(role: Role): string {
    return this.roleLabels[role];
  }

  /**
   * O observable de logout precisa ser assinado: a limpeza local é síncrona, mas a revogação
   * no servidor viaja nele, e um observable frio nunca dispara sem assinante. Sem isso a
   * família de refresh token sobreviveria no servidor até expirar.
   *
   * A navegação acontece nos dois desfechos, porque a sessão local já foi encerrada de
   * qualquer forma e prender o usuário na tela por um erro de rede não ajudaria ninguém.
   */
  logout(): void {
    this.authService.logout().subscribe({
      next: () => void this.router.navigate(['/login']),
      error: () => void this.router.navigate(['/login']),
    });
  }
}
