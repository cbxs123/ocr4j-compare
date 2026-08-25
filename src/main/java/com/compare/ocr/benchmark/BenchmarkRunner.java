package com.compare.ocr.benchmark;

import com.compare.ocr.engine.OcrEngineAdapter;
import com.compare.ocr.engine.OcrEngineResult;
import com.compare.ocr.monitor.ResourceMonitor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class BenchmarkRunner {

    private final ResourceMonitor resourceMonitor;

    public BenchmarkRunner() {
        this.resourceMonitor = new ResourceMonitor();
    }

    public BenchmarkResult runBenchmark(OcrEngineAdapter engine, BenchmarkConfig config) {
        log.info("Starting benchmark: engine={}, sample={}, concurrency={}, totalReq={}, warmup={}",
                engine.getName(), config.sampleName(), config.concurrency(), config.totalRequests(), config.warmupRequests());

        // 1. Warmup
        for (int i = 0; i < config.warmupRequests(); i++) {
            try {
                engine.run(config.imageBytes());
            } catch (Exception e) {
                log.warn("Warmup error: {}", e.getMessage());
            }
        }

        // Force GC before measurement
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}

        ResourceMonitor.Snapshot initialSnap = resourceMonitor.captureSnapshot();
        double initialRssMB = initialSnap.nativeRssMB();

        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        List<Long> latenciesNs = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Double> rssSnapshots = new CopyOnWriteArrayList<>();
        List<Double> heapSnapshots = new CopyOnWriteArrayList<>();
        List<Double> cpuSnapshots = new CopyOnWriteArrayList<>();

        ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        monitorExecutor.scheduleAtFixedRate(() -> {
            ResourceMonitor.Snapshot snap = resourceMonitor.captureSnapshot();
            rssSnapshots.add(snap.nativeRssMB());
            heapSnapshots.add(snap.jvmHeapUsedMB());
            cpuSnapshots.add(snap.processCpuLoadPercent());
        }, 50, 100, TimeUnit.MILLISECONDS);

        long benchStartNs = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(config.totalRequests());

        for (int i = 0; i < config.totalRequests(); i++) {
            executor.submit(() -> {
                try {
                    OcrEngineResult res = engine.run(config.imageBytes());
                    latenciesNs.add(res.totalElapsedNs());
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                    log.error("Execution failure: ", t);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            log.error("Benchmark interrupted", e);
        }

        long benchEndNs = System.nanoTime();
        executor.shutdown();
        monitorExecutor.shutdown();

        ResourceMonitor.Snapshot finalSnap = resourceMonitor.captureSnapshot();
        rssSnapshots.add(finalSnap.nativeRssMB());
        heapSnapshots.add(finalSnap.jvmHeapUsedMB());

        double totalDurationSec = (benchEndNs - benchStartNs) / 1_000_000_000.0;
        double qps = successCount.get() / Math.max(totalDurationSec, 0.001);

        List<Long> sortedLatencies = new ArrayList<>(latenciesNs);
        Collections.sort(sortedLatencies);

        double avgLatencyMs = sortedLatencies.isEmpty() ? 0.0 :
                (sortedLatencies.stream().mapToDouble(Long::doubleValue).average().orElse(0.0) / 1_000_000.0);
        double minLatencyMs = sortedLatencies.isEmpty() ? 0.0 : (sortedLatencies.get(0) / 1_000_000.0);
        double maxLatencyMs = sortedLatencies.isEmpty() ? 0.0 : (sortedLatencies.get(sortedLatencies.size() - 1) / 1_000_000.0);

        double p50 = getPercentile(sortedLatencies, 0.50);
        double p90 = getPercentile(sortedLatencies, 0.90);
        double p95 = getPercentile(sortedLatencies, 0.95);
        double p99 = getPercentile(sortedLatencies, 0.99);

        double peakRssMB = rssSnapshots.stream().mapToDouble(Double::doubleValue).max().orElse(initialRssMB);
        double peakHeapMB = heapSnapshots.stream().mapToDouble(Double::doubleValue).max().orElse(initialSnap.jvmHeapUsedMB());
        double avgCpu = cpuSnapshots.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double rssDeltaMB = peakRssMB - initialRssMB;

        return new BenchmarkResult(
                engine.getName(),
                config.sampleName(),
                config.concurrency(),
                config.totalRequests(),
                qps,
                avgLatencyMs,
                minLatencyMs,
                maxLatencyMs,
                p50,
                p90,
                p95,
                p99,
                initialRssMB,
                peakRssMB,
                rssDeltaMB,
                peakHeapMB,
                avgCpu,
                successCount.get(),
                errorCount.get()
        );
    }

    private double getPercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index) / 1_000_000.0;
    }
}
