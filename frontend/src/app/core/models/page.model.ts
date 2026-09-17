/** Standardized envelope for paginated lists from the backend. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
}
