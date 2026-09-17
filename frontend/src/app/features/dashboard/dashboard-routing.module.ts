import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { DashboardPageComponent } from './dashboard-page/dashboard-page.component';

/** Single route: the dashboard's four aggregates are open to every role. */
const routes: Routes = [{ path: '', component: DashboardPageComponent }];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class DashboardRoutingModule {}
