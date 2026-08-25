package com.compare.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
public class OcrCompareApplication {

    public static void main(String[] args) {
        log.info("Starting OcrCompareApplication (Spring Boot 2.7.2)...");
        SpringApplication.run(OcrCompareApplication.class, args);
    }
}
