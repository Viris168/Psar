import { createBrowserRouter } from 'react-router-dom'
import { StorefrontLayout } from '@/components/layout/StorefrontLayout'
import { AdminLayout } from '@/components/layout/AdminLayout'
import { RequireRole } from '@/components/RequireRole'
import { HomePage } from '@/features/catalog/pages/HomePage'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import { AdminDashboardPage } from '@/features/admin/pages/AdminDashboardPage'
import { NotFoundPage } from '@/components/NotFoundPage'

/**
 * Storefront routes are public (checkout/orders will require CUSTOMER).
 * /admin routes require ADMIN, MANAGER or STAFF; pages inside check finer roles.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <StorefrontLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'login', element: <LoginPage /> },
      // Phase 2: products/:slug, categories/:slug
      // Phase 4: cart, checkout, orders, orders/:publicId
      // Phase 7-8: rewards, membership
    ],
  },
  {
    path: '/admin',
    element: (
      <RequireRole roles={['ADMIN', 'MANAGER', 'STAFF']}>
        <AdminLayout />
      </RequireRole>
    ),
    children: [
      { index: true, element: <AdminDashboardPage /> },
      // Phase 2: products, categories, suppliers, purchase-orders
      // Phase 4: orders
      // Phase 6+: promotions, rewards, plans, delivery-companies, users
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
