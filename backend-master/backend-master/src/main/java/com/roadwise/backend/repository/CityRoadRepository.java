package com.roadwise.backend.repository;

import com.roadwise.backend.model.CityRoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityRoadRepository extends JpaRepository<CityRoad, Long> {

    List<CityRoad> findByBarangayId(Long barangayId);

    Optional<CityRoad> findByRoadId(String roadId);
    CityRoad findFirstByOrderByIdDesc();

    boolean existsByRoadNameIgnoreCaseAndBarangayId(String roadName, Long barangayId);
    // 🚀 NEW: Check for duplicates, but IGNORE the current road ID being edited!
    boolean existsByRoadNameIgnoreCaseAndBarangayIdAndIdNot(String roadName, Long barangayId, Long id);


}