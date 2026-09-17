import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';

/**
 * O relatório executivo em PDF. Mora no dashboard, e não em uma funcionalidade
 * `reports` própria, porque é um botão: os números do relatório são as mesmas agregações
 * que esta tela já mostra, e um módulo com rota, roteamento e carregamento tardio para
 * uma única chamada seria estrutura sem conteúdo. No dia em que existir uma tela de
 * relatórios — com escolha de período, histórico ou formatos — ela leva este serviço.
 */
@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly baseUrl = `${environment.apiUrl}/reports`;

  constructor(private readonly http: HttpClient) {}

  /**
   * Sem parâmetros de propósito: o endpoint não aceita intervalo de datas, e todos os
   * números são do momento da geração.
   *
   * `observe: 'response'` não é preferência: o nome do arquivo vem no
   * `Content-Disposition`, e só a resposta inteira dá acesso aos cabeçalhos.
   */
  executive(): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/executive`, {
      responseType: 'blob',
      observe: 'response',
    });
  }
}
