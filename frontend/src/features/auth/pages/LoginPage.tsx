import { useTranslation } from 'react-i18next'

export function LoginPage() {
  const { t } = useTranslation()
  return (
    <section className="mx-auto max-w-sm rounded-lg border bg-white p-6">
      <h1 className="text-xl font-semibold">{t('auth.loginTitle')}</h1>
      <p className="mt-2 text-sm text-gray-600">{t('auth.comingSoon')}</p>
      {/* Phase 1: email/password form -> POST /api/auth/login -> saveAuth() */}
    </section>
  )
}
