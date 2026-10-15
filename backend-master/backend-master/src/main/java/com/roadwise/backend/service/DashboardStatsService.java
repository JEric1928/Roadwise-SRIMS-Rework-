package com.roadwise.backend.service;

import com.roadwise.backend.repository.RoadReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard aggregates for {@code GET /api/reports/dashboard-stats}.
 *
 * <p>This is a separate bean rather than a method on {@code RoadReportController}
 * for two reasons:</p>
 *
 * <ul>
 *   <li><b>Scoping.</b> The endpoint is reachable by every authenticated role,
 *       while the report list scopes Barangay Officials to their own barangay.
 *       The totals have to follow the same rule, otherwise the endpoint is a
 *       way to read city-wide volumes that the caller cannot list.</li>
 *   <li><b>Caching.</b> The scope has to be part of the cache key. Caching from
 *       inside the controller would also bypass the cache proxy (self-invocation
 *       is not intercepted), so the {@code @Cacheable} lives here and the
 *       controller calls into it.</li>
 * </ul>
 *
 * <p>The cache name stays {@code dashboardStats}: the report-mutating endpoints
 * already evict it with {@code allEntries = true}
 * (see {@code RoadReportController}), which remains correct for every key.</p>
 */
@Service
public class DashboardStatsService {

    @Autowired
    private RoadReportRepository repository;

    /**
     * @param barangayId the barangay to scope the totals to, or {@code null}
     *                   for a city-wide view (administrators and the city
     *                   engineering office).
     * @return the same shape the endpoint returned before scoping: a map of
     *         {@code statusCounts}, {@code severityCounts} and
     *         {@code totalReports}.
     */
    @Cacheable(value = "dashboardStats", key = "#barangayId == null ? 'city-wide' : 'barangay-' + #barangayId")
    public Map<String, Object> getStats(Long barangayId) {
        Map<String, Long> statusCounts = new HashMap<>();
        for (Object[] row : countsByStatus(barangayId)) {
            if (row[0] != null) {
                statusCounts.put(row[0].toString(), (Long) row[1]);
            }
        }

        Map<String, Long> severityCounts = new HashMap<>();
        for (Object[] row : countsBySeverity(barangayId)) {
            if (row[0] != null) {
                severityCounts.put(row[0].toString(), (Long) row[1]);
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("statusCounts", statusCounts);
        stats.put("severityCounts", severityCounts);
        stats.put("totalReports", barangayId == null ? repository.count() : repository.countByBarangay_Id(barangayId));
        stats.put("scope", barangayId == null ? "city-wide" : "barangay");
        return stats;
    }

    private java.util.List<Object[]> countsByStatus(Long barangayId) {
        return barangayId == null
                ? repository.countReportsGroupByStatus()
                : repository.countReportsGroupByStatusForBarangay(barangayId);
    }

    private java.util.List<Object[]> countsBySeverity(Long barangayId) {
        return barangayId == null
                ? repository.countReportsGroupBySeverity()
                : repository.countReportsGroupBySeverityForBarangay(barangayId);
    }
}
