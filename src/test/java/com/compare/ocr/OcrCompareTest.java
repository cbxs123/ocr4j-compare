package com.compare.ocr;

import com.compare.ocr.config.OcrProperties;
import com.compare.ocr.engine.MicaPPOcrAdapter;
import com.compare.ocr.engine.RapidOcr4jAdapter;
import com.compare.ocr.engine.XbergOcrAdapter;
import com.compare.ocr.service.OcrEngineFactory;
import com.compare.ocr.util.ResourceDownloader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@TestPropertySource(properties = {
        "ocr.benchmark.auto-run-on-startup=false"
})
public class OcrCompareTest {

    @Autowired
    private OcrProperties properties;

    @Autowired
    private OcrEngineFactory engineFactory;

    @BeforeAll
    public static void setUp() {
        ResourceDownloader.prepareAll();
    }

    @Test
    public void testBothEnginesOnSmallImage() throws Exception {
        log.info("Running Spring Boot OCR test on small image...");
        String smallImgPath = Paths.get(properties.getBenchmark().getImagesBaseDir(), "small_single_line.png").toString();
        byte[] img = Files.readAllBytes(Paths.get(smallImgPath));

        try (MicaPPOcrAdapter mica = engineFactory.createMicaEngine()) {
            var res = mica.run(img);
            assertNotNull(res);
            assertFalse(res.fullText().isEmpty(), "mica-ppocr should recognize text");
            assertTrue(res.fullText().contains("88888") || res.fullText().contains("通行证"));
            log.info("mica-ppocr recognized text: {}", res.fullText());
        }

        try (RapidOcr4jAdapter rapid = engineFactory.createRapidEngine()) {
            var res = rapid.run(img);
            assertNotNull(res);
            assertFalse(res.fullText().isEmpty(), "RapidOCR4j should recognize text");
            assertTrue(res.fullText().contains("88888") || res.fullText().contains("通行证"));
            log.info("RapidOCR4j recognized text: {}", res.fullText());
        }

        try (XbergOcrAdapter xberg = engineFactory.createXbergEngine()) {
            var res = xberg.run(img);
            assertNotNull(res);
            assertFalse(res.fullText().isEmpty(), "Xberg should recognize text");
            assertTrue(res.fullText().contains("88888") || res.fullText().contains("通行证"));
            log.info("Xberg recognized text: {}", res.fullText());
        }
    }
}
