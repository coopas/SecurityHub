/** Envelope padronizado de listas paginadas do backend. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
}
