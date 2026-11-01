package com.example.spring_app;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {
    Slice<TrafficData> findByOrderByIdAsc(Pageable pageable);

    @Modifying
    @Query("delete from TrafficData data where data.testRunId = :testRunId")
    int deleteForTestRunId(@Param("testRunId") String testRunId);
}