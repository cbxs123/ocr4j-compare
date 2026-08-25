package com.compare.ocr.engine;

public interface OcrEngineAdapter extends AutoCloseable {
    /**
     * Engine display name for reports.
     */
    String getName();

    /**
     * Run OCR on raw image bytes.
     */
    OcrEngineResult run(byte[] imageBytes) throws Exception;

    /**
     * Release all native resources, sessions, and memory.
     */
    @Override
    void close();
}
