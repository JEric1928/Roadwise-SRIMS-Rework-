package com.roadwise.backend.service;

import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.repository.RoadReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the PostGIS-first / bounding-box-fallback candidate lookup.
 * The PostGIS native query cannot run on the H2 test database, so the
 * fallback path is what the H2-backed workflow tests exercise; these unit
 * tests pin both branches with a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class DuplicateDetectionServiceTest {

    @Mock
    private RoadReportRepository repository;

    private DuplicateDetectionService service;

    @BeforeEach
    void setUp() {
        service = new DuplicateDetectionService(repository, synchronousTransactionManager());
    }

    /**
     * Runs transaction callbacks inline. The service isolates the PostGIS probe in a
     * REQUIRES_NEW transaction; a mocked repository throws before any real transaction
     * work happens, so a pass-through manager preserves this test's original meaning.
     */
    private static PlatformTransactionManager synchronousTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }

    private static RoadReport report(Long id, double lat, double lng, String roadName) {
        RoadReport report = new RoadReport();
        report.setId(id);
        report.setLatitude(lat);
        report.setLongitude(lng);
        report.setCityRoadName(roadName);
        report.setDateSubmitted(LocalDate.now());
        return report;
    }

    @Test
    void usesPostgisPathWhenAvailable() {
        RoadReport existing = report(7L, 14.5867, 121.0175, "Quirino Highway");
        when(repository.findNearbyCandidates(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), anyString()))
                .thenReturn(List.of(existing));

        RoadReport candidate = report(null, 14.5867, 121.0175, "Quirino Highway");

        Optional<RoadReport> duplicate = service.findPotentialDuplicate(candidate);

        assertTrue(duplicate.isPresent());
        assertEquals(7L, duplicate.get().getId());
        verify(repository, never()).findCandidateDuplicates(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fallsBackToBoundingBoxWhenPostgisUnavailable() {
        RoadReport existing = report(7L, 14.5867, 121.0175, "Quirino Highway");
        when(repository.findNearbyCandidates(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), anyString()))
                .thenThrow(new BadSqlGrammarException(
                        "findNearbyCandidates", "SELECT r.* ... ST_DWithin ...",
                        new SQLException("function st_dwithin(geography, geography, double precision) does not exist")));
        when(repository.findCandidateDuplicates(any(), any(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(), anyString()))
                .thenReturn(List.of(existing));

        RoadReport candidate = report(null, 14.5867, 121.0175, "Quirino Highway");

        Optional<RoadReport> duplicate = service.findPotentialDuplicate(candidate);

        assertTrue(duplicate.isPresent());
        assertEquals(7L, duplicate.get().getId());
        verify(repository).findCandidateDuplicates(
                any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyString());
    }

    @Test
    void returnsEmptyWhenNeitherPathFindsAMatch() {
        when(repository.findNearbyCandidates(anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), anyString()))
                .thenReturn(List.of());

        RoadReport candidate = report(null, 14.5867, 121.0175, "Quirino Highway");

        assertTrue(service.findPotentialDuplicate(candidate).isEmpty());
    }
}
