import { Route, Routes } from 'react-router-dom'
import { AppLayout } from '../components/layout/AppLayout'
import { NotFoundPage } from '../pages/NotFoundPage'
import { OverviewPage } from '../pages/OverviewPage'
import { SectionPage } from '../pages/SectionPage'
import { ChangesPage } from '../pages/ChangesPage'
import { ChangeDetailPage } from '../pages/ChangeDetailPage'
import { ConnectionsPage } from '../pages/ConnectionsPage'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        {/* Detail IDs remain URL state so reads are shareable within an authorized workspace. */}
        <Route path="changes" element={<ChangesPage />} />
        <Route path="changes/:id" element={<ChangeDetailPage />} />
        <Route path="connections" element={<ConnectionsPage />} />
        <Route
          path="connections/github/callback"
          element={<ConnectionsPage />}
        />
        <Route path="services" element={<SectionPage title="Services" />} />
        <Route path="activity" element={<SectionPage title="Activity" />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
