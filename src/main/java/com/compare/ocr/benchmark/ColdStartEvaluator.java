package com.compare.ocr.benchmark;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ColdStartEvaluator {

    public record ColdStartResult(
            String engineName,
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
        return new ColdStartResult("mica-ppocr", initMs, firstRunMs, initMs + firstRunMs);
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
        return new ColdStartResult("RapidOCR4j", initMs, firstRunMs, initMs + firstRunMs);
    }
}
