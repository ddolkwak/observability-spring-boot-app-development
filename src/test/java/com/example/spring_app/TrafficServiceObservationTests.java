package com.example.spring_app;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrafficServiceObservationTests {
    private static final String NAME = "traffic.service.operation";
    private static final String HTTP_NAME = "http.server.requests";

    private TrafficDataRepository repository;
    private TestObservationRegistry registry;
    private TrafficService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrafficDataRepository.class);
        registry = TestObservationRegistry.create();
        service = new TrafficService(repository, registry);
    }

    @AfterEach
    void leavesNoCurrentObservation() {
        assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test
    void observesCreateAndPreservesItsResponse() {
        var record = new TrafficData("message", "127.0.0.1", "run-A");
        when(repository.save(any(TrafficData.class))).thenReturn(record);

        var response = service.createTraffic("message", "127.0.0.1", "run-A");

        assertThat(response).isEqualTo(TrafficResponse.from(record));
        verify(repository).save(argThat(data ->
                "message".equals(data.getMessage())
                        && "127.0.0.1".equals(data.getClientIp())
                        && "run-A".equals(data.getTestRunId())));
        assertCompleted("create", null);
    }

    @Test
    void observesPageReadAndPreservesItsResponse() {
        var record = new TrafficData("message", "127.0.0.1", "run-A");
        var pageable = PageRequest.of(0, 20);
        when(repository.findByOrderByIdAsc(pageable))
                .thenReturn(new SliceImpl<>(List.of(record), pageable, false));

        var response = service.readTraffic(0, 20);

        assertThat(response).isEqualTo(new TrafficSliceResponse(
                List.of(TrafficResponse.from(record)), 0, 20, 1, false));
        verify(repository).findByOrderByIdAsc(pageable);
        assertCompleted("read-page", null);
    }

    @Test
    void observesFullReadAndPreservesItsResponse() {
        when(repository.findAll()).thenReturn(List.of(
                new TrafficData("first", "127.0.0.1", "run-A"),
                new TrafficData("second", "127.0.0.1", "run-A")));

        var response = service.readAllForExperiment();

        assertThat(response).isEqualTo(new FullScanResponse("full-table-read", 2));
        verify(repository).findAll();
        assertCompleted("read-all", null);
    }

    @Test
    void observesCleanupAndPreservesItsResponse() {
        when(repository.deleteForTestRunId("run-A")).thenReturn(3);

        var response = service.cleanupTraffic("run-A");

        assertThat(response).isEqualTo(new TrafficCleanupResponse("run-A", 3));
        verify(repository).deleteForTestRunId("run-A");
        assertCompleted("cleanup", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "read-page", "read-all", "cleanup"})
    void recordsAndRethrowsTheSameRepositoryFailure(String operation) {
        var failure = new IllegalStateException("synthetic repository failure");

        Runnable invocation = switch (operation) {
            case "create" -> {
                when(repository.save(any(TrafficData.class))).thenThrow(failure);
                yield () -> service.createTraffic("message", "127.0.0.1", "run-A");
            }
            case "read-page" -> {
                when(repository.findByOrderByIdAsc(PageRequest.of(0, 20))).thenThrow(failure);
                yield () -> service.readTraffic(0, 20);
            }
            case "read-all" -> {
                when(repository.findAll()).thenThrow(failure);
                yield () -> service.readAllForExperiment();
            }
            case "cleanup" -> {
                when(repository.deleteForTestRunId("run-A")).thenThrow(failure);
                yield () -> service.cleanupTraffic("run-A");
            }
            default -> throw new AssertionError(operation);
        };

        assertThatThrownBy(invocation::run).isSameAs(failure);
        assertCompleted(operation, failure);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 101", "2147483647, 100"})
    void observesInvalidPaginationWithoutCallingTheRepository(int page, int size) {
        Throwable failure = catchThrowable(() -> service.readTraffic(page, size));

        assertThat(failure).isInstanceOf(InvalidTrafficQueryException.class);
        verifyNoInteractions(repository);
        assertCompleted("read-page", failure);
    }

    @Test
    void preservesTheParentAndRestoresItsScopeAfterSuccessAndFailure() {
        var record = new TrafficData("message", "127.0.0.1", "run-A");
        var failure = new IllegalStateException("synthetic repository failure");
        var parent = Observation.start("test.parent", registry);

        when(repository.save(any(TrafficData.class)))
                .thenAnswer(invocation -> {
                    assertThat(registry.getCurrentObservation()).isNotNull().isNotSameAs(parent);
                    return record;
                })
                .thenAnswer(invocation -> {
                    assertThat(registry.getCurrentObservation()).isNotNull().isNotSameAs(parent);
                    throw failure;
                });

        try (Observation.Scope ignored = parent.openScope()) {
            service.createTraffic("message", "127.0.0.1", "run-A");
            assertThat(registry.getCurrentObservation()).isSameAs(parent);

            assertThatThrownBy(() -> service.createTraffic("message", "127.0.0.1", "run-A"))
                    .isSameAs(failure);
            assertThat(registry.getCurrentObservation()).isSameAs(parent);
        } finally {
            parent.stop();
        }

        registry.assertThat()
                .hasNumberOfObservationsWithNameEqualTo(NAME, 2)
                .forAllObservationsWithNameEqualTo(NAME,
                        context -> context.hasParentObservationEqualTo(parent));
    }

    @Test
    void recordsSuccessfulAndFailedTimerDurationsWithoutSleeping() {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        registry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));

        var pageable = PageRequest.of(0, 20);
        var failure = new IllegalStateException("synthetic repository failure");
        when(repository.findByOrderByIdAsc(pageable))
                .thenAnswer(invocation -> {
                    clock.add(Duration.ofMillis(25));
                    return new SliceImpl<TrafficData>(List.of(), pageable, false);
                })
                .thenAnswer(invocation -> {
                    clock.add(Duration.ofMillis(10));
                    throw failure;
                });

        try {
            service.readTraffic(0, 20);
            assertThatThrownBy(() -> service.readTraffic(0, 20)).isSameAs(failure);

            Timer success = meters.get(NAME)
                    .tags("operation", "read-page", "error", "none").timer();
            assertThat(success.count()).isEqualTo(1);
            assertThat(success.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);

            Timer failed = meters.get(NAME)
                    .tags("operation", "read-page", "error", "IllegalStateException").timer();
            assertThat(failed.count()).isEqualTo(1);
            assertThat(failed.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(10.0);
            registry.assertThat().hasNumberOfObservationsWithNameEqualTo(NAME, 2);
        } finally {
            meters.close();
        }
    }

    @Test
    void createsOneServiceChildOfOneHttpObservation() throws Exception {
        var pageable = PageRequest.of(0, 20);
        when(repository.findByOrderByIdAsc(pageable))
                .thenReturn(new SliceImpl<>(List.of(), pageable, false));

        mvc().perform(get("/api/traffic")).andExpect(status().isOk());

        registry.assertThat()
                .hasNumberOfObservationsEqualTo(2)
                .hasNumberOfObservationsWithNameEqualTo(HTTP_NAME, 1)
                .hasNumberOfObservationsWithNameEqualTo(NAME, 1)
                .forAllObservationsWithNameEqualTo(NAME,
                        context -> context.hasParentObservationContextMatching(parent -> HTTP_NAME.equals(parent.getName())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "cleanup"})
    void controllerRejectionDoesNotCreateAServiceObservation(String operation) throws Exception {
        MockHttpServletRequestBuilder request = operation.equals("create")
                ? post("/api/traffic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\" \"}")
                : delete("/api/experiments/traffic").param("testRunId", "bad/id");

        mvc().perform(request).andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
        registry.assertThat()
                .hasNumberOfObservationsEqualTo(1)
                .hasNumberOfObservationsWithNameEqualTo(HTTP_NAME, 1)
                .hasNumberOfObservationsWithNameEqualTo(NAME, 0);
    }

    @Test
    void preservesTheSafeHttp500AndRecordsTheActualServiceFailure() throws Exception {
        var failure = new IllegalStateException("synthetic repository failure");
        when(repository.findByOrderByIdAsc(PageRequest.of(0, 20))).thenThrow(failure);

        mvc().perform(get("/api/traffic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."));

        registry.assertThat()
                .hasNumberOfObservationsWithNameEqualTo(HTTP_NAME, 1)
                .hasNumberOfObservationsWithNameEqualTo(NAME, 1)
                .forAllObservationsWithNameEqualTo(NAME, context -> context.hasError(failure));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new TrafficController(service), new TrafficCleanupController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new ServerHttpObservationFilter(registry))
                .build();
    }

    private void assertCompleted(String operation, Throwable failure) {
        var observation = registry.assertThat()
                .hasNumberOfObservationsEqualTo(1)
                .hasSingleObservationThat()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasNameEqualTo(NAME)
                .hasContextualNameEqualTo("traffic " + operation)
                .hasLowCardinalityKeyValue("operation", operation)
                .hasOnlyKeys("operation")
                .doesNotHaveParentObservation();

        if (failure == null) {
            observation.doesNotHaveError();
        } else {
            observation.hasError(failure);
        }
    }
}