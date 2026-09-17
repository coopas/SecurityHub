import { UserSummary } from './vulnerability.model';

/**
 * Anexo de uma vulnerabilidade, campo a campo como o `AttachmentResponse` do backend.
 * O backend omite campos nulos (`default-property-inclusion: non_null`), daí o opcional
 * em `uploadedBy`; `sizeBytes` e `canDelete` são primitivos no Java e vêm sempre.
 */
export interface Attachment {
  id: number;
  vulnerabilityId: number;
  /** Nome saneado do envio; o nome em disco nunca é exposto. */
  filename: string;
  /** Determinado pelo servidor a partir dos bytes, nunca pelo cabeçalho enviado. */
  contentType: string;
  sizeBytes: number;
  checksumSha256: string;
  uploadedBy?: UserSummary;
  /**
   * Calculado no servidor (quem enviou ou um ADMIN), como o `editable` do comentário. A
   * tela usa este campo em vez de refazer a regra, para que as duas nunca divirjam.
   */
  canDelete: boolean;
  createdAt: string;
}

/**
 * Espelha a allowlist do `AttachmentContentTypeDetector`. Serve ao atributo `accept` do
 * seletor de arquivos e à checagem imediata antes do envio — mas quem decide o tipo é o
 * servidor, olhando os bytes: este arranjo economiza um upload inútil, não protege nada.
 */
export const ALLOWED_ATTACHMENT_TYPES: readonly string[] = [
  'application/pdf',
  'image/png',
  'image/jpeg',
  'text/plain',
];

/** `securityhub.attachments.max-size-bytes`: 10 MiB. */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

/** `AttachmentService.MAX_ATTACHMENTS`: o servidor responde 409 a partir daqui. */
export const MAX_ATTACHMENTS = 20;
