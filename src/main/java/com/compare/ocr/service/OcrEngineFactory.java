package com.compare.ocr.service;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import com.compare.ocr.engine.XbergOcrAdapter;
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

    public XbergOcrAdapter createXbergEngine() {
        return createXbergEngine(properties.getRuntime().getIntraOpNumThreads());
    }

    public XbergOcrAdapter createXbergEngine(int threads) {
        return new XbergOcrAdapter(properties, threads);
    }
}
