import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, takeUntil } from 'rxjs';

import { ROLE_LABELS, Role, User } from '../../core/models';
import { AuthService } from '../../core/services/auth.service';
import { Theme, ThemeService } from '../../core/services/theme.service';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Empty means visible to any authenticated user. */
  roles: Role[];
}

/** Below this point the navigation turns into an overlay drawer with a hamburger button. */
export const HANDSET_QUERY = '(max-width: 959.98px)';

@Component({
  selector: 'app-main-layout',
  templateUrl: './main-layout.component.html',
  styleUrls: ['./main-layout.component.scss'],
})
export class MainLayoutComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();

  readonly currentUser$: Observable<User | null> = this.authService.currentUser$;
  readonly theme$: Observable<Theme> = this.themeService.theme$;
  readonly roleLabels = ROLE_LABELS;

  readonly navItems: NavItem[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/dashboard', roles: [] },
    { label: 'Projetos', icon: 'folder', route: '/projects', roles: [] },
    { label: 'Ativos', icon: 'dns', route: '/assets', roles: [] },
    { label: 'Vulnerabilidades', icon: 'bug_report', route: '/vulnerabilities', roles: [] },
    { label: 'Importações', icon: 'upload_file', route: '/imports', roles: [] },
    { label: 'Usuários', icon: 'group', route: '/users', roles: ['ADMIN'] },
    { label: 'Auditoria', icon: 'history', route: '/audit', roles: ['ADMIN'] },
  ];

  get currentSection(): string {
    return this.navItems.find((item) => this.router.url.split('?')[0].startsWith(item.route))?.label ?? 'Workspace';
  }

  isHandset = false;

  constructor(
    private readonly authService: AuthService,
    private readonly themeService: ThemeService,
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
   * The logout observable has to be subscribed to: the local cleanup is synchronous, but the
   * revocation on the server travels in it, and a cold observable never fires without a
   * subscriber. Without this the refresh token family would survive on the server until it
   * expired.
   *
   * The navigation happens on both outcomes, because the local session has been ended either
   * way and trapping the user on the screen over a network error would help nobody.
   */
  logout(): void {
    this.authService.logout().subscribe({
      next: () => void this.router.navigate(['/login']),
      error: () => void this.router.navigate(['/login']),
    });
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }
}
