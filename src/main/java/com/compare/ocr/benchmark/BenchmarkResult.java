package com.compare.ocr.benchmark;

import java.util.List;

public record BenchmarkResult(
        String engineName,
        String sampleName,
        int concurrency,
        int totalRequests,
        double qps,
        double avgLatencyMs,
        double minLatencyMs,
        double maxLatencyMs,
        double p50LatencyMs,
        double p90LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double initialRssMB,
        double peakRssMB,
        double rssDeltaMB,
        double peakHeapMB,
        double avgProcessCpuPercent,
        int successCount,
        int errorCount
) {
}
