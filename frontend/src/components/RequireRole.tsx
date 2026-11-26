import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { getRole } from '@/lib/auth'
import type { Role } from '@/types/api'

/** UI guard only. The backend (@PreAuthorize) is the real protection. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const role = getRole()
  if (!role) return <Navigate to="/login" replace />
  if (!roles.includes(role)) return <Navigate to="/" replace />
  return <>{children}</>
}
