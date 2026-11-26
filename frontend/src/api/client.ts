import axios from 'axios'
import { getAccessToken, clearAuth } from '@/lib/auth'

/**
 * The only axios instance. Feature API files (src/api/*.ts) use it.
 * Base URL: VITE_API_URL in production, Vite dev proxy (/api) locally.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Phase 1: try POST /auth/refresh once before logging out.
    if (error.response?.status === 401) clearAuth()
    return Promise.reject(error)
  },
)
