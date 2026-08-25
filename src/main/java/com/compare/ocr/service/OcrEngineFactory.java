package com.compare.ocr.service;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrEngineFactory {

    private final OcrProperties properties;

    public MicaPPOcrAdapter createMicaEngine() {
        return createMicaEngine(properties.getRuntime().getIntraOpNumThreads());
    }

    public MicaPPOcrAdapter createMicaEngine(int threads) {
        return new MicaPPOcrAdapter(properties, threads);
    }

    public RapidOcr4jAdapter createRapidEngine() {
        return createRapidEngine(properties.getRuntime().getIntraOpNumThreads());
    }

    public RapidOcr4jAdapter createRapidEngine(int threads) {
        return new RapidOcr4jAdapter(properties, threads);
    }
}
