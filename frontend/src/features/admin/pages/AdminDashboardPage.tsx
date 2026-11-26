import { useTranslation } from 'react-i18next'

export function AdminDashboardPage() {
  const { t } = useTranslation()
  return (
    <section>
      <h1 className="text-2xl font-semibold">{t('admin.dashboard')}</h1>
      <p className="mt-2 text-sm text-gray-600">{t('admin.comingSoon')}</p>
      {/* Phase 10: sales charts (Recharts), top products, low stock */}
    </section>
  )
}
