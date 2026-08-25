package com.compare.ocr.benchmark;

public record BenchmarkConfig(
        int concurrency,
        int totalRequests,
        int warmupRequests,
        String sampleName,
        byte[] imageBytes
) {
}
