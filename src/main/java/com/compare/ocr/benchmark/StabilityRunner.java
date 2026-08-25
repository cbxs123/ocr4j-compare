package com.compare.ocr.benchmark;

import com.compare.ocr.engine.OcrEngineAdapter;
import com.compare.ocr.monitor.ResourceMonitor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class StabilityRunner {

    public record StabilityResult(
            String engineName,
            int totalRequests,
            double initialRssMB,
            double midRssMB,
            double finalRssMB,
            double rssGrowthMB,
            double avgLatencyMs,
            boolean possibleLeak
    ) {
    }

    public static StabilityResult runStabilityTest(OcrEngineAdapter engine, byte[] imageBytes, int totalRequests) {
        log.info("Running stability test for {}: totalRequests={}", engine.getName(), totalRequests);
        ResourceMonitor monitor = new ResourceMonitor();

        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        double initialRss = monitor.captureSnapshot().nativeRssMB();
        double midRss = initialRss;
        List<Long> latencies = new ArrayList<>();

        int midPoint = totalRequests / 2;

        for (int i = 0; i < totalRequests; i++) {
            try {
                long start = System.nanoTime();
                engine.run(imageBytes);
                latencies.add(System.nanoTime() - start);
            } catch (Exception e) {
                log.error("Stability test error at iteration {}", i, e);
            }

            if (i == midPoint) {
                midRss = monitor.captureSnapshot().nativeRssMB();
            }
        }

        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        double finalRss = monitor.captureSnapshot().nativeRssMB();
        double rssGrowth = finalRss - initialRss;
        double avgLatency = latencies.stream().mapToDouble(Long::doubleValue).average().orElse(0.0) / 1_000_000.0;

        // Leak heuristic: if memory grows continuously by > 80 MB across requests
        boolean possibleLeak = (rssGrowth > 80.0) && (finalRss > midRss + 30.0);

        return new StabilityResult(
                engine.getName(),
                totalRequests,
                initialRss,
                midRss,
                finalRss,
                rssGrowth,
                avgLatency,
                possibleLeak
        );
    }
}
