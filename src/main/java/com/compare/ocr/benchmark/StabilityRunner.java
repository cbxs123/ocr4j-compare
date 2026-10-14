package com.compare.ocr.benchmark;

import com.compare.ocr.engine.OcrEngineAdapter;
import com.compare.ocr.monitor.ResourceMonitor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class StabilityRunner {

    /**
     * 一次长跑中采集的 RSS 样本数。Windows 会在内存压力下回收进程工作集，单点采样会出现
     * 几十甚至上百 MB 的抖动，因此按窗口取中位数后才用于判断趋势。
     */
    private static final int RSS_SAMPLE_COUNT = 20;

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

        List<Long> latencies = new ArrayList<>();
        List<Double> rssSamples = new ArrayList<>();
        int sampleInterval = Math.max(1, totalRequests / RSS_SAMPLE_COUNT);

        for (int i = 0; i < totalRequests; i++) {
            try {
                long start = System.nanoTime();
                engine.run(imageBytes);
                latencies.add(System.nanoTime() - start);
            } catch (Exception e) {
                log.error("Stability test error at iteration {}", i, e);
            }

            if (i % sampleInterval == 0) {
                rssSamples.add(monitor.captureSnapshot().nativeRssMB());
            }
        }

        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        rssSamples.add(monitor.captureSnapshot().nativeRssMB());

        int window = Math.max(1, rssSamples.size() / 5);
        double initialRss = windowMedian(rssSamples, 0.0, window);
        double midRss = windowMedian(rssSamples, 0.5, window);
        double finalRss = windowMedian(rssSamples, 1.0, window);
        double rssGrowth = finalRss - initialRss;
        double avgLatency = latencies.stream().mapToDouble(Long::doubleValue).average().orElse(0.0) / 1_000_000.0;

        // Leak heuristic: 稳定运行后仍在持续单调增长才判定为疑似泄漏
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

    /**
     * 取窗口内样本的中位数。
     *
     * @param samples 采样序列，按时间先后排列
     * @param centerFraction 窗口中心在采样序列中的相对位置，0.0 为起点、1.0 为终点
     * @param window 窗口长度
     * @return 窗口内的中位数
     */
    private static double windowMedian(List<Double> samples, double centerFraction, int window) {
        int size = samples.size();
        int length = Math.min(window, size);
        int start = (int) Math.round(centerFraction * (size - length));
        start = Math.max(0, Math.min(start, size - length));
        List<Double> slice = new ArrayList<>(samples.subList(start, start + length));
        Collections.sort(slice);
        int middle = slice.size() / 2;
        return slice.size() % 2 == 1 ? slice.get(middle) : (slice.get(middle - 1) + slice.get(middle)) / 2.0;
    }
}
