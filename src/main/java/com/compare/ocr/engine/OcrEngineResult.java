package com.compare.ocr.engine;

public record OcrEngineResult(
        String fullText,
        int lineCount,
        long totalElapsedNs,
        long detElapsedNs,
        long recElapsedNs
) {
}
