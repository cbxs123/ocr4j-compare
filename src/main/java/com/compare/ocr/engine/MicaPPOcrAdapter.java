package com.compare.ocr.engine;

import com.compare.ocr.config.OcrProperties;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import nu.pattern.OpenCV;

import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class MicaPPOcrAdapter implements OcrEngineAdapter {

    static {
        try {
            OpenCV.loadLocally();
        } catch (Throwable t) {
            log.warn("OpenCV load locally: {}", t.getMessage());
        }
    }

    private final PPOcrV6Engine engine;
    private final String name;

    public MicaPPOcrAdapter(OcrProperties properties) {
        this("mica-ppocr (tiny)", properties, properties.getRuntime().getIntraOpNumThreads());
    }

    public MicaPPOcrAdapter(OcrProperties properties, int intraOpNumThreads) {
        this("mica-ppocr (tiny)", properties, intraOpNumThreads);
    }

    public MicaPPOcrAdapter(String name, OcrProperties properties, int intraOpNumThreads) {
        this.name = name;
        String detModel = Paths.get(properties.getModel().getDetModelPath()).toAbsolutePath().toString();
        String recModel = Paths.get(properties.getModel().getRecModelPath()).toAbsolutePath().toString();
        String dictPath = Paths.get(properties.getModel().getDictPath()).toAbsolutePath().toString();
        String docOriModel = Paths.get(properties.getModel().getDocOriModelPath()).toAbsolutePath().toString();

        PPOcrV6Config config = PPOcrV6Config.builder()
                .detModelPath(detModel)
                .recModelPath(recModel)
                .recCharDictPath(dictPath)
                .intraOpNumThreads(intraOpNumThreads)
                .interOpNumThreads(properties.getRuntime().getInterOpNumThreads())
                .useDocOrientationClassify(properties.getRuntime().isUseCls())
                .docOrientationModelPath(docOriModel)
                .detLimitSideLen(properties.getDetection().getLimitSideLen())
                .detLimitType(properties.getDetection().getLimitType())
                .detMaxSideLimit(properties.getDetection().getMaxSideLimit())
                .detBoxThresh(properties.getDetection().getBoxThresh())
                .detThresh(properties.getDetection().getThresh())
                .detUnclipRatio(properties.getDetection().getUnclipRatio())
                .recBatchSize(properties.getRecognition().getBatchSize())
                .preferAccelerator(properties.getRecognition().isPreferAccelerator())
                .build();

        this.engine = new PPOcrV6Engine(config);
    }

    // Overload for backward compatibility / tests
    public MicaPPOcrAdapter(int intraOpNumThreads) {
        this(new OcrProperties(), intraOpNumThreads);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public OcrEngineResult run(byte[] imageBytes) {
        long start = System.nanoTime();
        List<PPOcrV6Result> results = engine.run(imageBytes);
        long end = System.nanoTime();

        String fullText = results.stream()
                .map(PPOcrV6Result::text)
                .collect(Collectors.joining("\n"));

        return new OcrEngineResult(
                fullText,
                results.size(),
                end - start,
                0,
                0
        );
    }

    @Override
    public void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception e) {
                log.warn("Error closing mica engine", e);
            }
        }
    }
}
