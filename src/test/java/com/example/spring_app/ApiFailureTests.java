package com.example.spring_app;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrafficController.class)
@ActiveProfiles("test")
class ApiFailureTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TrafficService service;

    @Test
    void unexpectedFailureReturnsSafeProblemWithoutInternalDetails() throws Exception {
        when(service.readTraffic(0, 20)).thenThrow(new IllegalStateException(
            "Synthetic failure: jdbc:postgresql://private-db/traffic?password=do-not-leak"));

        String response = mvc.perform(get("/api/traffic"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."))
            .andExpect(jsonPath("$.errors").isEmpty())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).doesNotContain(
            "jdbc:postgresql", "private-db", "do-not-leak", "IllegalStateException");
    }
}