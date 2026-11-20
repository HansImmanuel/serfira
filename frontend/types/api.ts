export interface ApiErrorBody {
  code: string;
  message: string;
}

/** The one envelope every endpoint returns (TS §2.2); unwrapped only by `lib/api.ts`. */
export interface ApiResponse<T> {
  data: T | null;
  error: ApiErrorBody | null;
}

/** Page envelope (TS §2.2, snake_case on the wire). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}
