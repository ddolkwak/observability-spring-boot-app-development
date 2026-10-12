package com.example.spring_app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {
    Slice<TrafficData> findByOrderByIdAsc(Pageable pageable);
}