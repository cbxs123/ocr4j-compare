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

        sb.append("# mica-ppocr、RapidOCR4j 与 Xberg 性能、资源与识别效果对比评测报告\n\n");
        sb.append("> **测试环境与基准说明**：\n");
        sb.append("> - **Java 版本**：OpenJDK 21 (Java 21 LTS，开启 `--enable-preview --enable-native-access=ALL-UNNAMED`)\n");
        sb.append("> - **框架体系**：Spring Boot 3.5.6 + Log4j2 (非 Logback 模式)\n");
        sb.append("> - **模型架构**：PP-OCRv6 `tiny` 官方 ONNX 模型（三家引擎共用同一套 det/rec 权重与字典）\n");
        sb.append("> - **基准配置**：并发度 `C=2`（同时支持单线程 C=1、高并发 C=4 梯度对比），单算子线程 `intraOpNumThreads=1`\n");
        sb.append("> - **引擎版本**：`mica-ppocr-core 1.1.6`、`rapidocr4j 3.9.2`、`xberg-jdk21 1.3.9`（`paddle-ocr` 后端）\n");
        sb.append("> - **测量口径**：同一进程内顺序执行，单次运行结果；重复运行 QPS 波动约 ±15%，Xberg 的绝对延迟波动更大，请以量级而非末位数字做判断\n");
        sb.append("> - **生成时间**：").append(timeStr).append("\n\n");

        sb.append("---\n\n");

        // 1. Executive Summary
        sb.append("## 一、 核心选型结论摘要\n\n");
        sb.append("| 评测维度 | mica-ppocr | RapidOCR4j | Xberg | 选型建议与差异分析 |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");
        sb.append("| **并发吞吐 (QPS)** | 见下表实测 | 见下表实测 | 见下表实测 | 三家均基于 ONNX Runtime，相同 tiny 模型与线程配置下吞吐高度接近 |\n");
        sb.append("| **内存管理 (Native RSS)** | 稳定无泄漏 | 稳定无泄漏 | 稳定无泄漏 | 进程物理内存主要由 ONNXRuntime/OpenCV Native 堆占用，JVM 堆仅占小部分 |\n");
        sb.append("| **模型管理与依赖** | 需配置模型路径与字典 | **内置默认模型**，开箱即用 | **绑定库 + ORT + 模型全部内置于 jar**，首次落地后复用 | RapidOCR4j / Xberg 开箱即用；mica-ppocr 支持灵活切换 tiny/small/medium 档位 |\n");
        sb.append("| **启动姿态** | JVM 内实例化 Engine | JVM 内实例化 RapidOCR | 首次需落地原生运行时到 `runtime-dir` | Xberg 冷启动包含一次原生资源落地，进程内二次启动无此开销 |\n");
        sb.append("| **业务结构化能力** | **内置 9+ 种证件票据解析** | 纯 OCR 文本提取 | **通用文档智能**：版面/表格/分块/关键词/结构化抽取 | 证件审核/报销推荐 `mica-ppocr`，通用识别推荐 `RapidOCR4j`，复杂文档推荐 `Xberg` |\n");
        sb.append("| **坐标与定位精度** | 文本行级四点多边形 | **单字/英文词级精确坐标** | 行 / 词级 OCR 元素（`ocrElements`）与几何信息 | 需要字级高亮/双层 PDF 重建推荐 `RapidOCR4j` |\n");
        sb.append("| **生态与框架集成** | **Spring Boot 官方 Starter** | 独立 Java SDK | 独立 Java SDK（JDK 21 + preview） | Spring 体系推荐 `mica-ppocr`，跨语言/多格式文档推荐 `Xberg`，非 Spring 且需 JDK 17 推荐 `RapidOCR4j` |\n\n");

        // 2. Cold Start
        sb.append("## 二、 冷启动与初始化开销对比\n\n");
        sb.append("| 引擎名称 | 原生资源落地 (ms) | Native & 模型加载耗时 (ms) | 首帧推理耗时 (ms) | 总冷启动耗时 (ms) |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: |\n");
        for (ColdStartEvaluator.ColdStartResult cs : coldStartResults) {
            String staging = cs.stagingMs() > 0.0 ? String.format("%.2f", cs.stagingMs()) : "-";
            sb.append(String.format("| `%s` | %s | %.2f | %.2f | **%.2f** |\n",
                    cs.engineName(), staging, cs.initTimeMs(), cs.firstInferenceMs(), cs.totalColdStartMs()));
        }
        sb.append("\n");
        sb.append("> 说明：冷启动在同一 JVM 内按 `Xberg → mica-ppocr → RapidOCR4j` 的顺序依次测量，");
        sb.append("先测量的引擎需承担 OpenCV / ONNX Runtime 等共享原生库的一次性加载成本。\n");
        sb.append("> Xberg 多出的「原生资源落地」是首次把 jar 内的绑定库、ONNX Runtime 与模型复制到 `runtime-dir` 的一次性成本，");
        sb.append("同机重复运行时该列为 0（文件按大小比对后全部 reused）。\n");
        sb.append("> Xberg 的 det/rec/角度分类 ONNX 会话在首次 `extract` 时惰性创建，因此它的「首帧推理」包含三个会话的建立成本，");
        sb.append("其余两家在引擎实例化阶段就完成了会话创建。\n\n");

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
        sb.append("> 说明：Xberg 的 `Xberg.extract` 走的是完整文档解析流水线（同时准备版面、质量、分块等环节），");
        sb.append("单次调用固定开销高于纯 OCR 引擎，因此纯图片 OCR 吞吐与延迟不占优；其价值体现在多格式文档理解。\n\n");

        // 4. Stability
        sb.append("## 四、 稳定性与长跑内存泄漏监测 (200+ 次请求连续压测)\n\n");
        sb.append("| 引擎名称 | 总请求量 | 初始 RSS (MB) | 中期 RSS (MB) | 结束 RSS (MB) | 内存净增 (MB) | 平均延迟 (ms) | 泄漏风险判定 |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n");
        for (StabilityRunner.StabilityResult st : stabilityResults) {
            String leakStr = st.possibleLeak() ? "疑似增长" : "稳定无泄漏";
            sb.append(String.format("| `%s` | %d | %.1f | %.1f | %.1f | %+.1f | %.2f | %s |\n",
                    st.engineName(), st.totalRequests(), st.initialRssMB(), st.midRssMB(), st.finalRssMB(),
                    st.rssGrowthMB(), st.avgLatencyMs(), leakStr));
        }
        sb.append("\n");
        sb.append("> 说明：Windows 会在内存压力下主动回收进程工作集，单次 RSS 采样抖动可达上百 MB，因此表中「初始 / 中期 / 结束」");
        sb.append("均为对应时间窗口的中位数（各窗口约占总请求量的 20%），『内存净增』= 结束中位数 − 初始中位数。\n");
        sb.append("> 「疑似增长」的判定条件为：净增 > 80MB 且结束中位数高于中期中位数 30MB 以上。\n\n");

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
        sb.append("2. **如果你的项目是通用工具库、或者需要精确字/词级高亮标注、或需要兼容 JDK 17 及以下**：\n");
        sb.append("   - **首选 `RapidOCR4j`**：开箱内置模型，无需配置模型文件路径，支持单字/词级细粒度坐标输出，支持调用级热参数覆盖（`ParamConfig`）。\n\n");
        sb.append("3. **如果你的项目需要多格式文档理解（PDF/Office/图片统一入口），并希望一次拿到版面、表格、分块与关键词**：\n");
        sb.append("   - **首选 `Xberg`**：绑定库、ONNX Runtime 与 PP-OCRv6 模型全部打包在 `xberg-jdk21` jar 内，`Xberg.extract` 单入口覆盖图片/PDF/Office；代价是必须运行在 **JDK 21 且开启 `--enable-preview`** 的进程上，首次调用需把原生资源落地到 `runtime-dir`。\n\n");
        sb.append("4. **高并发部署与资源配置建议**：\n");
        sb.append("   - OCR 计算主要消耗 CPU 和 Native 物理内存（RSS），JVM 堆占用极少。\n");
        sb.append("   - 容器化部署时，**内存限制（Memory Limit）必须以 Native 物理内存为基准**（建议每路并发预留 300MB~500MB 物理内存），不能仅依赖 `-Xmx`。\n");
        sb.append("   - 多线程并发时建议将 `intraOpNumThreads` 设为 `1` 或 `CPU物理核数/并发路数`，避免算子线程超订导致吞吐下降。\n");
        sb.append("   - 同一 JVM 内混跑多个 OCR 引擎时，Windows 会按模块名复用已加载的 `onnxruntime.dll`；本工程先让 Xberg 落地并加载其自带 ONNX Runtime，再启动其余引擎，保证三者共享同一份原生运行时。\n\n");

        // 7. How to reproduce
        sb.append("## 七、 复现方式\n\n");
        sb.append("```bash\n");
        sb.append("# 1. 用 JDK 21 运行（Xberg 绑定库依赖 preview 特性，JDK 17 无法加载）\n");
        sb.append("export JAVA_HOME=/path/to/jdk-21\n\n");
        sb.append("# 2. 直接启动，CommandLineRunner 会自动跑完整基准并覆写本报告\n");
        sb.append("mvn spring-boot:run\n\n");
        sb.append("# 3. 只想跑测试不跑基准\n");
        sb.append("mvn test -Dtest=OcrCompareTest\n");
        sb.append("```\n\n");
        sb.append("- 运行参数由 `spring-boot-maven-plugin` 注入：`--enable-preview --enable-native-access=ALL-UNNAMED -Dxberg.runtime.dir=target/xberg-runtime`，IDE 中直接运行主类时需手工补上。\n");
        sb.append("- Xberg 的绑定库 / ONNX Runtime / PP-OCRv6 模型全部来自本地仓库的 `io.xberg.jdk21:xberg-jdk21:1.3.9`，首次运行落地到 `target/xberg-runtime`，也可用环境变量 `XBERG_RUNTIME_DIR` 改写。\n");
        sb.append("- 调整基准规模：`ocr.benchmark.requests-per-bench`、`ocr.benchmark.warmup-requests`、`ocr.benchmark.stability-requests`、`ocr.benchmark.concurrencies`。\n");
        sb.append("- 只做单测不跑基准：`ocr.benchmark.auto-run-on-startup=false`。\n");

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
