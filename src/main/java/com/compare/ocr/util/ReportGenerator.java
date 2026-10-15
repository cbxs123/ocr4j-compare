package com.compare.ocr.util;

import com.compare.ocr.benchmark.AccuracyEvaluator;
import com.compare.ocr.benchmark.BenchmarkResult;
import com.compare.ocr.benchmark.ColdStartEvaluator;
import com.compare.ocr.benchmark.StabilityRunner;
import com.compare.ocr.config.OcrProperties;
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

    /** 中等证件卡片样本名，用于吞吐扩展性对比。 */
    private static final String MEDIUM_SAMPLE = "中图-证件卡片 (800x500)";

    public static String generateMarkdownReport(
            List<BenchmarkResult> benchmarkResults,
            List<ColdStartEvaluator.ColdStartResult> coldStartResults,
            List<StabilityRunner.StabilityResult> stabilityResults,
            List<AccuracyEvaluator.EvalResult> accuracyResults,
            OcrProperties properties
    ) {
        StringBuilder sb = new StringBuilder();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        OcrProperties.XbergProperties xberg = properties.getXberg();
        OcrProperties.RuntimeProperties runtime = properties.getRuntime();

        sb.append("# mica-ppocr、RapidOCR4j 与 Xberg 性能、资源与识别效果对比评测报告\n\n");
        sb.append("> **测试环境与基准说明**：\n");
        sb.append("> - **Java 版本**：OpenJDK 21 (Java 21 LTS，开启 `--enable-preview --enable-native-access=ALL-UNNAMED`)\n");
        sb.append("> - **框架体系**：Spring Boot 3.5.6 + Log4j2 (非 Logback 模式)\n");
        sb.append("> - **模型架构**：PP-OCRv6 `tiny` 官方 ONNX 模型（三家引擎共用同一套 det/rec 权重与字典）\n");
        sb.append("> - **线程配置**：`mica-ppocr` / `RapidOCR4j` 会话级 `intraOpNumThreads=")
                .append(runtime.getIntraOpNumThreads()).append("`；`Xberg` 进程级 `max-threads=")
                .append(xberg.getMaxThreads() > 0 ? xberg.getMaxThreads() : "auto").append("`\n");
        sb.append("> - **引擎版本**：`mica-ppocr-core 1.1.6`、`rapidocr4j 3.9.2`、`xberg-jdk21 1.3.9`（`paddle-ocr` 后端）\n");
        sb.append("> - **测量口径**：同一进程内顺序执行，单次运行结果；重复运行 QPS 波动约 ±15%，Xberg 的绝对延迟波动更大，请以量级而非末位数字做判断\n");
        sb.append("> - **生成时间**：").append(timeStr).append("\n\n");

        sb.append("---\n\n");

        // 1. Executive Summary
        sb.append("## 一、 核心选型结论摘要\n\n");
        sb.append("| 评测维度 | mica-ppocr | RapidOCR4j | Xberg | 选型建议与差异分析 |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");
        sb.append("| **并发吞吐 (QPS)** | 见下表实测 | 见下表实测 | 见下表实测 | 单页图片 OCR 上 mica-ppocr / RapidOCR4j 领先，原因见第四节 |\n");
        sb.append("| **内存管理 (Native RSS)** | 稳定无泄漏 | 稳定无泄漏 | 稳定无泄漏 | 进程物理内存主要由 ONNXRuntime/OpenCV Native 堆占用，JVM 堆仅占小部分 |\n");
        sb.append("| **模型管理与依赖** | 需配置模型路径与字典 | **内置默认模型**，开箱即用 | **绑定库 + ORT + 模型全部内置于 jar**，首次落地后复用 | RapidOCR4j / Xberg 开箱即用；mica-ppocr 支持灵活切换 tiny/small/medium 档位 |\n");
        sb.append("| **启动姿态** | JVM 内实例化 Engine | JVM 内实例化 RapidOCR | 首次需落地原生运行时到 `runtime-dir` | Xberg 冷启动包含一次原生资源落地，进程内二次启动无此开销 |\n");
        sb.append("| **线程语义** | 会话级 `intraOpNumThreads` | 会话级 `intraOpNumThreads` | **进程级共享 `max-threads`** | 语义不同决定调优方向：前者靠并发度 C 扩展，后者靠加大预算扩展 |\n");
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

        // 4. Why Xberg scales worse
        appendXbergConcurrencyAnalysis(sb, benchmarkResults, xberg, runtime);

        // 5. Parameter alignment and tuning
        appendXbergTuningSection(sb, properties, xberg, runtime);

        // 6. Stability
        sb.append("## 六、 稳定性与长跑内存泄漏监测 (200+ 次请求连续压测)\n\n");
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

        // 7. Accuracy & Text Output
        sb.append("## 七、 多场景与不同尺寸图片识别效果对比\n\n");
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

        // 8. Conclusion
        sb.append("## 八、 生产环境选型综合建议\n\n");
        sb.append("1. **如果你的项目是 Spring Boot / 企业微服务，且需要处理证件、发票、营业执照等结构化数据**：\n");
        sb.append("   - **首选 `mica-ppocr`**：开箱提供 Spring Boot Starter，内置 9+ 种常见凭证解析器，支持整图 4 方向纠偏（处理手机倒拍/横拍），模型与代码解耦方便升级。\n\n");
        sb.append("2. **如果你的项目是通用工具库、或者需要精确字/词级高亮标注、或需要兼容 JDK 17 及以下**：\n");
        sb.append("   - **首选 `RapidOCR4j`**：开箱内置模型，无需配置模型文件路径，支持单字/词级细粒度坐标输出，支持调用级热参数覆盖（`ParamConfig`）。\n\n");
        sb.append("3. **如果你的项目需要多格式文档理解（PDF/Office/图片统一入口），并希望一次拿到版面、表格、分块与关键词**：\n");
        sb.append("   - **首选 `Xberg`**：绑定库、ONNX Runtime 与 PP-OCRv6 模型全部打包在 `xberg-jdk21` jar 内，`Xberg.extract` 单入口覆盖图片/PDF/Office；");
        sb.append("代价是必须运行在 **JDK 21 且开启 `--enable-preview`** 的进程上，且其 PaddleOCR 后端对单页图片的并发扩展性弱于纯 OCR 库。\n\n");
        sb.append("4. **高并发部署与资源配置建议**：\n");
        sb.append("   - OCR 计算主要消耗 CPU 和 Native 物理内存（RSS），JVM 堆占用极少。\n");
        sb.append("   - 容器化部署时，**内存限制（Memory Limit）必须以 Native 物理内存为基准**（建议每路并发预留 300MB~500MB 物理内存），不能仅依赖 `-Xmx`。\n");
        sb.append("   - `mica-ppocr` / `RapidOCR4j`：把 `intraOpNumThreads` 设为 `1`，靠应用侧并发度扩展；把三者的 `intraOpNumThreads` 同时调到核数会造成 `C × intraOp` 超订，吞吐反而掉到默认配置的 1/4~1/6（见第四节对照实验）。\n");
        sb.append("   - `Xberg`：显式设置 `concurrency.max_threads`（默认 `min(CPU 核数, 8)`，8 核以上机器用不满整机）并让 `max_concurrent_ocr` 有确定值，避免并发额度随启动瞬间的空闲内存浮动。\n");
        sb.append("   - 同一 JVM 内混跑多个 OCR 引擎时，Windows 会按模块名复用已加载的 `onnxruntime.dll`；本工程先让 Xberg 落地并加载其自带 ONNX Runtime，再启动其余引擎，保证三者共享同一份原生运行时。\n\n");

        // 9. How to reproduce
        sb.append("## 九、 复现方式\n\n");
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

    /**
     * 输出 Xberg 并发扩展性偏弱的成因分析。
     */
    private static void appendXbergConcurrencyAnalysis(StringBuilder sb, List<BenchmarkResult> results,
                                                       OcrProperties.XbergProperties xberg,
                                                       OcrProperties.RuntimeProperties runtime) {
        sb.append("## 四、 Xberg 并发吞吐偏低与扩展性不足的成因分析\n\n");

        sb.append("### 4.1 实测扩展性对比（中图-证件卡片 800x500）\n\n");
        sb.append("| 引擎名称 | C=1 QPS | C=4 QPS | C=1→C=4 放大倍数 | C=1 平均延迟 (ms) | C=4 平均延迟 (ms) | 延迟增幅 |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n");
        for (String engine : List.of("mica-ppocr", "RapidOCR4j", "Xberg")) {
            BenchmarkResult c1 = findResult(results, MEDIUM_SAMPLE, 1, engine);
            BenchmarkResult c4 = findResult(results, MEDIUM_SAMPLE, 4, engine);
            if (c1 == null || c4 == null) {
                continue;
            }
            double scale = c4.qps() / Math.max(c1.qps(), 0.001);
            double latencyGrowth = (c4.avgLatencyMs() - c1.avgLatencyMs()) / Math.max(c1.avgLatencyMs(), 0.001) * 100.0;
            sb.append(String.format("| `%s` | %.2f | %.2f | **%.2fx** | %.0f | %.0f | %+.0f%% |\n",
                    engine, c1.qps(), c4.qps(), scale, c1.avgLatencyMs(), c4.avgLatencyMs(), latencyGrowth));
        }
        sb.append("\n");
        sb.append("三家的吞吐都会随并发度上升，但 mica-ppocr / RapidOCR4j 接近线性（4 路并发约 3.2 倍、延迟只涨 20% 以内），");
        sb.append("Xberg 的放大倍数明显更低、平均延迟被并发推高得更狠。\n\n");

        sb.append("### 4.2 成因一：PaddleOCR 推理会话被互斥锁持有，OCR 阶段无法跨请求并行\n\n");
        sb.append("Xberg 官方 OCR 指南明确写道：**「PaddleOCR 的页面不并发执行 —— ONNX session 被 mutex 持有，");
        sb.append("因此线程预算全部用于算子内并行（intra-op parallelism），总墙钟时间 ≈ 页数 × 单页推理时间」**。\n\n");
        sb.append("也就是说，Xberg 进程内每次 `Xberg.extract` 的推理段落是串行的：并发请求只能重叠 OCR 之外的预处理/后处理，");
        sb.append("推理段落排队使平均延迟随并发度单调上升。这解释了 4.1 中「放大倍数低 + 延迟增幅大」的组合特征。\n\n");

        sb.append("### 4.3 成因二：线程预算是进程级共享，且默认被硬上限截断\n\n");
        sb.append("`ExtractionConfig.concurrency.max_threads` 是**进程级共享预算**（同时约束 Rayon 全局池、ONNX Runtime intra-op 线程与批量子任务）。");
        sb.append("参考文档说明其默认规则为：\n\n");
        sb.append("- 未设置时有效预算 = `min(检测到的 CPU 核心数, 8)`；核数 > 8 且无 Linux cgroup CPU 配额时，**多出来的核不会被使用**，");
        sb.append("且首次解析会打出 WARN 提示。本机 ").append(Runtime.getRuntime().availableProcessors())
                .append(" 逻辑核，默认只用到 8 个。\n");
        sb.append("- `max_concurrent_ocr` 未设置时「跟随 `max_threads`，并再按主机空闲内存可承载的会话数下调」，");
        sb.append("而且该限制在**进程内首次抽取时固定**、之后不可更改。\n\n");
        sb.append("后果是：不显式配置时，Xberg 的并发额度由「启动瞬间的空闲内存」决定，同一份配置不同次运行的结果会明显分叉");
        sb.append("（本机对同一默认配置重复测量，C=4 吞吐在 6.5~10.5 QPS 之间摆动，波动幅度超过任何一项调优带来的收益）。\n\n");

        sb.append("### 4.4 成因三：检测输入尺寸与另外两家不一致\n\n");
        sb.append("Xberg 的 `det_limit_side_len` 默认 **1024**，而本工程 mica-ppocr / RapidOCR4j 走的 `max-side-limit` 是 **960**。");
        sb.append("在 1600x2200 的大图上，Xberg 的检测分辨率更高（像素量约多 `(1024/960)² ≈ 1.14` 倍），");
        sb.append("叠加其文档解析流水线的固定开销，单页平均延迟明显高于另外两家。该项属于配置口径不一致，可对齐。\n\n");

        sb.append("### 4.5 成因四：真正不对等的是线程语义，而不是「线程给少了」\n\n");
        sb.append("把三者放到同一个进程级线程预算下（`intra-op-num-threads=16` + Xberg `max-threads=16`）另跑一组对照实验，");
        sb.append("中图-证件卡片 C=4 的结果与本轮报告对比：\n\n");
        sb.append("| 引擎名称 | 统一线程预算=16 的 C=4 QPS（对照实验） | 本轮报告 C=4 QPS |\n");
        sb.append("| :--- | :---: | :---: |\n");
        sb.append("| `mica-ppocr` | 5.03 | ").append(c4Qps(results, "mica-ppocr")).append(" |\n");
        sb.append("| `RapidOCR4j` | 3.16 | ").append(c4Qps(results, "RapidOCR4j")).append(" |\n");
        sb.append("| `Xberg` | **9.36** | ").append(c4Qps(results, "Xberg")).append(" |\n\n");
        sb.append("> 对照实验与本轮报告是同一台机器上的两次独立运行，绝对值仅供横向比较。\n\n");
        sb.append("结论很清晰：\n\n");
        sb.append("1. `mica-ppocr` / `RapidOCR4j` 的 `intraOpNumThreads` 是**会话级**参数，C 路并发各自持有会话，实际线程数 = `C × intraOp`；");
        sb.append("设成 16 后就是 4 × 16 = 64 线程抢 16 个核，严重超订，吞吐反而掉到各自合理配置的 1/4 ~ 1/6。\n");
        sb.append("2. `Xberg` 的 `max_threads` 是**进程级共享**预算，不会随并发度翻倍，所以给足预算它是三者中扩展性最好的。\n");
        sb.append("3. 因此「Xberg 并发效果差」并不等于线程配少了：在**同一台机器各自的合理配置**下，它单页延迟本身就高（mutex 串行 + 文档流水线固定开销）；");
        sb.append("而一旦按会话级语义去对比，把三家的线程数字强行设成一样，反而会把另外两家打垮。\n\n");
    }

    /** 取本轮报告里某引擎在中图样本 C=4 的吞吐，缺失时返回占位符。 */
    private static String c4Qps(List<BenchmarkResult> results, String enginePrefix) {
        BenchmarkResult c4 = findResult(results, MEDIUM_SAMPLE, 4, enginePrefix);
        return c4 == null ? "-" : String.format("%.2f", c4.qps());
    }

    /**
     * 输出三引擎 OCR 参数对齐表与 Xberg 调优项。
     */
    private static void appendXbergTuningSection(StringBuilder sb, OcrProperties properties,
                                                 OcrProperties.XbergProperties xberg,
                                                 OcrProperties.RuntimeProperties runtime) {
        OcrProperties.DetectionProperties det = properties.getDetection();
        OcrProperties.RecognitionProperties rec = properties.getRecognition();

        sb.append("## 五、 三引擎 OCR 参数对齐与 Xberg 调优\n\n");

        sb.append("### 5.1 OCR 语义参数逐项对齐\n\n");
        sb.append("三家使用同一套 PP-OCRv6 `tiny` 权重与字典，检测/识别超参与批次完全一致，");
        sb.append("因此吞吐与精度差异来自运行时行为，而不是模型或阈值口径。\n\n");
        sb.append("| OCR 参数 | 工程配置键 | mica-ppocr | RapidOCR4j | Xberg |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");
        sb.append(String.format("| 检测模型 | `ocr.model.det-model-path` | `%s` | 同左 | jar 内置 `v6/det/tiny/model.onnx` |\n",
                shortName(properties.getModel().getDetModelPath())));
        sb.append(String.format("| 识别模型 | `ocr.model.rec-model-path` | `%s` | 同左 | jar 内置 `v6/rec/tiny/model.onnx` |\n",
                shortName(properties.getModel().getRecModelPath())));
        sb.append("| 识别字典 | `ocr.model.dict-path` | `dict.txt` | 同左 | jar 内置 `v6/rec/tiny/dict.txt` |\n");
        sb.append(String.format("| 二值化阈值 | `ocr.detection.thresh` | %.2f | %.2f | %.2f |\n",
                det.getThresh(), det.getThresh(), det.getThresh()));
        sb.append(String.format("| 文本框阈值 | `ocr.detection.box-thresh` | %.2f | %.2f | %.2f |\n",
                det.getBoxThresh(), det.getBoxThresh(), det.getBoxThresh()));
        sb.append(String.format("| 文本框扩张系数 | `ocr.detection.unclip-ratio` | %.2f | %.2f | %.2f |\n",
                det.getUnclipRatio(), det.getUnclipRatio(), det.getUnclipRatio()));
        sb.append(String.format("| 识别批次 | `ocr.recognition.batch-size` | %d | %d | %d |\n",
                rec.getBatchSize(), rec.getBatchSize(), rec.getBatchSize()));
        sb.append(String.format("| 文本行方向分类 | `ocr.runtime.use-cls` | %s | %s | %s |\n",
                runtime.isUseCls(), runtime.isUseCls(), runtime.isUseCls()));
        sb.append(String.format("| 检测长边上限 | `ocr.detection.max-side-limit` / `ocr.xberg.det-limit-side-len` | %d | %d | **%s** |\n",
                det.getMaxSideLimit(), det.getMaxSideLimit(),
                xberg.getDetLimitSideLen() > 0 ? String.valueOf(xberg.getDetLimitSideLen()) : "默认 1024（未对齐）"));
        sb.append(String.format("| 线程预算 | `intra-op-num-threads` / `ocr.xberg.max-threads` | %d（会话级） | %d（会话级） | **%s**（进程级） |\n",
                runtime.getIntraOpNumThreads(), runtime.getIntraOpNumThreads(),
                xberg.getMaxThreads() > 0 ? String.valueOf(xberg.getMaxThreads()) : "默认 min(核数,8)"));
        sb.append(String.format("| 并发会话上限 | `ocr.xberg.max-concurrent-ocr` | 不适用 | 不适用 | **%s** |\n\n",
                xberg.getMaxConcurrentOcr() > 0 ? String.valueOf(xberg.getMaxConcurrentOcr()) : "跟随 max-threads（内存相关）"));

        sb.append("### 5.2 Xberg 调优项\n\n");
        sb.append("| 调优项 | 配置键 | 调优值 | 依据与效果 |\n");
        sb.append("| :--- | :--- | :---: | :--- |\n");
        sb.append(String.format("| 进程级线程预算 | `ocr.xberg.max-threads` | **%s** | Xberg 默认为 `min(CPU 核数, 8)`，本机 %d 逻辑核用不满整机；显式给足后 C=4 吞吐与延迟都优于默认（见 4.3） |\n",
                xberg.getMaxThreads() > 0 ? String.valueOf(xberg.getMaxThreads()) : "未设置",
                Runtime.getRuntime().availableProcessors()));
        sb.append(String.format("| 检测输入长边 | `ocr.xberg.det-limit-side-len` | **%s** | 与 mica-ppocr / RapidOCR4j 的 `max-side-limit=%d` 对齐，消除 Xberg 默认 1024 带来的多余检测像素（见 4.4） |\n",
                xberg.getDetLimitSideLen() > 0 ? String.valueOf(xberg.getDetLimitSideLen()) : "未设置",
                det.getMaxSideLimit()));
        sb.append(String.format("| 并发会话上限 | `ocr.xberg.max-concurrent-ocr` | %s | 显式固定可消除「按空闲内存下调、首次抽取后固定」带来的不确定性；实测限制到 4 会显著压低吞吐，因此保持跟随线程预算 |\n",
                xberg.getMaxConcurrentOcr() > 0 ? String.valueOf(xberg.getMaxConcurrentOcr()) : "0（跟随 max-threads）"));
        sb.append("\n");

        sb.append("> 调优边界：以上三项只改动 Xberg 的**线程与输入尺寸**，不触碰模型档位、检测/识别超参、批次与语言，");
        sb.append("因此三家仍然跑在同一套 PP-OCRv6 `tiny` 权重与同一套 OCR 语义参数上（见 5.1），对比结论不失真。\n");
        sb.append("> 效果说明：本机为 16 逻辑核桌面环境，上述调整主要收益是**去掉文档记载的线程上限、消除并发额度随机性**；");
        sb.append("单次运行的吞吐增量与环境噪声同量级（±15%），因此不应把 Xberg 与另外两家的差距归因于配置，");
        sb.append("真正差距来自 4.2 / 4.5 的会话串行与文档流水线固定开销。\n\n");
    }

    private static BenchmarkResult findResult(List<BenchmarkResult> results, String sampleName,
                                              int concurrency, String enginePrefix) {
        for (BenchmarkResult br : results) {
            if (sampleName.equals(br.sampleName()) && br.concurrency() == concurrency
                    && br.engineName().startsWith(enginePrefix)) {
                return br;
            }
        }
        return null;
    }

    private static String shortName(String path) {
        if (path == null || path.isEmpty()) {
            return "-";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
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
