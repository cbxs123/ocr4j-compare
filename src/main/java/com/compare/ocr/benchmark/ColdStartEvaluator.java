package com.compare.ocr.benchmark;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import com.compare.ocr.engine.XbergOcrAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ColdStartEvaluator {

    /**
     * @param engineName 引擎名
     * @param stagingMs 原生资源落地耗时，只有 Xberg 需要（其余引擎为 0）
     * @param initTimeMs 引擎实例化与原生库/模型加载耗时
     * @param firstInferenceMs 首帧推理耗时
     * @param totalColdStartMs 三者之和
     */
    public record ColdStartResult(
            String engineName,
            double stagingMs,
            double initTimeMs,
            double firstInferenceMs,
            double totalColdStartMs
    ) {
    }

    public static ColdStartResult evaluateMica(byte[] testImage, int threads) {
        return evaluateMica(testImage, new OcrProperties(), threads);
    }

    public static ColdStartResult evaluateMica(byte[] testImage, OcrProperties properties, int threads) {
        log.info("Evaluating cold start for mica-ppocr...");
        long t0 = System.nanoTime();
        MicaPPOcrAdapter adapter = new MicaPPOcrAdapter(properties, threads);
        long t1 = System.nanoTime();
        try {
            adapter.run(testImage);
        } catch (Exception e) {
            log.error("First inference error in mica: ", e);
        }
        long t2 = System.nanoTime();
        adapter.close();

        double initMs = (t1 - t0) / 1_000_000.0;
        double firstRunMs = (t2 - t1) / 1_000_000.0;
        return new ColdStartResult("mica-ppocr", 0.0, initMs, firstRunMs, initMs + firstRunMs);
    }

    public static ColdStartResult evaluateRapid(byte[] testImage, int threads) {
        return evaluateRapid(testImage, new OcrProperties(), threads);
    }

    public static ColdStartResult evaluateRapid(byte[] testImage, OcrProperties properties, int threads) {
        log.info("Evaluating cold start for RapidOCR4j...");
        long t0 = System.nanoTime();
        RapidOcr4jAdapter adapter = new RapidOcr4jAdapter(properties, threads);
        long t1 = System.nanoTime();
        try {
            adapter.run(testImage);
        } catch (Exception e) {
            log.error("First inference error in RapidOCR4j: ", e);
        }
        long t2 = System.nanoTime();
        adapter.close();

        double initMs = (t1 - t0) / 1_000_000.0;
        double firstRunMs = (t2 - t1) / 1_000_000.0;
        return new ColdStartResult("RapidOCR4j", 0.0, initMs, firstRunMs, initMs + firstRunMs);
    }

    public static ColdStartResult evaluateXberg(byte[] testImage, int threads) {
        return evaluateXberg(testImage, new OcrProperties(), threads, 0L);
    }

    public static ColdStartResult evaluateXberg(byte[] testImage, OcrProperties properties, int threads) {
        return evaluateXberg(testImage, properties, threads, 0L);
    }

    /**
     * Xberg 的冷启动包含「原生运行时落地」与「首个推理会话建立」两段，前者发生在
     * 进程启动阶段，需由调用方把耗时传进来才能得到完整的冷启动口径。
     *
     * @param runtimeBootstrapNanos 运行时落地耗时，已就绪时传 0
     */
    public static ColdStartResult evaluateXberg(byte[] testImage, OcrProperties properties, int threads,
                                                long runtimeBootstrapNanos) {
        log.info("Evaluating cold start for Xberg...");
        long t0 = System.nanoTime();
        XbergOcrAdapter adapter = new XbergOcrAdapter(properties, threads);
        long t1 = System.nanoTime();
        try {
            adapter.run(testImage);
        } catch (Exception e) {
            log.error("First inference error in Xberg: ", e);
        }
        long t2 = System.nanoTime();
        adapter.close();

        double stagingMs = runtimeBootstrapNanos / 1_000_000.0;
        double initMs = (t1 - t0) / 1_000_000.0;
        double firstRunMs = (t2 - t1) / 1_000_000.0;
        return new ColdStartResult("Xberg", stagingMs, initMs, firstRunMs, stagingMs + initMs + firstRunMs);
    }
}
