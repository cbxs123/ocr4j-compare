package com.compare.ocr.service;

import com.compare.ocr.benchmark.*;
import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import com.compare.ocr.util.ReportGenerator;
import com.compare.ocr.util.ResourceDownloader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrBenchmarkService {

    private final OcrProperties properties;
    private final OcrEngineFactory engineFactory;

    public String runFullBenchmark() {
        log.info("=== Starting OCR Benchmark & Comparison (Spring Boot 2.7.2 / PP-OCRv6 tiny) ===");

        // 1. Prepare resources
        ResourceDownloader.prepareAll(properties);

        String imgDir = properties.getBenchmark().getImagesBaseDir();
        byte[] smallImg = readImage(Paths.get(imgDir, "small_single_line.png").toString());
        byte[] mediumImg = readImage(Paths.get(imgDir, "medium_card.png").toString());
        byte[] largeImg = readImage(Paths.get(imgDir, "large_document.png").toString());
        byte[] blankImg = readImage(Paths.get(imgDir, "blank_white.png").toString());
        byte[] noisyImg = readImage(Paths.get(imgDir, "noisy_sample.png").toString());

        int intraThreads = properties.getRuntime().getIntraOpNumThreads();

        // 2. Cold Start Evaluation
        log.info("--- 1. Evaluating Cold Start ---");
        List<ColdStartEvaluator.ColdStartResult> coldStartResults = new ArrayList<>();
        coldStartResults.add(ColdStartEvaluator.evaluateMica(mediumImg, properties, intraThreads));
        coldStartResults.add(ColdStartEvaluator.evaluateRapid(mediumImg, properties, intraThreads));

        // 3. Concurrency Benchmarks
        log.info("--- 2. Running Concurrency Benchmarks ---");
        List<BenchmarkResult> benchmarkResults = new ArrayList<>();
        BenchmarkRunner runner = new BenchmarkRunner();

        List<Integer> concurrencies = properties.getBenchmark().getConcurrencies();
        int requestsPerBench = properties.getBenchmark().getRequestsPerBench();
        int warmup = properties.getBenchmark().getWarmupRequests();

        // Test Medium image across concurrencies
        for (int c : concurrencies) {
            log.info("Benchmarking Medium Card at concurrency C={}", c);
            BenchmarkConfig cfg = new BenchmarkConfig(c, requestsPerBench, warmup, "中图-证件卡片 (800x500)", mediumImg);

            try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(intraThreads)) {
                benchmarkResults.add(runner.runBenchmark(mica, cfg));
            }
            try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(intraThreads)) {
                benchmarkResults.add(runner.runBenchmark(rapid, cfg));
            }
        }

        // Test Small & Large image at target concurrency C=2
        log.info("Benchmarking Small Line & Large Document at concurrency C=2");
        BenchmarkConfig smallCfg = new BenchmarkConfig(2, requestsPerBench, warmup, "小图-单行文本 (360x60)", smallImg);
        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(intraThreads)) {
            benchmarkResults.add(runner.runBenchmark(mica, smallCfg));
        }
        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(intraThreads)) {
            benchmarkResults.add(runner.runBenchmark(rapid, smallCfg));
        }

        BenchmarkConfig largeCfg = new BenchmarkConfig(2, Math.max(requestsPerBench / 2, 10), Math.max(warmup - 1, 1), "大图-密集文档 (1600x2200)", largeImg);
        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(intraThreads)) {
            benchmarkResults.add(runner.runBenchmark(mica, largeCfg));
        }
        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(intraThreads)) {
            benchmarkResults.add(runner.runBenchmark(rapid, largeCfg));
        }

        // 4. Accuracy & Edge Case Testing
        log.info("--- 3. Evaluating Recognition Accuracy & Robustness ---");
        List<AccuracyEvaluator.EvalResult> accuracyResults = new ArrayList<>();

        evalSingle(accuracyResults, "小图-单行文本", smallImg, "浙A·88888 电子通行证");
        evalSingle(accuracyResults, "中图-证件卡片", mediumImg, "中华人民共和国居民身份证 姓名：张三 性别：男 民族：汉 出生：1990年10月24日 住址：北京市海淀区中关村南大街1号院 公民身份号码：110108199010241234");
        evalSingle(accuracyResults, "大图-密集文档", largeImg, "技术评估与架构选型规范报告 一、项目背景与评估目标 在现代化高并发微服务体系建设中");
        evalSingle(accuracyResults, "边界-全白空白图", blankImg, "");
        evalSingle(accuracyResults, "边界-噪点验证码", noisyImg, "验证码防伪标识 CODE-9527");

        // 5. Stability & Leak Testing
        log.info("--- 4. Running Stability & Memory Leak Test ---");
        List<StabilityRunner.StabilityResult> stabilityResults = new ArrayList<>();
        int stabilityReqs = properties.getBenchmark().getStabilityRequests();
        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(intraThreads)) {
            stabilityResults.add(StabilityRunner.runStabilityTest(mica, mediumImg, stabilityReqs));
        }
        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(intraThreads)) {
            stabilityResults.add(StabilityRunner.runStabilityTest(rapid, mediumImg, stabilityReqs));
        }

        // 6. Generate and Save Report to README.md
        log.info("--- 5. Generating Markdown Report ---");
        String markdown = ReportGenerator.generateMarkdownReport(
                benchmarkResults,
                coldStartResults,
                stabilityResults,
                accuracyResults
        );

        String outputReadme = properties.getBenchmark().getOutputReadme();
        ReportGenerator.writeReadme(markdown, outputReadme);
        log.info("=== All Benchmark tests completed! {} updated successfully. ===", outputReadme);

        return markdown;
    }

    private void evalSingle(List<AccuracyEvaluator.EvalResult> list, String sampleName, byte[] imgBytes, String reference) {
        String micaText = "";
        String rapidText = "";
        int micaLines = 0;
        int rapidLines = 0;
        int intraThreads = properties.getRuntime().getIntraOpNumThreads();

        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(intraThreads)) {
            var res = mica.run(imgBytes);
            micaText = res.fullText();
            micaLines = res.lineCount();
        } catch (Exception e) {
            log.error("Mica eval error on " + sampleName, e);
        }

        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(intraThreads)) {
            var res = rapid.run(imgBytes);
            rapidText = res.fullText();
            rapidLines = res.lineCount();
        } catch (Exception e) {
            log.error("Rapid eval error on " + sampleName, e);
        }

        double micaSim = AccuracyEvaluator.computeSimilarity(micaText, reference);
        double rapidSim = AccuracyEvaluator.computeSimilarity(rapidText, reference);

        list.add(new AccuracyEvaluator.EvalResult("mica-ppocr", sampleName, micaLines, micaText.length(), micaText, micaSim));
        list.add(new AccuracyEvaluator.EvalResult("RapidOCR4j", sampleName, rapidLines, rapidText.length(), rapidText, rapidSim));
    }

    private byte[] readImage(String pathStr) {
        try {
            Path path = Paths.get(pathStr);
            return Files.readAllBytes(path);
        } catch (Exception e) {
            log.error("Failed to read image: {}", pathStr, e);
            return new byte[0];
        }
    }
}
