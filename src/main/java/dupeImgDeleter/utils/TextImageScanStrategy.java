package dupeImgDeleter.utils;

import org.apache.commons.io.FilenameUtils;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

public class TextImageScanStrategy implements ScanStrategy {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Path STAGING_DIR = Paths.get("C:\\DeletedImages");

    // Threshold Tuners
    private static final int LUMINANCE_BLACK_THRESHOLD = 35;  // Deemed "Black" background
    private static final int LUMINANCE_WHITE_THRESHOLD = 200; // Deemed "White" text
    private static final double MIN_BLACK_RATIO = 0.85;       // Min percent of black background
    private static final double MAX_WHITE_RATIO = 0.15;       // Max percent of white text
    private static final double MIN_WHITE_RATIO = 0.001;      // Min percent text so blank images pass through

    @Override
    public void execute(File directory, boolean isDryRun, Consumer<String> logger) throws Exception {
        if (isDryRun) {
            try {
                Files.createDirectories(STAGING_DIR);
                logger.accept("Dry Run Mode: Staging text images in " + STAGING_DIR.toAbsolutePath());
            } catch (IOException e) {
                logger.accept("[ERROR] Failed to create staging directory: " + e.getMessage());
                return;
            }
        } else {
            logger.accept("Warning: Permanent deletion mode active.");
        }

        logger.accept("\nScanning directory for text images... (Please wait)");

        List<Path> imagePaths = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isImageFile)
                 .forEach(imagePaths::add);
        } catch (IOException e) {
            logger.accept("[FATAL] Error scanning directory tree: " + e.getMessage());
            return;
        }

        logger.accept("Found " + imagePaths.size() + " image files. Analyzing pixel content...");

        long processedCount = 0;
        long bytesSaved = 0;
        AtomicInteger uniqueCounter = new AtomicInteger(1);

        // Process in parallel to maximize CPU performance during disk read gaps
        List<Path> matchedImages = imagePaths.parallelStream()
                .filter(path -> isWhiteTextOnBlackBackground(path, logger))
                .toList();

        logger.accept("\nProcessing matching text images...");
        for (Path path : matchedImages) {
            try {
                long size = Files.size(path);

                if (isDryRun) {
                    String rawName = FilenameUtils.getBaseName(path.getFileName().toString());
                    String ext = FilenameUtils.getExtension(path.getFileName().toString());
                    Path targetPath = STAGING_DIR.resolve(rawName + "_txt_" + uniqueCounter.getAndIncrement() + "." + ext);

                    Files.move(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.accept("  [STAGED]  " + path.getFileName() + " -> " + targetPath.toAbsolutePath());
                } else {
                    Files.delete(path);
                    logger.accept("  [DELETED] " + path.toAbsolutePath());
                }

                processedCount++;
                bytesSaved += size;
            } catch (IOException e) {
                logger.accept("  [FAILED]  " + path.getFileName() + " -> " + e.getMessage());
            }
        }

        logger.accept("\n--- Scan Complete ---");
        String action = isDryRun ? "moved to staging" : "permanently deleted";
        logger.accept("Total text images " + action + ": " + processedCount);
        logger.accept("Total space managed: " + (bytesSaved / (1024 * 1024)) + " MB");
    }

    private boolean isWhiteTextOnBlackBackground(Path path, Consumer<String> logger) {
        try {
            // Note: Disk read occurs here. 
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) return false;

            int width = image.getWidth();
            int height = image.getHeight();
            
            // FAST-FAIL: Check 4 corners first before running a complete matrix scan
            if (!isDark(image.getRGB(0, 0)) || 
                !isDark(image.getRGB(width - 1, 0)) || 
                !isDark(image.getRGB(0, height - 1)) || 
                !isDark(image.getRGB(width - 1, height - 1))) {
                return false; 
            }

            int totalPixels = width * height;
            int blackCount = 0;
            int whiteCount = 0;

            // Step-sampling configuration to prevent reading every single pixel on large images
            // For massive images, check every 2nd or 3rd pixel to optimize CPU cache lines
            int sampleStep = (totalPixels > 1_000_000) ? 2 : 1;
            int sampledTotal = 0;

            for (int y = 0; y < height; y += sampleStep) {
                for (int x = 0; x < width; x += sampleStep) {
                    int rgb = image.getRGB(x, y);
                    sampledTotal++;

                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;

                    // Relative Luminance formula calculation
                    double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;

                    if (luminance < LUMINANCE_BLACK_THRESHOLD) {
                        blackCount++;
                    } else if (luminance > LUMINANCE_WHITE_THRESHOLD) {
                        whiteCount++;
                    }
                }
            }

            double blackRatio = (double) blackCount / sampledTotal;
            double whiteRatio = (double) whiteCount / sampledTotal;

            // Check if the image fits our structural thresholds
            return blackRatio >= MIN_BLACK_RATIO && 
                   whiteRatio >= MIN_WHITE_RATIO && 
                   whiteRatio <= MAX_WHITE_RATIO;

        } catch (IOException e) {
            logger.accept("[ERROR] Could not read image matrix data: " + path.getFileName() + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luminance < LUMINANCE_BLACK_THRESHOLD;
    }

    private boolean isImageFile(Path path) {
        String ext = FilenameUtils.getExtension(path.getFileName().toString()).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }
}