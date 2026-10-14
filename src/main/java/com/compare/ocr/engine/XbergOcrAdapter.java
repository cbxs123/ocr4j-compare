package com.compare.ocr.engine;

import com.compare.ocr.config.OcrProperties;
import io.xberg.ConcurrencyConfig;
import io.xberg.ExtractInput;
import io.xberg.ExtractInputKind;
import io.xberg.ExtractedDocument;
import io.xberg.ExtractionConfig;
import io.xberg.ExtractionResult;
import io.xberg.MimeDetectionPolicy;
import io.xberg.OcrConfig;
import io.xberg.PaddleOcrConfig;
import io.xberg.Xberg;
import io.xberg.XbergRsException;
import io.xberg.XbergRuntime;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Xberg 适配器：走 {@code paddle-ocr} 后端 + PP-OCRv6 tiny ONNX 模型，与 mica-ppocr /
 * RapidOCR4j 使用同一套权重，便于横向对比。
 *
 * <p>Xberg 把绑定库、ONNX Runtime 与模型都打包在 jar 内，第一次构造适配器时由
 * {@link XbergRuntime} 落地到运行目录并加载，之后同进程内复用；落地耗时可通过
 * {@link #bootstrap(OcrProperties)} 的返回值单独统计。
 */
@Slf4j
public class XbergOcrAdapter implements OcrEngineAdapter {

    private static final Object RUNTIME_LOCK = new Object();
    private static String bootstrappedRuntimeDir;

    private final String name;
    private final ExtractionConfig extractionConfig;

    public XbergOcrAdapter(OcrProperties properties) {
        this("Xberg (paddle-ocr tiny)", properties, properties.getRuntime().getIntraOpNumThreads());
    }

    public XbergOcrAdapter(OcrProperties properties, int intraOpNumThreads) {
        this("Xberg (paddle-ocr tiny)", properties, intraOpNumThreads);
    }

    public XbergOcrAdapter(String name, OcrProperties properties, int intraOpNumThreads) {
        this(name, bootstrap(properties), properties);
    }

    private XbergOcrAdapter(String name, BootstrapResult runtime, OcrProperties properties) {
        this.name = name;

        OcrProperties.XbergProperties xberg = properties.getXberg();
        PaddleOcrConfig.Builder paddleBuilder = PaddleOcrConfig.builder()
                .withModelTier(xberg.getModelTier())
                .withModelVersion(xberg.getModelVersion())
                .withCacheDir(runtime.modelCacheDirectory())
                .withUseAngleCls(properties.getRuntime().isUseCls())
                .withDetDbThresh(properties.getDetection().getThresh())
                .withDetDbBoxThresh(properties.getDetection().getBoxThresh())
                .withDetDbUnclipRatio(properties.getDetection().getUnclipRatio())
                .withRecBatchNum(properties.getRecognition().getBatchSize());
        if (xberg.getDetLimitSideLen() > 0) {
            paddleBuilder.withDetLimitSideLen(xberg.getDetLimitSideLen());
        }

        ExtractionConfig.Builder extractionBuilder = ExtractionConfig.builder()
                .withMimeDetectionPolicy(MimeDetectionPolicy.PREFER_CONTENT)
                .withOcr(OcrConfig.builder()
                        .withBackend(xberg.getBackend())
                        .withLanguage(List.of(xberg.getLanguage()))
                        .withPaddleOcrSettings(paddleBuilder.build())
                        .build())
                .withForceOcr(true);
        if (xberg.getMaxThreads() > 0 || xberg.getMaxConcurrentOcr() > 0) {
            ConcurrencyConfig.Builder concurrency = ConcurrencyConfig.builder();
            if (xberg.getMaxThreads() > 0) {
                concurrency.withMaxThreads(xberg.getMaxThreads());
            }
            if (xberg.getMaxConcurrentOcr() > 0) {
                concurrency.withMaxConcurrentOcr(xberg.getMaxConcurrentOcr());
            }
            extractionBuilder.withConcurrency(concurrency.build());
        }

        this.extractionConfig = extractionBuilder.build();
    }

    /**
     * 幂等地落地并加载 Xberg 原生运行时。
     *
     * <p>提前调用可以让 Xberg 自带的 ONNX Runtime 先于其它引擎绑定进进程，避免进程内已存在的
     * 同名 DLL 抢占加载；同一进程内后续调用直接返回上一次的结果，耗时记为 0。
     *
     * @param properties 工程配置
     * @return 落地结果，含模型缓存目录与本次落地耗时
     */
    public static BootstrapResult bootstrap(OcrProperties properties) {
        OcrProperties.XbergProperties xberg = properties.getXberg();
        Path runtimeDir = Paths.get(xberg.getRuntimeDir()).toAbsolutePath().normalize();
        synchronized (RUNTIME_LOCK) {
            if (runtimeDir.toString().equals(bootstrappedRuntimeDir)) {
                return new BootstrapResult(XbergRuntime.status().modelCacheDirectory(), 0L);
            }
            long start = System.nanoTime();
            XbergRuntime.Config.Builder builder = XbergRuntime.Config.builder()
                    .withRuntimeDirectory(runtimeDir)
                    .withStageModels(true)
                    .withLoadOnnxRuntime(true)
                    .withLoadNativeLibrary(true);
            if (xberg.getModelRevision() != null && !xberg.getModelRevision().isBlank()) {
                builder.withModelRevision(xberg.getModelRevision().trim());
            }
            XbergRuntime.Status status = XbergRuntime.initialize(builder.build());
            long elapsedNanos = System.nanoTime() - start;

            for (String warning : status.warnings()) {
                log.warn("Xberg runtime warning: {}", warning);
            }
            if (status.onnxRuntime() == null) {
                log.warn("Xberg 未落地可用的 ONNX Runtime，OCR 无法运行");
            }
            log.info("Xberg runtime ready: dir={} library={} onnxRuntime={} staged={} reused={} modelCache={} ms={}",
                    status.runtimeDirectory(), status.nativeLibrary(), status.onnxRuntime(),
                    status.stagedFiles(), status.reusedFiles(), status.modelCacheDirectory(),
                    elapsedNanos / 1_000_000);

            bootstrappedRuntimeDir = runtimeDir.toString();
            return new BootstrapResult(status.modelCacheDirectory(), elapsedNanos);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public OcrEngineResult run(byte[] imageBytes) {
        long start = System.nanoTime();
        ExtractionResult result;
        try {
            result = Xberg.extract(
                    ExtractInput.builder()
                            .withKind(ExtractInputKind.BYTES)
                            .withBytes(imageBytes)
                            .withFilename("sample.png")
                            .withMimeType("image/png")
                            .build(),
                    extractionConfig);
        } catch (XbergRsException e) {
            throw new RuntimeException("Xberg inference failed", e);
        }
        long end = System.nanoTime();

        // Xberg 输出的是段落化的文本，段落之间带空行；去掉空行后才与其它引擎的逐行文本可比。
        String fullText = "";
        int lineCount = 0;
        if (result != null && !result.results().isEmpty()) {
            ExtractedDocument document = result.results().get(result.results().size() - 1);
            String content = document.content() == null ? "" : document.content();
            List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
            fullText = lines.stream().map(String::strip).collect(Collectors.joining("\n"));
            lineCount = lines.size();
        }

        return new OcrEngineResult(fullText, lineCount, end - start, 0, 0);
    }

    @Override
    public void close() {
        // Xberg 的运行时与推理会话由原生层全局持有，单个适配器无需释放。
        log.debug("XbergOcrAdapter closed (native runtime stays loaded for the JVM lifetime)");
    }

    /**
     * 一次运行时落地的结果。
     *
     * @param modelCacheDirectory 模型缓存目录，即 {@code PaddleOcrConfig.cacheDir()}
     * @param elapsedNanos 本次落地耗时；运行时已就绪时为 0
     */
    public record BootstrapResult(Path modelCacheDirectory, long elapsedNanos) {
    }
}
