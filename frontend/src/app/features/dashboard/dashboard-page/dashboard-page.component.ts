import { Component } from '@angular/core';

/**
 * Página do dashboard: só o cabeçalho e o arranjo das regiões.
 *
 * Cada região carrega os próprios dados e trata o próprio erro. Não há um `forkJoin` de
 * todas as chamadas nem um estado único de tela: uma tendência que falha não pode apagar
 * os cards, e cada painel tem seu próprio "tentar novamente".
 */
@Component({
  selector: 'app-dashboard-page',
  templateUrl: './dashboard-page.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class DashboardPageComponent {}
