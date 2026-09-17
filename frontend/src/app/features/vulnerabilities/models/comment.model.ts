import { UserSummary } from './vulnerability.model';

/** Comment on a vulnerability. There is no comment deletion in the API. */
export interface Comment {
  id: number;
  vulnerabilityId: number;
  content: string;
  author?: UserSummary;
  /**
   * Computed on the server (author or ADMIN). The screen uses this field instead of
   * redoing the rule on the client, so that the two never diverge.
   */
  editable: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CommentRequest {
  content: string;
}

export const COMMENT_MAX_LENGTH = 2000;

export const COMMENTS_PAGE_SIZE = 20;
