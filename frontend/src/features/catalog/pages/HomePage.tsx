import { useTranslation } from 'react-i18next'

export function HomePage() {
  const { t } = useTranslation()
  return (
    <section className="py-16 text-center">
      <h1 className="text-3xl font-bold">{t('home.title')}</h1>
      <p className="mt-3 text-gray-600">{t('home.subtitle')}</p>
      {/* Phase 2: featured categories + product grid (useQuery -> api/catalog.ts) */}
    </section>
  )
}
