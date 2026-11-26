/** Mirrors com.psar.common.exception.ErrorResponse */
export interface ErrorResponse {
  code: string
  message: string
  details: Record<string, string>
  timestamp: string
}

/** Mirrors com.psar.common.web.PageResponse */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type Role = 'ADMIN' | 'MANAGER' | 'STAFF' | 'CUSTOMER'
