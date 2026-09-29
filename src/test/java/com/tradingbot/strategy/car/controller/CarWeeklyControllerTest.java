package com.tradingbot.strategy.car.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CarWeeklyController.class)
class CarWeeklyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CarWeeklyGttService carService;

    @Test
    void testTriggerWeeklyScanEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/car/run-weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(carService).runSundayWeeklyRoutine();
    }
}
