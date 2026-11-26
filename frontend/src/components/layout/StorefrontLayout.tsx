import { Link, Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'

export function StorefrontLayout() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen">
      <header className="border-b bg-white">
        <nav className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <Link to="/" className="text-xl font-bold text-emerald-700">
            {t('app.name')}
          </Link>
          <div className="flex items-center gap-4 text-sm">
            <Link to="/" className="hover:underline">{t('nav.home')}</Link>
            <Link to="/login" className="hover:underline">{t('nav.login')}</Link>
            <LanguageSwitcher />
          </div>
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
