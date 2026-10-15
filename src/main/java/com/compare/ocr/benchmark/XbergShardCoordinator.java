package com.compare.ocr.benchmark;

import com.compare.ocr.config.OcrProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Xberg 进程级分片执行器：fork 出 N 个 worker JVM，各自以 C=1 跑同一张图的基准，聚合总 QPS。
 *
 * <p>Xberg 的 PaddleOCR ONNX 会话受进程级 mutex 保护（官方文档：PaddleOCR pages do not run
 * concurrently），进程内加线程无法突破串行；因此并发扩展的唯一单位是进程。worker 侧由
 * {@code ocr.benchmark.shard-role=worker} 触发（见 OcrBenchmarkCommandLineRunner），
 * 以 {@code SHARD_RESULT|qps|avgMs|success|errors} 单行协议回传结果。
 */
@Slf4j
public final class XbergShardCoordinator {

    /** worker 回传结果行的前缀，父进程按此解析。 */
    public static final String RESULT_PREFIX = "SHARD_RESULT";

    private static final String MAIN_CLASS = "com.compare.ocr.OcrCompareApplication";

    private XbergShardCoordinator() {
    }

    /**
     * @param workers 成功回传结果的 worker 数
     * @param totalQps 各 worker QPS 之和
     * @param avgLatencyMs 按成功请求数加权的平均延迟
     * @param workerQps 每个 worker 的 QPS
     */
    public record ShardSummary(int workers, double totalQps, double avgLatencyMs, List<Double> workerQps) {
    }

    /**
     * 启动 {@code ocr.xberg.shard-processes} 个 worker JVM 并聚合结果。
     *
     * @param properties 工程配置
     * @param imagePath 分片 worker 使用的图片路径（相对或绝对均可，子进程继承当前工作目录）
     * @return 分片聚合结果；全部 worker 失败时 totalQps 为 0
     */
    public static ShardSummary runShards(OcrProperties properties, String imagePath) {
        OcrProperties.XbergProperties xberg = properties.getXberg();
        int workers = xberg.getShardProcesses();
        int requests = xberg.getShardRequestsPerWorker();

        String javaBinary = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        List<Process> processes = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            List<String> command = new ArrayList<>(List.of(
                    javaBinary,
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    MAIN_CLASS,
                    "--spring.main.banner-mode=off",
                    "--ocr.benchmark.auto-run-on-startup=false",
                    "--ocr.benchmark.shard-role=worker",
                    "--ocr.benchmark.shard-image=" + imagePath,
                    "--ocr.benchmark.shard-requests=" + requests,
                    "--ocr.xberg.max-threads=" + xberg.getShardWorkerThreads()));
            try {
                processes.add(new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start());
            } catch (Exception e) {
                log.error("Failed to launch Xberg shard worker #{}", i, e);
            }
        }

        List<Double> workerQps = new ArrayList<>();
        double weightedLatencySum = 0.0;
        int successTotal = 0;
        for (Process process : processes) {
            try {
                double[] parsed = null;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(RESULT_PREFIX)) {
                            parsed = parseResult(line);
                        }
                    }
                }
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    log.warn("Xberg shard worker timeout, destroying");
                    process.destroyForcibly();
                }
                if (parsed != null) {
                    workerQps.add(parsed[0]);
                    weightedLatencySum += parsed[1] * parsed[2];
                    successTotal += (int) parsed[2];
                } else {
                    log.warn("Xberg shard worker exited without result (code={})", process.exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Xberg shard worker failed", e);
            }
        }

        double totalQps = workerQps.stream().mapToDouble(Double::doubleValue).sum();
        double avgLatencyMs = successTotal == 0 ? 0.0 : weightedLatencySum / successTotal;
        log.info("Xberg process shards done: workers={} requestsEach={} workerQps={} totalQps={} avgLatencyMs={} elapsedMs={}",
                workers, requests, workerQps, String.format("%.2f", totalQps),
                String.format("%.2f", avgLatencyMs), (System.nanoTime() - start) / 1_000_000);
        return new ShardSummary(workerQps.size(), totalQps, avgLatencyMs, workerQps);
    }

    /** 解析 {@code SHARD_RESULT|qps|avgLatencyMs|successCount|errorCount}。 */
    private static double[] parseResult(String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 5) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
            };
        } catch (NumberFormatException e) {
            log.warn("Malformed shard result line: {}", line);
            return null;
        }
    }
}
