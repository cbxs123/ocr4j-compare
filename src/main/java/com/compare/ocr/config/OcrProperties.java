package com.compare.ocr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private ModelProperties model = new ModelProperties();
    private DetectionProperties detection = new DetectionProperties();
    private RecognitionProperties recognition = new RecognitionProperties();
    private RuntimeProperties runtime = new RuntimeProperties();
    private BenchmarkProperties benchmark = new BenchmarkProperties();
    private XbergProperties xberg = new XbergProperties();

    @Data
    public static class ModelProperties {
        private String baseDir = "src/main/resources/models/ppocr-v6/tiny";
        private String detModelPath = "src/main/resources/models/ppocr-v6/tiny/det.onnx";
        private String recModelPath = "src/main/resources/models/ppocr-v6/tiny/rec.onnx";
        private String dictPath = "src/main/resources/models/ppocr-v6/tiny/dict.txt";
        private String detUrl = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/tmp/PP-OCRv6_tiny_det_onnx.tar";
        private String recUrl = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/tmp/PP-OCRv6_tiny_rec_0515_onnx.tar";
        private String dictUrl = "https://raw.githubusercontent.com/AIwork4me/ppocrv6_onnx/master/models/rec_char_dict.txt";
        private String dictBackupUrl = "https://raw.githubusercontent.com/lets-mica/mica-ppocr/master/models/ppocr-v6/tiny/dict.txt";
    }

    @Data
    public static class DetectionProperties {
        private int limitSideLen = 64;
        private String limitType = "min";
        private int maxSideLimit = 960;
        private float thresh = 0.3f;
        private float boxThresh = 0.6f;
        private float unclipRatio = 1.5f;
    }

    @Data
    public static class RecognitionProperties {
        private int batchSize = 6;
        private boolean preferAccelerator = false;
    }

    @Data
    public static class RuntimeProperties {
        private int intraOpNumThreads = 1;
        private int interOpNumThreads = 1;
        private boolean useCls = false;
        private boolean useVerticalPadding = false;
    }

    @Data
    public static class BenchmarkProperties {
        private boolean autoRunOnStartup = true;
        private List<Integer> concurrencies = List.of(1, 2, 4);
        private int requestsPerBench = 30;
        private int warmupRequests = 3;
        private int stabilityRequests = 200;
        private String imagesBaseDir = "src/main/resources/images";
        private String outputReadme = "README.md";
        /** 分片子进程内部模式：{@code worker} 表示只跑一轮 Xberg C=1 基准并输出结果行后退出。 */
        private String shardRole = "";
        /** worker 模式使用的图片路径。 */
        private String shardImage = "";
        /** worker 模式的请求数。 */
        private int shardRequests = 20;
    }

    /**
     * Xberg 引擎配置。Xberg 自带绑定库、ONNX Runtime 与 PP-OCRv6 模型，
     * 首次运行会把这些资源落地到 {@code runtime-dir}，之后复用。
     */
    @Data
    public static class XbergProperties {
        private String runtimeDir = "target/xberg-runtime";
        private String backend = "paddle-ocr";
        private String language = "ch";
        private String modelTier = "tiny";
        private String modelVersion = "pp-ocrv6";
        /** 留空表示落地 jar 内置的全部模型快照。 */
        private String modelRevision = "";
        /**
         * Xberg 内部线程池总预算（Rayon / ONNX Runtime intra-op / 批量子任务）。
         * {@code 0} 表示交给 Xberg 决定；其默认值为 {@code min(CPU 核数, 8)}，
         * 因此在 8 核以上的机器上默认不会用满整机。
         */
        private int maxThreads = 0;
        /**
         * 同时运行的 OCR 识别会话上限，{@code 0} 表示跟随 {@code max-threads}。
         * 进程内首次抽取后固定，调整需重启进程。
         */
        private int maxConcurrentOcr = 0;
        /**
         * 检测网络输入长边上限，{@code 0} 表示使用 Xberg 默认值（1024）。
         * 与 mica-ppocr / RapidOCR4j 的 {@code detection.max-side-limit} 对齐。
         */
        private int detLimitSideLen = 0;
        /**
         * 纯 OCR 瘦身开关：true 时关闭质量处理、扫描页质量门控，
         * 输出改用 {@code OutputFormat.Plain} 并跳过 Markdown 转义、表格锚点、
         * 二维码识别与文档结构组织，砍掉面向文档智能的固定流水线开销。
         */
        private boolean leanPipeline = true;
        /** 表格检测（PaddleOcrConfig.enableTableDetection），纯文本基准默认关闭。 */
        private boolean enableTableDetection = false;
        /** ONNX 推理后端：{@code ""} = Xberg 默认；{@code ort} | {@code tract}。 */
        private String inferenceBackend = "";
        /** 识别置信度丢弃阈值，{@code null} = Xberg 默认。 */
        private Float dropScore;
        /** 识别文本行裁剪 padding（像素），{@code null} = Xberg 默认。 */
        private Integer padding;
        /** 加速 provider：{@code ""} = CPU；{@code auto} | {@code core-ml} | {@code cuda} | {@code tensor-rt}。 */
        private String accelerationProvider = "";
        private int accelerationDeviceId = 0;
        /** 进程级抽取并发上限（背压阀门），{@code 0} = 不设置。 */
        private int maxConcurrentExtractions = 0;
        /** 是否在冷启动测试之后执行预热，把 ONNX 会话惰性建立成本移出测量段。 */
        private boolean warmupEnabled = true;
        private int warmupRounds = 2;
        /** 进程分片 worker 数，{@code 0} = 关闭分片验证。 */
        private int shardProcesses = 0;
        private int shardRequestsPerWorker = 20;
        /** 分片 worker 进程内的 Xberg 线程预算（intra-op），刻意压小以体现「进程换线程」。 */
        private int shardWorkerThreads = 2;
    }
}
