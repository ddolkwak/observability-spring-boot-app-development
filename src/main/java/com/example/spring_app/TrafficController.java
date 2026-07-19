package com.example.spring_app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TrafficController {

    private final TrafficDataRepository repository;

    public TrafficController(TrafficDataRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/generate-insert")
    public String generateInsertTraffic(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        
        TrafficData savedData = repository.save(new TrafficData("Test traffic generated.", ip));
        
        return "DB Insert Completed. ID: " + savedData.getId();
    }

    @GetMapping("/api/generate-select")
    public String generateSelectTraffic() {
        List<TrafficData> allData = repository.findAll();
        
        return "DB Select Completed. Current Stored Data: " + allData.size();
    }
}