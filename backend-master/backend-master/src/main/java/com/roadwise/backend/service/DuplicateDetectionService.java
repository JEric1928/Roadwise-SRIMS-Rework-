package com.roadwise.backend.service;

import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.repository.RoadReportRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class DuplicateDetectionService {

    private final RoadReportRepository repository;

    public DuplicateDetectionService(RoadReportRepository repository) {
        this.repository = repository;
    }

    /**
     * Calculates Haversine distance in meters between two lat/lng points.
     */
    public double calculateHaversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // Earth radius in meters
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Checks whether a given candidate report is a potential duplicate of an existing recent report.
     * Potential duplicate if distance < 50m AND submitted within 7 days AND (same barangay or road).
     */
    public Optional<RoadReport> findPotentialDuplicate(RoadReport candidate) {
        if (candidate == null || candidate.getLatitude() == null || candidate.getLongitude() == null) {
            return Optional.empty();
        }

        LocalDate candidateDate = candidate.getDateSubmitted() != null ? candidate.getDateSubmitted() : LocalDate.now();
        LocalDate minDate = candidateDate.minusDays(7);
        LocalDate maxDate = candidateDate.plusDays(7);

        double lat = candidate.getLatitude();
        double lng = candidate.getLongitude();
        double minLat = lat - 0.001;
        double maxLat = lat + 0.001;
        double minLng = lng - 0.001;
        double maxLng = lng + 0.001;

        Long barangayId = candidate.getBarangay() != null ? candidate.getBarangay().getId() : null;
        String cityRoadName = candidate.getCityRoadName();

        List<RoadReport> recentReports = repository.findCandidateDuplicates(
                minDate, maxDate, minLat, maxLat, minLng, maxLng, barangayId, cityRoadName);

        for (RoadReport existing : recentReports) {
            if (candidate.getId() != null && candidate.getId().equals(existing.getId())) {
                continue;
            }
            if (existing.getLatitude() == null || existing.getLongitude() == null) {
                continue;
            }

            LocalDate existingDate = existing.getDateSubmitted() != null ? existing.getDateSubmitted() : LocalDate.now();
            long daysApart = Math.abs(ChronoUnit.DAYS.between(candidateDate, existingDate));

            if (daysApart <= 7) {
                double distanceMeters = calculateHaversineDistanceMeters(
                        candidate.getLatitude(), candidate.getLongitude(),
                        existing.getLatitude(), existing.getLongitude()
                );

                if (distanceMeters <= 50.0) {
                    boolean sameBarangay = candidate.getBarangay() != null && existing.getBarangay() != null &&
                            candidate.getBarangay().getId().equals(existing.getBarangay().getId());
                    boolean sameRoad = candidate.getCityRoadName() != null && existing.getCityRoadName() != null &&
                            candidate.getCityRoadName().equalsIgnoreCase(existing.getCityRoadName());

                    if (sameBarangay || sameRoad) {
                        return Optional.of(existing);
                    }
                }
            }
        }

        return Optional.empty();
    }
}
