package com.compare.ocr.util;

import com.compare.ocr.config.OcrProperties;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

@Slf4j
public class ResourceDownloader {

    public static void prepareAll() {
        prepareAll(new OcrProperties());
    }

    public static void prepareAll(OcrProperties properties) {
        prepareDirectories(properties);
        prepareModels(properties);
        prepareImages(properties);
    }

    private static void prepareDirectories(OcrProperties properties) {
        try {
            Files.createDirectories(Paths.get(properties.getModel().getBaseDir()));
            Files.createDirectories(Paths.get(properties.getBenchmark().getImagesBaseDir()));
        } catch (IOException e) {
            log.error("Failed to create resource directories", e);
        }
    }

    private static void prepareModels(OcrProperties properties) {
        Path detPath = Paths.get(properties.getModel().getDetModelPath());
        Path recPath = Paths.get(properties.getModel().getRecModelPath());
        Path dictPath = Paths.get(properties.getModel().getDictPath());
        String modelBaseDir = properties.getModel().getBaseDir();

        if (!Files.exists(detPath) || detPath.toFile().length() == 0) {
            downloadTarAndExtract(properties.getModel().getDetUrl(), detPath, modelBaseDir, "PP-OCRv6 tiny det");
        }
        if (!Files.exists(recPath) || recPath.toFile().length() == 0) {
            downloadTarAndExtract(properties.getModel().getRecUrl(), recPath, modelBaseDir, "PP-OCRv6 tiny rec");
        }
        downloadIfNotExists(dictPath, properties.getModel().getDictUrl(), properties.getModel().getDictBackupUrl(), "dict.txt");
    }

