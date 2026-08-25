package com.compare.ocr.runner;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.service.OcrBenchmarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrBenchmarkCommandLineRunner implements CommandLineRunner {

    private final OcrProperties properties;
    private final OcrBenchmarkService benchmarkService;

    @Override
    public void run(String... args) {
        if (properties.getBenchmark().isAutoRunOnStartup()) {
            log.info("Auto-run benchmark on startup is ENABLED.");
            benchmarkService.runFullBenchmark();
        } else {
            log.info("Auto-run benchmark on startup is DISABLED (ocr.benchmark.auto-run-on-startup=false).");
        }
    }
}
