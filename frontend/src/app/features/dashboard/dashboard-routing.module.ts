import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { DashboardPageComponent } from './dashboard-page/dashboard-page.component';

/** Rota única: os quatro agregados do dashboard são abertos a todos os papéis. */
const routes: Routes = [{ path: '', component: DashboardPageComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class DashboardRoutingModule {}
