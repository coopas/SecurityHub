import { HttpClientTestingModule } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { Role } from '../../core/models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
} from '../../core/services/auth.service';
import { makeJwt, makeUser } from '../../core/testing/auth-test-utils';
import { SharedModule } from '../../shared/shared.module';
import { makeAssetPage } from '../assets/testing/asset-test-utils';
import { AssetService } from '../assets/services/asset.service';
import { ProjectService } from '../projects/services/project.service';
import { makeProjectPage } from '../projects/testing/project-test-utils';
import { ImportListComponent } from './import-list/import-list.component';
import { ImportPreviewComponent } from './import-preview/import-preview.component';
import { ImportUploadComponent } from './import-upload/import-upload.component';
import { IMPORTS_ROUTES } from './imports-routing.module';
import { ImportService } from './services/import.service';
import { makeScanImport, makeScanImportPage } from './testing/import-test-utils';

/** Stands in for the neighboring screens (/403 and /login) without dragging their modules in. */
@Component({ selector: 'app-route-stub', template: '' })
class RouteStubComponent {}

describe('IMPORTS_ROUTES', () => {
  let router: Router;

  const configure = (role: Role | null): void => {
    if (role) {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
      localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));
    }

    const importService = jasmine.createSpyObj<ImportService>('ImportService', [
      'list',
      'get',
      'upload',
      'mapFinding',
      'confirm',
      'discard',
    ]);
    importService.list.and.returnValue(of(makeScanImportPage([])));
    importService.get.and.returnValue(of(makeScanImport()));

    const projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['list']);
    projectService.list.and.returnValue(of(makeProjectPage([])));

    const assetService = jasmine.createSpyObj<AssetService>('AssetService', ['list']);
    assetService.list.and.returnValue(of(makeAssetPage([])));

    TestBed.configureTestingModule({
      declarations: [
        ImportListComponent,
        ImportUploadComponent,
        ImportPreviewComponent,
        RouteStubComponent,
      ],
      imports: [
        SharedModule,
        HttpClientTestingModule,
        NoopAnimationsModule,
        RouterTestingModule.withRoutes([
          { path: 'imports', children: IMPORTS_ROUTES },
          { path: '403', component: RouteStubComponent },
          { path: 'login', component: RouteStubComponent },
        ]),
      ],
      providers: [
        { provide: ImportService, useValue: importService },
        { provide: ProjectService, useValue: projectService },
        { provide: AssetService, useValue: assetService },
        { provide: MatDialog, useValue: jasmine.createSpyObj<MatDialog>('MatDialog', ['open']) },
      ],
    });

    router = TestBed.inject(Router);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('abre o histórico para qualquer papel: é leitura', async () => {
    configure('VIEWER');

    await router.navigateByUrl('/imports');

    expect(router.url).toBe('/imports');
  });

  it('resolve novo como rota própria, e não como identificador', async () => {
    configure('ANALYST');

    await router.navigateByUrl('/imports/novo');

    expect(router.url).toBe('/imports/novo');
    expect(router.routerState.snapshot.root.firstChild?.firstChild?.routeConfig?.path).toBe('novo');
  });

  it('abre a prévia por identificador', async () => {
    configure('ANALYST');

    await router.navigateByUrl('/imports/4');

    expect(router.url).toBe('/imports/4');
    expect(router.routerState.snapshot.root.firstChild?.firstChild?.routeConfig?.path).toBe(':id');
  });

  it('desvia do envio os papéis que não criam vulnerabilidades', async () => {
    for (const role of ['DEVELOPER', 'VIEWER'] as Role[]) {
      configure(role);

      await router.navigateByUrl('/imports/novo');

      expect(router.url).withContext(`papel ${role}`).toBe('/403');

      localStorage.clear();
      TestBed.resetTestingModule();
    }
  });

  it('manda o visitante anônimo ao login guardando o destino', async () => {
    configure(null);

    await router.navigateByUrl('/imports/novo');

    expect(router.url).toBe('/login?returnUrl=%2Fimports%2Fnovo');
  });
});
