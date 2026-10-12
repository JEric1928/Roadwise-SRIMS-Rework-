package com.roadwise.backend.repository;

import java.time.LocalDate;
import java.util.List;
import com.roadwise.backend.model.RoadReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoadReportRepository extends JpaRepository<RoadReport, Long>, JpaSpecificationExecutor<RoadReport> {

    List<RoadReport> findByBarangay_Id(Long barangayId);

    List<RoadReport> findByBarangay_IdOrderByIdDesc(Long barangayId);

    List<RoadReport> findByStatusIgnoreCase(String status);

    @Query("SELECT r FROM RoadReport r WHERE " +
            "(r.dateSubmitted >= :minDate AND r.dateSubmitted <= :maxDate) AND " +
            "(r.latitude >= :minLat AND r.latitude <= :maxLat) AND " +
            "(r.longitude >= :minLng AND r.longitude <= :maxLng) AND " +
            "((:barangayId IS NOT NULL AND r.barangay.id = :barangayId) OR " +
            " (:cityRoadName IS NOT NULL AND LOWER(r.cityRoadName) = LOWER(:cityRoadName)))")
    List<RoadReport> findCandidateDuplicates(
            @Param("minDate") LocalDate minDate,
            @Param("maxDate") LocalDate maxDate,
            @Param("minLat") Double minLat,
            @Param("maxLat") Double maxLat,
            @Param("minLng") Double minLng,
            @Param("maxLng") Double maxLng,
            @Param("barangayId") Long barangayId,
            @Param("cityRoadName") String cityRoadName);

    // Database-side aggregations for dashboard statistics
    @Query("SELECT r.status, COUNT(r) FROM RoadReport r GROUP BY r.status")
    List<Object[]> countReportsGroupByStatus();

    @Query("SELECT r.severity, COUNT(r) FROM RoadReport r GROUP BY r.severity")
    List<Object[]> countReportsGroupBySeverity();

    boolean existsByIdAndUser_Id(Long id, Long userId);

    // ==========================================
    // ⚡ ANNUAL ROLLOVER SELECTION
    // ==========================================
    // Selects everything EXCEPT active repairs: 'Dispatched to CEO', 'In Progress',
    // 'Completed' (Pending QA) and already-archived rows. The caller archives each
    // report individually so archival timestamps, timeline entries and optimistic
    // version increments are written (a bulk UPDATE cannot do any of these).
    @Query("SELECT r FROM RoadReport r " +
            "WHERE LOWER(TRIM(r.status)) NOT IN ('dispatched to ceo', 'in progress', 'completed', 'archived')")
    List<RoadReport> findAnnualCycleArchivableReports();
}
