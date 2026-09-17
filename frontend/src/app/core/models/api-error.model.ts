export type ApiErrorCode =
  | 'VALIDATION_ERROR'
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'PAYLOAD_TOO_LARGE'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'INTERNAL_ERROR';

export interface FieldError {
  field: string;
  message: string;
}

/** Envelope padronizado de erro do backend. */
export interface ApiError {
  timestamp: string;
  status: number;
  code: ApiErrorCode;
  message: string;
  path: string;
  fieldErrors?: FieldError[];
  traceId: string;
}
