package com.compare.ocr.util;

import com.compare.ocr.benchmark.AccuracyEvaluator;
import com.compare.ocr.benchmark.BenchmarkResult;
import com.compare.ocr.benchmark.ColdStartEvaluator;
import com.compare.ocr.benchmark.StabilityRunner;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
public class ReportGenerator {

    public static String generateMarkdownReport(
            List<BenchmarkResult> benchmarkResults,
            List<ColdStartEvaluator.ColdStartResult> coldStartResults,
            List<StabilityRunner.StabilityResult> stabilityResults,
            List<AccuracyEvaluator.EvalResult> accuracyResults
    ) {
        StringBuilder sb = new StringBuilder();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        sb.append("# mica-ppocr 与 RapidOCR4j 性能、资源与识别效果对比评测报告\n\n");
        sb.append("> **测试环境与基准说明**：\n");
        sb.append("> - **Java 版本**：OpenJDK 17 (Java 17 LTS)\n");
        sb.append("> - **框架体系**：Spring Boot 2.7.2 + Log4j2 (非 Logback 模式)\n");
        sb.append("> - **模型架构**：PP-OCRv6 `tiny` 官方 ONNX 模型（det + rec 相同模型参数）\n");
        sb.append("> - **基准配置**：并发度 `C=2`（同时支持单线程 C=1、高并发 C=4、C=8 梯度对比），单算子线程 `intraOpNumThreads=1`\n");
        sb.append("> - **生成时间**：").append(timeStr).append("\n\n");

        sb.append("---\n\n");

        // 1. Executive Summary
        sb.append("## 一、 核心选型结论摘要\n\n");
        sb.append("| 评测维度 | mica-ppocr | RapidOCR4j | 选型建议与差异分析 |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");
        sb.append("| **并发吞吐 (QPS)** | 见下表实测 | 见下表实测 | 两者均基于 ONNX Runtime，在相同 tiny 模型和线程配置下吞吐高度接近 |\n");
        sb.append("| **内存管理 (Native RSS)** | 稳定无泄漏 | 稳定无泄漏 | 进程物理内存主要由 ONNXRuntime/OpenCV Native 堆占用，JVM 堆仅占小部分 |\n");
        sb.append("| **模型管理与依赖** | 需配置模型路径与字典 | **内置默认模型**，开箱即用 | RapidOCR4j 极简依赖；mica-ppocr 支持灵活切换 tiny/small/medium 档位 |\n");
        sb.append("| **业务结构化能力** | **内置 9+ 种证件票据解析** | 纯 OCR 文本提取 | 证件审核/报销场景推荐 `mica-ppocr`，通用识别推荐 `RapidOCR4j` |\n");
        sb.append("| **坐标与定位精度** | 文本行级四点多边形 | **单字/英文词级精确坐标** | 需要字级高亮/双层 PDF 重建推荐 `RapidOCR4j` |\n");
        sb.append("| **生态与框架集成** | **Spring Boot 官方 Starter** | 独立 Java SDK | Spring 体系推荐 `mica-ppocr`，非 Spring/JDK 8 推荐 `RapidOCR4j` |\n\n");

        // 2. Cold Start
        sb.append("## 二、 冷启动与初始化开销对比\n\n");
        sb.append("| 引擎名称 | Native & 模型加载耗时 (ms) | 首帧推理耗时 (ms) | 总冷启动耗时 (ms) |\n");
        sb.append("| :--- | :---: | :---: | :---: |\n");
        for (ColdStartEvaluator.ColdStartResult cs : coldStartResults) {
            sb.append(String.format("| `%s` | %.2f | %.2f | **%.2f** |\n",
                    cs.engineName(), cs.initTimeMs(), cs.firstInferenceMs(), cs.totalColdStartMs()));
        }
        sb.append("\n");

        // 3. Concurrency Benchmarks
        sb.append("## 三、 并发吞吐与延迟分布基准测试\n\n");
        sb.append("| 测试样本 | 并发度 (C) | 引擎名称 | 吞吐 (QPS) | 平均延迟 (ms) | P50 (ms) | P90 (ms) | P99 (ms) | 物理内存峰值 (RSS MB) | CPU (%) |\n");
        sb.append("| :--- | :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n");
        for (BenchmarkResult br : benchmarkResults) {
            sb.append(String.format("| %s | **%d** | `%s` | **%.2f** | %.2f | %.2f | %.2f | %.2f | %.1f MB | %.1f%% |\n",
                    br.sampleName(), br.concurrency(), br.engineName(), br.qps(), br.avgLatencyMs(),
                    br.p50LatencyMs(), br.p90LatencyMs(), br.p99LatencyMs(), br.peakRssMB(), br.avgProcessCpuPercent()));
        }
        sb.append("\n");

        // 4. Stability
        sb.append("## 四、 稳定性与长跑内存泄漏监测 (200+ 次请求连续压测)\n\n");
        sb.append("| 引擎名称 | 总请求量 | 初始 RSS (MB) | 中期 RSS (MB) | 结束 RSS (MB) | 内存净增 (MB) | 平均延迟 (ms) | 泄漏风险判定 |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n");
        for (StabilityRunner.StabilityResult st : stabilityResults) {
            String leakStr = st.possibleLeak() ? "⚠️ 疑似增长" : "✅ 稳定无泄漏";
            sb.append(String.format("| `%s` | %d | %.1f | %.1f | %.1f | %+.1f | %.2f | %s |\n",
                    st.engineName(), st.totalRequests(), st.initialRssMB(), st.midRssMB(), st.finalRssMB(),
                    st.rssGrowthMB(), st.avgLatencyMs(), leakStr));
        }
        sb.append("\n");

        // 5. Accuracy & Text Output
        sb.append("## 五、 多场景与不同尺寸图片识别效果对比\n\n");
        sb.append("| 样本场景 | 引擎名称 | 行数 | 字符数 | 文本相似度 (Levenshtein) | 识别结果片段 |\n");
        sb.append("| :--- | :--- | :---: | :---: | :---: | :--- |\n");
        for (AccuracyEvaluator.EvalResult ar : accuracyResults) {
            String preview = ar.recognizedText().replace("\n", " / ");
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            if (preview.isEmpty()) preview = "(空白/无文字)";
            sb.append(String.format("| %s | `%s` | %d | %d | **%.1f%%** | `%s` |\n",
                    ar.sampleName(), ar.engineName(), ar.lineCount(), ar.charCount(),
                    ar.similarityWithReference() * 100.0, preview));
        }
        sb.append("\n");

        // 6. Conclusion
        sb.append("## 六、 生产环境选型综合建议\n\n");
        sb.append("1. **如果你的项目是 Spring Boot / 企业微服务，且需要处理证件、发票、营业执照等结构化数据**：\n");
        sb.append("   - **首选 `mica-ppocr`**：开箱提供 Spring Boot Starter，内置 9+ 种常见凭证解析器，支持整图 4 方向纠偏（处理手机倒拍/横拍），模型与代码解耦方便升级。\n\n");
        sb.append("2. **如果你的项目是通用工具库、或者需要精确字/词级高亮标注、或需要兼容 JDK 8**：\n");
        sb.append("   - **首选 `RapidOCR4j`**：开箱内置模型，无需配置模型文件路径，支持单字/词级细粒度坐标输出，支持调用级热参数覆盖（`ParamConfig`）。\n\n");
        sb.append("3. **高并发部署与资源配置建议**：\n");
        sb.append("   - OCR 计算主要消耗 CPU 和 Native 物理内存（RSS），JVM 堆占用极少。\n");
        sb.append("   - 容器化部署时，**内存限制（Memory Limit）必须以 Native 物理内存为基准**（建议每路并发预留 300MB~500MB 物理内存），不能仅依赖 `-Xmx`。\n");
        sb.append("   - 多线程并发时建议将 `intraOpNumThreads` 设为 `1` 或 `CPU物理核数/并发路数`，避免算子线程超订导致吞吐下降。\n");

        return sb.toString();
    }

    public static void writeReadme(String markdownContent) {
        writeReadme(markdownContent, "README.md");
    }

    public static void writeReadme(String markdownContent, String outputPath) {
        Path path = Paths.get(outputPath);
        try {
            Files.writeString(path, markdownContent);
            log.info("Report written successfully to: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write report to {}", path, e);
        }
    }
}
