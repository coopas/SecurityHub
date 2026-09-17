import { NgModule } from '@angular/core';

import { SharedModule } from '../../shared/shared.module';
import { AuditDiffComponent } from './audit-diff/audit-diff.component';
import { AuditListComponent } from './audit-list/audit-list.component';
import { AuditRoutingModule } from './audit-routing.module';

@NgModule({
  declarations: [AuditListComponent, AuditDiffComponent],
  imports: [SharedModule, AuditRoutingModule],
})
export class AuditModule {}
