package com.example.spring_app;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
public class TrafficService {
    public static final int MAX_PAGE_SIZE = 100;
    private static final String OPERATION_OBSERVATION = "traffic.service.operation";

    private final TrafficDataRepository repository;
    private final ObservationRegistry observationRegistry;

    public TrafficService(TrafficDataRepository repository, ObservationRegistry observationRegistry) {
        this.repository = repository;
        this.observationRegistry = observationRegistry;
    }

    @Transactional
    public TrafficResponse createTraffic(
            String message, String clientIp, String testRunId) {
        return Observation.createNotStarted(OPERATION_OBSERVATION, observationRegistry)
                .lowCardinalityKeyValue("operation", "create")
                .contextualName("traffic create")
                .observe(() -> {
                    TrafficData saved = repository.save(new TrafficData(message, clientIp, testRunId));
                    return TrafficResponse.from(saved);
                });
    }

    @Transactional(readOnly = true)
    public TrafficSliceResponse readTraffic(int page, int size) {
        return Observation.createNotStarted(OPERATION_OBSERVATION, observationRegistry)
                .lowCardinalityKeyValue("operation", "read-page")
                .contextualName("traffic read-page")
                .observe(() -> {
                    validateQuery(page, size);

                    var slice = repository.findByOrderByIdAsc(PageRequest.of(page, size));
                    var items = slice.getContent().stream()
                            .map(TrafficResponse::from)
                            .toList();

                    return new TrafficSliceResponse(items, page, size, slice.getNumberOfElements(), slice.hasNext());
                });
    }

    @Transactional(readOnly = true)
    public FullScanResponse readAllForExperiment() {
        return Observation.createNotStarted(OPERATION_OBSERVATION, observationRegistry)
                .lowCardinalityKeyValue("operation", "read-all")
                .contextualName("traffic read-all")
                .observe(() -> {
                    var records = repository.findAll();
                    return new FullScanResponse("full-table-read", records.size());
                });
    }

    @Transactional
    public TrafficCleanupResponse cleanupTraffic(String testRunId) {
        return Observation.createNotStarted(OPERATION_OBSERVATION, observationRegistry)
                .lowCardinalityKeyValue("operation", "cleanup")
                .contextualName("traffic cleanup")
                .observe(() -> {
                    int deletedCount = repository.deleteForTestRunId(testRunId);
                    return new TrafficCleanupResponse(testRunId, deletedCount);
                });
    }

    private static void validateQuery(int page, int size) {
        if (page < 0) {
            throw new InvalidTrafficQueryException("page", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidTrafficQueryException("size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // JPA's first-result offset is an int. Reject unsupported deep offsets.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidTrafficQueryException("page", "page * size must not exceed " + Integer.MAX_VALUE);
        }
    }
}