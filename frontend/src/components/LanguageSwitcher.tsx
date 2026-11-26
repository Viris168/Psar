import { useTranslation } from 'react-i18next'

export function LanguageSwitcher() {
  const { i18n } = useTranslation()
  const next = i18n.language === 'km' ? 'en' : 'km'

  const switchLanguage = () => {
    void i18n.changeLanguage(next)
    try {
      localStorage.setItem('psar.language', next)
    } catch {
      /* storage unavailable */
    }
  }

  return (
    <button onClick={switchLanguage} className="rounded border px-2 py-1 text-sm hover:bg-gray-100">
      {next === 'km' ? 'ខ្មែរ' : 'EN'}
    </button>
  )
}
