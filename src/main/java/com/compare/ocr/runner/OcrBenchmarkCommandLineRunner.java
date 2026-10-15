package com.compare.ocr.runner;

import com.compare.ocr.benchmark.BenchmarkConfig;
import com.compare.ocr.benchmark.BenchmarkResult;
import com.compare.ocr.benchmark.BenchmarkRunner;
import com.compare.ocr.benchmark.XbergShardCoordinator;
import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.XbergOcrAdapter;
import com.compare.ocr.service.OcrBenchmarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrBenchmarkCommandLineRunner implements CommandLineRunner {

    private final OcrProperties properties;
    private final OcrBenchmarkService benchmarkService;

    @Override
    public void run(String... args) {
        if ("worker".equalsIgnoreCase(properties.getBenchmark().getShardRole())) {
            runShardWorker();
            System.exit(0);
            return;
        }
        if (properties.getBenchmark().isAutoRunOnStartup()) {
            log.info("Auto-run benchmark on startup is ENABLED.");
            benchmarkService.runFullBenchmark();
        } else {
            log.info("Auto-run benchmark on startup is DISABLED (ocr.benchmark.auto-run-on-startup=false).");
        }
    }

    /**
     * 分片 worker 模式：只跑一轮 Xberg C=1 基准，并按
     * {@code SHARD_RESULT|qps|avgMs|success|errors} 协议向 stdout 回传一行结果，
     * 供父进程（XbergShardCoordinator）聚合。
     */
    private void runShardWorker() {
        OcrProperties.BenchmarkProperties bench = properties.getBenchmark();
        log.info("Xberg shard worker starting: image={} requests={} maxThreads={}",
                bench.getShardImage(), bench.getShardRequests(), properties.getXberg().getMaxThreads());
        try {
            byte[] image = Files.readAllBytes(Paths.get(bench.getShardImage()));
            BenchmarkConfig cfg = new BenchmarkConfig(1, bench.getShardRequests(), 2, "shard-worker", image);
            BenchmarkResult result;
            try (XbergOcrAdapter xberg = new XbergOcrAdapter(properties)) {
                result = new BenchmarkRunner().runBenchmark(xberg, cfg);
            }
            System.out.println(XbergShardCoordinator.RESULT_PREFIX + "|"
                    + result.qps() + "|" + result.avgLatencyMs() + "|"
                    + result.successCount() + "|" + result.errorCount());
            System.out.flush();
        } catch (Exception e) {
            log.error("Xberg shard worker failed", e);
        }
    }
}
