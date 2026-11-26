import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <div className="mx-auto max-w-md py-24 text-center">
      <h1 className="text-2xl font-semibold">{t('common.notFound')}</h1>
      <Link to="/" className="mt-4 inline-block text-blue-600 hover:underline">
        {t('common.backHome')}
      </Link>
    </div>
  )
}
