package dupeImgDeleter.utils;

import org.apache.commons.io.FilenameUtils;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimilarImageScanStrategy implements ScanStrategy {

    private static final int HAMMING_THRESHOLD = 4;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Path STAGING_DIR = Paths.get("C:\\DeletedImages");

    @Override
    public void execute(File directory, boolean isDryRun, Consumer<String> logger) throws Exception {
        if (isDryRun) {
            try {
                Files.createDirectories(STAGING_DIR); // Create the staging directory
                logger.accept("Dry Run Mode: Staging duplicates in " + STAGING_DIR.toAbsolutePath());
            } catch (IOException e) {
                logger.accept("[ERROR] Failed to create staging directory: " + e.getMessage());
                return;
            }
        } else {
            logger.accept("Warning: Permanent deletion mode active.");
        }

        logger.accept("\nIndexing files... (Please wait)");
        
        // Find candidate image paths in the target directory tree
        List<Path> imagePaths = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            imagePaths = paths.filter(Files::isRegularFile)
                              .filter(this::isImageFile)
                              .collect(Collectors.toList());
        } catch (IOException e) {
            logger.accept("[FATAL] Error scanning directory tree: " + e.getMessage());
            return;
        }

        logger.accept(String.format("Found %d candidate image files. Computing perceptual hashes...", imagePaths.size()));

        // Map to store calculated hashes to avoid re-reading files from disk
        Map<Path, Long> hashMap = new HashMap<>();
        for (Path path : imagePaths) {
            try {
                long hash = computeDifferenceHash(path);
                hashMap.put(path, hash);
            } catch (IOException e) {
                logger.accept("[WARN] Failed to process image: " + path.getFileName() + " (" + e.getMessage() + ")");
            }
        }

        logger.accept("\nAnalyzing perceptual similarities...");
        
        Set<Path> processedFiles = new HashSet<>();
        long processedCount = 0;
        long bytesSaved = 0;
        AtomicInteger uniqueCounter = new AtomicInteger(1);

        for (int i = 0; i < imagePaths.size(); i++) {
            Path fileA = imagePaths.get(i);
            if (processedFiles.contains(fileA) || !hashMap.containsKey(fileA)) continue;

            long hashA = hashMap.get(fileA);
            List<Path> similarGroup = new ArrayList<>();

            for (int j = i + 1; j < imagePaths.size(); j++) {
                Path fileB = imagePaths.get(j);
                if (processedFiles.contains(fileB) || !hashMap.containsKey(fileB)) continue;

                long hashB = hashMap.get(fileB);
                if (calculateHammingDistance(hashA, hashB) <= HAMMING_THRESHOLD) {
                    similarGroup.add(fileB);
                }
            }

            if (!similarGroup.isEmpty()) {
                logger.accept("\nFound matching group (" + (similarGroup.size() + 1) + " files):");
                logger.accept("  [KEEPING] " + fileA.toAbsolutePath());
                processedFiles.add(fileA);

                for (Path duplicate : similarGroup) {
                    processedFiles.add(duplicate);
                    try {
                        long size = Files.size(duplicate);

                        if (isDryRun) {
                            // Stage the file with a unique name in C:\DeletedImages
                            String rawName = FilenameUtils.getBaseName(duplicate.getFileName().toString());
                            String ext = FilenameUtils.getExtension(duplicate.getFileName().toString());
                            Path targetPath = STAGING_DIR.resolve(rawName + "_" + uniqueCounter.getAndIncrement() + "." + ext);

                            Files.move(duplicate, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            logger.accept("  [STAGED]  " + duplicate.getFileName() + " -> " + targetPath.toAbsolutePath());
                        } else {
                            // Delete the duplicate permanently
                            Files.delete(duplicate); 
                            logger.accept("  [DELETED] " + duplicate.toAbsolutePath()); 
                        }

                        processedCount++;
                        bytesSaved += size;
                    } catch (IOException e) {
                        logger.accept("  [FAILED]  " + duplicate.getFileName() + " -> " + e.getMessage()); 
                    }
                }
            }
        }

        // Print Summary Details
        logger.accept("\n--- Scan Complete ---");
        String action = isDryRun ? "moved to staging" : "permanently deleted";
        logger.accept("Total duplicate files " + action + ": " + processedCount);
        logger.accept("Total space managed: " + (bytesSaved / (1024 * 1024)) + " MB");
    }

    /**
     * Computes a 64-bit Difference Hash (dHash) using Java NIO Path.
     */
    private long computeDifferenceHash(Path path) throws IOException {
        BufferedImage original = ImageIO.read(path.toFile());
        if (original == null) {
            throw new IOException("Unsupported or corrupt image format.");
        }

        BufferedImage resized = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = resized.getGraphics();
        g.drawImage(original, 0, 0, 9, 8, null);
        g.dispose();

        long hash = 0;
        int bitCounter = 0;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int pixelLeft = resized.getRaster().getSample(x, y, 0);
                int pixelRight = resized.getRaster().getSample(x + 1, y, 0);
                
                if (pixelLeft > pixelRight) {
                    hash |= (1L << bitCounter);
                }
                bitCounter++;
            }
        }
        return hash;
    }

    private int calculateHammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    private boolean isImageFile(Path path) {
        String ext = FilenameUtils.getExtension(path.getFileName().toString()).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }
}