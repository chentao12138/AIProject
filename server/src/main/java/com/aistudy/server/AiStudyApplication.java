package com.aistudy.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiStudyApplication.class, args);
    }
}
