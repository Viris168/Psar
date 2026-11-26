import type { Role } from '@/types/api'

/**
 * Minimal auth storage for Phase 0. Phase 1 replaces this with a proper auth store
 * (access token in memory, refresh token flow).
 */
const TOKEN_KEY = 'psar.accessToken'
const ROLE_KEY = 'psar.role'

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function getAccessToken(): string | null {
  return safeGet(TOKEN_KEY)
}

export function getRole(): Role | null {
  return safeGet(ROLE_KEY) as Role | null
}

export function saveAuth(token: string, role: Role): void {
  try {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(ROLE_KEY, role)
  } catch {
    /* storage unavailable */
  }
}

export function clearAuth(): void {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(ROLE_KEY)
  } catch {
    /* storage unavailable */
  }
}
