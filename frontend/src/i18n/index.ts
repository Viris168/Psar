import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en.json'
import km from './km.json'

let savedLanguage = 'en'
try {
  savedLanguage = localStorage.getItem('psar.language') ?? 'en'
} catch {
  /* storage unavailable */
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, km: { translation: km } },
  lng: savedLanguage,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

export default i18n
