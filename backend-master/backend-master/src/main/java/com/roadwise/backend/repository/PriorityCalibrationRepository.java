package com.roadwise.backend.repository;

import com.roadwise.backend.model.PriorityCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriorityCalibrationRepository extends JpaRepository<PriorityCalibration, Short> {
}
