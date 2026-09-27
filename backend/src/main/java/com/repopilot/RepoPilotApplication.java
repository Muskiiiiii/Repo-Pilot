package com.repopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RepoPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoPilotApplication.class, args);
    }
}
