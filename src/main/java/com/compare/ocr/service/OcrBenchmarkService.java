package com.compare.ocr.service;

import com.compare.ocr.benchmark.*;
import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.OcrEngineAdapter;
import com.compare.ocr.engine.OcrEngineResult;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import com.compare.ocr.engine.XbergOcrAdapter;
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
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrBenchmarkService {

    private static final String MICA_NAME = "mica-ppocr";
    private static final String RAPID_NAME = "RapidOCR4j";
    private static final String XBERG_NAME = "Xberg";

    private final OcrProperties properties;
    private final OcrEngineFactory engineFactory;

    public String runFullBenchmark() {
        log.info("=== Starting OCR Benchmark & Comparison (Spring Boot 3.5.6 / JDK 21 / PP-OCRv6 tiny) ===");

        // 1. Prepare resources
        ResourceDownloader.prepareAll(properties);
        // Xberg 自带 ONNX Runtime，先落地以便让整个进程绑定到同一份原生运行时
        XbergOcrAdapter.BootstrapResult xbergRuntime = XbergOcrAdapter.bootstrap(properties);

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
        ColdStartEvaluator.ColdStartResult xbergColdStart =
                ColdStartEvaluator.evaluateXberg(mediumImg, properties, intraThreads, xbergRuntime.elapsedNanos());
        ColdStartEvaluator.ColdStartResult micaColdStart =
                ColdStartEvaluator.evaluateMica(mediumImg, properties, intraThreads);
        ColdStartEvaluator.ColdStartResult rapidColdStart =
                ColdStartEvaluator.evaluateRapid(mediumImg, properties, intraThreads);
        coldStartResults.add(micaColdStart);
        coldStartResults.add(rapidColdStart);
        coldStartResults.add(xbergColdStart);

        // 2.5 Xberg 预热：把 3 个 ONNX 会话的惰性建立成本（实测首帧 ~1500ms）移出后续测量段。
        // 必须放在冷启动评测之后，否则会污染 ColdStart 的首帧口径。
        OcrProperties.XbergProperties xbergProps = properties.getXberg();
        if (xbergProps.isWarmupEnabled()) {
            log.info("--- 1.5 Xberg Warmup (lazy ONNX sessions) ---");
            try (XbergOcrAdapter xberg = engineFactory.createXbergEngine(intraThreads)) {
                long warmupMs = xberg.warmup(xbergProps.getWarmupRounds());
                log.info("Xberg warmup finished: rounds={} totalMs={}", xbergProps.getWarmupRounds(), warmupMs);
            }
        }

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
            benchmarkAll(runner, benchmarkResults, cfg, intraThreads);
        }

        // Test Small & Large image at target concurrency C=2
        log.info("Benchmarking Small Line & Large Document at concurrency C=2");
        BenchmarkConfig smallCfg = new BenchmarkConfig(2, requestsPerBench, warmup, "小图-单行文本 (360x60)", smallImg);
        benchmarkAll(runner, benchmarkResults, smallCfg, intraThreads);

        BenchmarkConfig largeCfg = new BenchmarkConfig(2, Math.max(requestsPerBench / 2, 10), Math.max(warmup - 1, 1), "大图-密集文档 (1600x2200)", largeImg);
        benchmarkAll(runner, benchmarkResults, largeCfg, intraThreads);

        // 3.5 进程级分片验证：Xberg PaddleOCR 会话受进程内 mutex 串行，进程是并发唯一扩展单位。
        // 以 N 个 worker JVM（各 C=1、小线程预算）对比单进程 C=N 的 QPS，量化"进程换线程"的收益。
        if (xbergProps.getShardProcesses() > 0) {
            log.info("--- 2.5 Xberg Process-Shard Scaling ({} workers x C=1) ---", xbergProps.getShardProcesses());
            String mediumImagePath = Paths.get(imgDir, "medium_card.png").toString();
            XbergShardCoordinator.ShardSummary shards =
                    XbergShardCoordinator.runShards(properties, mediumImagePath);
            int targetC = xbergProps.getShardProcesses();
            benchmarkResults.stream()
                    .filter(r -> r.engineName().startsWith(XBERG_NAME)
                            && r.concurrency() == targetC
                            && r.sampleName().contains("中图"))
                    .findFirst()
                    .ifPresent(single -> log.info(
                            "Shard comparison: {} workers x C=1 totalQps={} | single-process C={} qps={} | scaling {}x",
                            shards.workers(), String.format("%.2f", shards.totalQps()),
                            targetC, String.format("%.2f", single.qps()),
                            String.format("%.2fx", shards.totalQps() / Math.max(single.qps(), 0.001))));
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
        try (XbergOcrAdapter xberg = engineFactory.createXbergEngine(intraThreads)) {
            stabilityResults.add(StabilityRunner.runStabilityTest(xberg, mediumImg, stabilityReqs));
        }

        // 6. Generate and Save Report to README.md
        log.info("--- 5. Generating Markdown Report ---");
        String markdown = ReportGenerator.generateMarkdownReport(
                benchmarkResults,
                coldStartResults,
                stabilityResults,
                accuracyResults,
                properties
        );

        String outputReadme = properties.getBenchmark().getOutputReadme();
        ReportGenerator.writeReadme(markdown, outputReadme);
        log.info("=== All Benchmark tests completed! {} updated successfully. ===", outputReadme);

        return markdown;
    }

    /**
     * 用同一份配置依次压测三种引擎。
     */
    private void benchmarkAll(BenchmarkRunner runner, List<BenchmarkResult> results, BenchmarkConfig cfg, int threads) {
        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine(threads)) {
            results.add(runner.runBenchmark(mica, cfg));
        }
        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine(threads)) {
            results.add(runner.runBenchmark(rapid, cfg));
        }
        try (XbergOcrAdapter xberg = engineFactory.createXbergEngine(threads)) {
            results.add(runner.runBenchmark(xberg, cfg));
        }
    }

    private void evalSingle(List<AccuracyEvaluator.EvalResult> list, String sampleName, byte[] imgBytes, String reference) {
        int threads = properties.getRuntime().getIntraOpNumThreads();
        evalOne(list, sampleName, reference, imgBytes, MICA_NAME, () -> engineFactory.createMicaEngine(threads));
        evalOne(list, sampleName, reference, imgBytes, RAPID_NAME, () -> engineFactory.createRapidEngine(threads));
        evalOne(list, sampleName, reference, imgBytes, XBERG_NAME, () -> engineFactory.createXbergEngine(threads));
    }

    private void evalOne(List<AccuracyEvaluator.EvalResult> list, String sampleName, String reference,
                         byte[] imgBytes, String engineName, Supplier<OcrEngineAdapter> engineSupplier) {
        String text = "";
        int lines = 0;
        try (OcrEngineAdapter engine = engineSupplier.get()) {
            OcrEngineResult res = engine.run(imgBytes);
            text = res.fullText();
            lines = res.lineCount();
        } catch (Exception e) {
            log.error("Accuracy evaluation failed: engine={} sample={}", engineName, sampleName, e);
        }
        double similarity = AccuracyEvaluator.computeSimilarity(text, reference);
        list.add(new AccuracyEvaluator.EvalResult(engineName, sampleName, lines, text.length(), text, similarity));
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
