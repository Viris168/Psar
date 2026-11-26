import { NavLink, Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'

export function AdminLayout() {
  const { t } = useTranslation()
  return (
    <div className="flex min-h-screen">
      <aside className="w-60 border-r bg-white p-4">
        <div className="mb-6 text-lg font-bold text-emerald-700">{t('app.name')} · {t('nav.admin')}</div>
        <nav className="flex flex-col gap-1 text-sm">
          <NavLink to="/admin" end className="rounded px-2 py-1 hover:bg-gray-100">
            {t('admin.dashboard')}
          </NavLink>
          {/* Phase 2+: products, stock, suppliers, orders, promotions, rewards ... */}
        </nav>
      </aside>
      <div className="flex-1">
        <header className="flex justify-end border-b bg-white px-6 py-3">
          <LanguageSwitcher />
        </header>
        <main className="p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
