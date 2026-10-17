package com.roadwise.backend.repository;

import com.roadwise.backend.model.ReportImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ReportImageRepository extends JpaRepository<ReportImage, Long> {

    List<ReportImage> findByReportIdOrderByDisplayOrderAsc(Long reportId);

    long countByReportId(Long reportId);

    void deleteByReportId(Long reportId);

    /**
     * Batched fetch for list screens: one query for many reports instead of the
     * N+1 a per-report lookup would produce on a priority masterlist of hundreds
     * of rows.
     */
    @Query("""
            select image
              from ReportImage image
             where image.report.id in :reportIds
             order by image.report.id asc, image.displayOrder asc, image.id asc
            """)
    List<ReportImage> findAllForReports(@Param("reportIds") Collection<Long> reportIds);
}
