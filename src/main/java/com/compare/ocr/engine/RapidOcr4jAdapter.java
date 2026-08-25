package com.compare.ocr.engine;

import com.compare.ocr.config.OcrProperties;
import io.github.lsforge.RapidOCR;
import io.github.lsforge.entity.OcrConfig;
import io.github.lsforge.entity.OcrResult;
import io.github.lsforge.entity.RecResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.stream.Collectors;

@Slf4j
public class RapidOcr4jAdapter implements OcrEngineAdapter {

    private final RapidOCR rapidOCR;
    private final String name;

    public RapidOcr4jAdapter(OcrProperties properties) {
        this("RapidOCR4j (tiny)", properties, properties.getRuntime().getIntraOpNumThreads());
    }

    public RapidOcr4jAdapter(OcrProperties properties, int intraOpNumThreads) {
        this("RapidOCR4j (tiny)", properties, intraOpNumThreads);
    }

    public RapidOcr4jAdapter(String name, OcrProperties properties, int intraOpNumThreads) {
        this.name = name;
        String detModel = Paths.get(properties.getModel().getDetModelPath()).toAbsolutePath().toString();
        String recModel = Paths.get(properties.getModel().getRecModelPath()).toAbsolutePath().toString();
        String dictPath = Paths.get(properties.getModel().getDictPath()).toAbsolutePath().toString();

        OcrConfig config = new OcrConfig();
        config.Global.setIntraOpNumThreads(intraOpNumThreads);
        config.Global.setInterOpNumThreads(properties.getRuntime().getInterOpNumThreads());
        config.Global.setUseCls(properties.getRuntime().isUseCls());
        config.Global.setUsePreprocessImg(true);
        config.Global.setMaxSideLen(properties.getDetection().getMaxSideLimit());
        config.Global.setUseVerticalPadding(properties.getRuntime().isUseVerticalPadding());

        config.Det.setModelPath(detModel);
        config.Det.setLimitSideLen(properties.getDetection().getLimitSideLen());
        config.Det.setLimitType(properties.getDetection().getLimitType());
        config.Det.setBoxThresh(properties.getDetection().getBoxThresh());
        config.Det.setThresh(properties.getDetection().getThresh());
        config.Det.setUnclipRatio(properties.getDetection().getUnclipRatio());

        config.Rec.setModelPath(recModel);
        config.Rec.setRecKeysPath(dictPath);
        config.Rec.setRecBatchNum(properties.getRecognition().getBatchSize());

        this.rapidOCR = RapidOCR.create(config);
    }

    // Overload for backward compatibility / tests
    public RapidOcr4jAdapter(int intraOpNumThreads) {
        this(new OcrProperties(), intraOpNumThreads);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public OcrEngineResult run(byte[] imageBytes) {
        long start = System.nanoTime();
        OcrResult result;
        try {
            result = rapidOCR.run(imageBytes);
        } catch (Exception e) {
            throw new RuntimeException("RapidOCR inference failed", e);
        }
        long end = System.nanoTime();

        String fullText = "";
        int lineCount = 0;
        if (result != null && result.getRecRes() != null) {
            lineCount = result.getRecRes().size();
            fullText = result.getRecRes().stream()
                    .map(RecResult::getText)
                    .collect(Collectors.joining("\n"));
        }

        long detNs = result != null ? (long) (result.getDetTime() * 1_000_000_000L) : 0;
        long recNs = result != null ? (long) (result.getRecTime() * 1_000_000_000L) : 0;

        return new OcrEngineResult(
                fullText,
                lineCount,
                end - start,
                detNs,
                recNs
        );
    }

    @Override
    public void close() {
        if (rapidOCR != null) {
            try {
                rapidOCR.close();
            } catch (Exception e) {
                log.warn("Error closing RapidOCR engine", e);
            }
        }
    }
}