    private static void downloadTarAndExtract(String tarUrl, Path targetOnnxPath, String modelBaseDir, String desc) {
        log.info("Downloading and extracting {} from {} ...", desc, tarUrl);
        Path tempTar = Paths.get(modelBaseDir, "temp_" + System.currentTimeMillis() + ".tar");
        if (downloadFile(tarUrl, tempTar)) {
            try (InputStream is = Files.newInputStream(tempTar);
                 BufferedInputStream bis = new BufferedInputStream(is)) {
                // Simple tar extractor for inference.onnx
                byte[] header = new byte[512];
                while (bis.read(header) == 512) {
                    if (header[0] == 0) break; // End of archive
                    String fileName = new String(header, 0, 100).trim();
                    // Octal size at offset 124, len 12
                    String sizeStr = new String(header, 124, 12).trim();
                    long fileSize = 0;
                    if (!sizeStr.isEmpty()) {
                        fileSize = Long.parseLong(sizeStr.split("\\s+")[0], 8);
                    }
                    if (fileName.endsWith(".onnx")) {
                        log.info("Found onnx file in tar: {}, size: {} bytes", fileName, fileSize);
                        try (OutputStream os = Files.newOutputStream(targetOnnxPath)) {
                            byte[] buffer = new byte[8192];
                            long remaining = fileSize;
                            while (remaining > 0) {
                                int read = bis.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (read == -1) break;
                                os.write(buffer, 0, read);
                                remaining -= read;
                            }
                        }
                        log.info("Extracted ONNX model to {} ({} bytes)", targetOnnxPath, Files.size(targetOnnxPath));
                        break;
                    } else {
                        // Skip file content and 512-byte padding
                        long toSkip = fileSize;
                        long padding = (512 - (fileSize % 512)) % 512;
                        toSkip += padding;
                        while (toSkip > 0) {
                            long skipped = bis.skip(toSkip);
                            if (skipped <= 0) break;
                            toSkip -= skipped;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to extract tar archive: {}", tempTar, e);
            } finally {
                try {
                    Files.deleteIfExists(tempTar);
                } catch (IOException ignored) {}
            }
        }
    }

    private static void downloadIfNotExists(Path targetPath, String primaryUrl, String backupUrl, String name) {
        if (Files.exists(targetPath) && targetPath.toFile().length() > 0) {
            log.info("Resource already exists: {} ({} bytes)", name, targetPath.toFile().length());
            return;
        }

        log.info("Downloading {} from {} ...", name, primaryUrl);
        boolean success = downloadFile(primaryUrl, targetPath);
        if (!success && backupUrl != null) {
            log.warn("Primary download failed for {}, trying backup URL: {}", name, backupUrl);
            success = downloadFile(backupUrl, targetPath);
        }

        if (success) {
            log.info("Successfully downloaded {} ({} bytes)", name, targetPath.toFile().length());
        } else {
            log.error("Failed to download {}", name);
        }
    }

    private static void prepareImages(OcrProperties properties) {
        String imagesDir = properties.getBenchmark().getImagesBaseDir();
        Path smallPath = Paths.get(imagesDir, "small_single_line.png");
        Path mediumPath = Paths.get(imagesDir, "medium_card.png");
        Path largePath = Paths.get(imagesDir, "large_document.png");
        Path blankPath = Paths.get(imagesDir, "blank_white.png");
        Path noisyPath = Paths.get(imagesDir, "noisy_sample.png");

        if (!Files.exists(smallPath)) {
            generateTextImage(smallPath, 360, 60,
                    new String[]{"浙A·88888 电子通行证"},
                    22, Color.WHITE, Color.BLACK);
        }

        if (!Files.exists(mediumPath)) {
            generateTextImage(mediumPath, 800, 500,
                    new String[]{
                            "中华人民共和国居民身份证",
                            "姓名：张三",
                            "性别：男   民族：汉",
                            "出生：1990年10月24日",
                            "住址：北京市海淀区中关村南大街1号院",
                            "公民身份号码：110108199010241234"
                    },
                    24, new Color(245, 247, 250), new Color(30, 30, 30));
        }

        if (!Files.exists(largePath)) {
            String[] lines = new String[40];
            lines[0] = "技术评估与架构选型规范报告";
            lines[1] = "一、项目背景与评估目标";
            lines[2] = "在现代化高并发微服务体系建设中，OCR（光学字符识别）作为核心视觉理解基础设施，";
            lines[3] = "直接影响智能审核、单据识别与自动化归档等核心业务链路的吞吐量与可用性指标。";
            lines[4] = "二、主流 Java OCR 引擎对比分析";
            lines[5] = "当前业界在 Java 领域主要存在两类主流开源方案：mica-ppocr 与 RapidOCR4j。";
            for (int i = 6; i < 40; i++) {
                lines[i] = String.format("第 %02d 项性能指标评估测试段落：高并发下吞吐率 QPS、延迟 P99 与内存 RSS 占用稳定性指标监测。", i);
            }
            generateTextImage(largePath, 1600, 2200, lines, 26, Color.WHITE, new Color(20, 20, 20));
        }

        if (!Files.exists(blankPath)) {
            generateTextImage(blankPath, 500, 500, new String[]{}, 20, Color.WHITE, Color.WHITE);
        }

        if (!Files.exists(noisyPath)) {
            generateNoisyTextImage(noisyPath, 400, 150, "验证码防伪标识 CODE-9527", 24);
        }
    }

    private static boolean downloadFile(String urlStr, Path targetPath) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlStr).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                return downloadFile(newUrl, targetPath);
            }

            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("Download returned status: {}", status);
                return false;
            }

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.warn("Error downloading from {}: {}", urlStr, e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void generateTextImage(Path outputPath, int width, int height, String[] lines, int fontSize, Color bgColor, Color textColor) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(bgColor);
            g.fillRect(0, 0, width, height);

            g.setColor(textColor);
            Font font = new Font("Microsoft YaHei", Font.PLAIN, fontSize);
            g.setFont(font);

            FontMetrics fm = g.getFontMetrics();
            int lineHeight = fm.getHeight() + 10;
            int startY = (height - (lines.length * lineHeight)) / 2 + fm.getAscent();
            if (startY < 40) startY = 40;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int x = 40;
                int y = startY + (i * lineHeight);
                g.drawString(line, x, y);
            }

            g.dispose();
            ImageIO.write(image, "PNG", outputPath.toFile());
            log.info("Generated synthetic test image: {} ({}x{})", outputPath.getFileName(), width, height);
        } catch (Exception e) {
            log.error("Failed to generate test image {}", outputPath, e);
        }
    }

    public static void generateNoisyTextImage(Path outputPath, int width, int height, String text, int fontSize) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();

            g.setColor(new Color(240, 240, 245));
            g.fillRect(0, 0, width, height);

            // Draw noise lines
            g.setColor(new Color(200, 200, 200));
            for (int i = 0; i < 30; i++) {
                int x1 = (int) (Math.random() * width);
                int y1 = (int) (Math.random() * height);
                int x2 = (int) (Math.random() * width);
                int y2 = (int) (Math.random() * height);
                g.drawLine(x1, y1, x2, y2);
            }

            // Draw text
            g.setColor(new Color(40, 60, 120));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int x = (width - fm.stringWidth(text)) / 2;
            int y = (height - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, x, y);

            // Draw salt-and-pepper noise
            for (int i = 0; i < 400; i++) {
                int px = (int) (Math.random() * width);
                int py = (int) (Math.random() * height);
                image.setRGB(px, py, Color.GRAY.getRGB());
            }

            g.dispose();
            ImageIO.write(image, "PNG", outputPath.toFile());
            log.info("Generated noisy test image: {} ({}x{})", outputPath.getFileName(), width, height);
        } catch (Exception e) {
            log.error("Failed to generate noisy image {}", outputPath, e);
        }
    }
}
