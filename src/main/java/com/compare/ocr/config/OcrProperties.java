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
    }
}
