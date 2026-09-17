import { UserSummary } from './vulnerability.model';

/**
 * Attachment of a vulnerability, field by field like the backend's `AttachmentResponse`.
 * The backend omits null fields (`default-property-inclusion: non_null`), hence the
 * optional on `uploadedBy`; `sizeBytes` and `canDelete` are primitives in Java and always
 * come through.
 */
export interface Attachment {
  id: number;
  vulnerabilityId: number;
  /** Sanitised name from the upload; the name on disk is never exposed. */
  filename: string;
  /** Determined by the server from the bytes, never from the header that was sent. */
  contentType: string;
  sizeBytes: number;
  checksumSha256: string;
  uploadedBy?: UserSummary;
  /**
   * Computed on the server (whoever uploaded it, or an ADMIN), like the comment's
   * `editable`. The screen uses this field instead of redoing the rule, so that the two
   * never diverge.
   */
  canDelete: boolean;
  createdAt: string;
}

/**
 * Mirrors the `AttachmentContentTypeDetector` allowlist. It serves the `accept` attribute
 * of the file picker and the immediate check before uploading — but the one who decides
 * the type is the server, looking at the bytes: this arrangement saves a pointless upload,
 * it protects nothing.
 */
export const ALLOWED_ATTACHMENT_TYPES: readonly string[] = [
  'application/pdf',
  'image/png',
  'image/jpeg',
  'text/plain',
];

/** `securityhub.attachments.max-size-bytes`: 10 MiB. */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

/** `AttachmentService.MAX_ATTACHMENTS`: from here on the server answers 409. */
export const MAX_ATTACHMENTS = 20;
