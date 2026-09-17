import { HttpClient, HttpEvent, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { Attachment } from '../models/attachment.model';

/**
 * Anexos vivem sob a vulnerabilidade: nenhuma operação existe sem o id do pai.
 *
 * A listagem devolve um array puro, e não o envelope paginado: o servidor limita a
 * `MAX_ATTACHMENTS` por vulnerabilidade, então nunca há uma segunda página.
 */
@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly baseUrl = `${environment.apiUrl}/vulnerabilities`;

  constructor(private readonly http: HttpClient) {}

  list(vulnerabilityId: number): Observable<Attachment[]> {
    return this.http.get<Attachment[]>(this.attachmentsUrl(vulnerabilityId));
  }

  /**
   * Envia o arquivo como `multipart/form-data`, na parte `file` que o backend espera.
   *
   * Nenhum `Content-Type` é definido aqui, e isso é deliberado: quem monta esse
   * cabeçalho é o navegador, porque só ele conhece o boundary que separa as partes.
   * Escrevê-lo à mão produz um `multipart/form-data` sem boundary, o servidor não
   * consegue separar parte alguma e responde 400 ou 415 — um erro que parece defeito do
   * backend e cuja causa está inteiramente nesta linha.
   *
   * `observe: 'events'` com `reportProgress: true` devolve os eventos de progresso do
   * upload, que é o que alimenta a barra determinada da tela; o último evento é a
   * resposta com o anexo criado.
   */
  upload(vulnerabilityId: number, file: File): Observable<HttpEvent<Attachment>> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.http.post<Attachment>(this.attachmentsUrl(vulnerabilityId), formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  /**
   * `observe: 'response'` não é preferência: o nome do arquivo vem no
   * `Content-Disposition`, e só a resposta inteira dá acesso aos cabeçalhos.
   */
  download(vulnerabilityId: number, attachmentId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.attachmentsUrl(vulnerabilityId)}/${attachmentId}/download`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  delete(vulnerabilityId: number, attachmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.attachmentsUrl(vulnerabilityId)}/${attachmentId}`);
  }

  private attachmentsUrl(vulnerabilityId: number): string {
    return `${this.baseUrl}/${vulnerabilityId}/attachments`;
  }
}
