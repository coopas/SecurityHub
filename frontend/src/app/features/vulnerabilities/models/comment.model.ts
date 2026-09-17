import { UserSummary } from './vulnerability.model';

/** Comentário de uma vulnerabilidade. Não há exclusão de comentário na API. */
export interface Comment {
  id: number;
  vulnerabilityId: number;
  content: string;
  author?: UserSummary;
  /**
   * Calculado no servidor (autor ou ADMIN). A tela usa este campo em vez de refazer a
   * regra no cliente, para que as duas nunca divirjam.
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
