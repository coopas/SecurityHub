import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import {
  ScanFinding,
  ScanFormat,
  ScanImport,
  ScanImportQuery,
  ScanImportSummary,
} from '../models/scan-import.model';

/**
 * Importação de relatórios de varredura. O recurso é uma área de espera: o envio cria
 * uma importação `PENDING` com os achados já normalizados, a revisão acontece na tela de
 * prévia e só o `confirm` transforma os achados em vulnerabilidades.
 *
 * A listagem devolve resumos paginados; o detalhe é o único lugar que traz os achados,
 * porque uma varredura grande não cabe numa linha de histórico.
 */
@Injectable({ providedIn: 'root' })
export class ImportService {
  private readonly baseUrl = `${environment.apiUrl}/scan-imports`;

  constructor(private readonly http: HttpClient) {}

  /** Histórico paginado; `page`, `size` e `sort` vêm dos query params da tela. */
  list(query: ScanImportQuery): Observable<PageResponse<ScanImportSummary>> {
    const params = new HttpParams()
      .set('page', String(query.page))
      .set('size', String(query.size))
      .set('sort', query.sort);

    return this.http.get<PageResponse<ScanImportSummary>>(this.baseUrl, { params });
  }

  get(id: number): Observable<ScanImport> {
    return this.http.get<ScanImport>(`${this.baseUrl}/${id}`);
  }

  /**
   * Envia o relatório como `multipart/form-data`, nas partes `file`, `projectId` e
   * `format` que o backend espera.
   *
   * Nenhum `Content-Type` é definido aqui, e isso é deliberado: quem monta esse
   * cabeçalho é o navegador, porque só ele conhece o boundary que separa as partes.
   * Escrevê-lo à mão produz um `multipart/form-data` sem boundary, o servidor não
   * consegue separar parte alguma e responde 400 ou 415 — um erro que parece defeito do
   * backend e cuja causa está inteiramente nesta linha.
   *
   * `observe: 'events'` com `reportProgress: true` devolve os eventos de progresso do
   * upload, que é o que alimenta a barra determinada da tela; o último evento é a
   * resposta com a importação criada.
   */
  upload(projectId: number, format: ScanFormat, file: File): Observable<HttpEvent<ScanImport>> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    formData.append('projectId', String(projectId));
    formData.append('format', format);

    return this.http.post<ScanImport>(this.baseUrl, formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  /**
   * Vincula um achado sem ativo ao ativo escolhido. A resposta é o achado inteiro já
   * reclassificado pelo servidor — que pode devolver `DUPLICATE` em vez de `MATCHED` —,
   * então a linha é substituída pelo que volta, e não pelo que a tela supôs.
   */
  mapFinding(id: number, findingId: number, assetId: number): Observable<ScanFinding> {
    return this.http.patch<ScanFinding>(`${this.baseUrl}/${id}/findings/${findingId}`, { assetId });
  }

  /** Cria as vulnerabilidades dos achados revisados; recusa com `CONFLICT` se repetido. */
  confirm(id: number): Observable<ScanImport> {
    return this.http.post<ScanImport>(`${this.baseUrl}/${id}/confirm`, {});
  }

  /** Descarta a importação inteira. Nada foi criado ainda, então não há o que desfazer. */
  discard(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
