package dupeImgDeleter.utils;

import org.apache.commons.io.FilenameUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DuplicateScanStrategy implements ScanStrategy {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Path STAGING_DIR = Paths.get("C:\\DeletedImages");

    @Override
    public void execute(File directory, boolean isDryRun, Consumer<String> logger) throws Exception {
        if (isDryRun) {
            try {
                Files.createDirectories(STAGING_DIR);
                logger.accept("Dry Run Mode: Staging duplicates in " + STAGING_DIR.toAbsolutePath());
            } catch (IOException e) {
                logger.accept("[ERROR] Failed to create staging directory: " + e.getMessage());
                return;
            }
        } else {
            logger.accept("Warning: Permanent deletion mode active.");
        }

        logger.accept("\nIndexing files and checking sizes... (Please wait)");
        
        // Group files by file size
        Map<Long, List<Path>> filesBySize = new HashMap<>();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isImageFile)
                 .forEach(path -> {
                     try {
                         long size = Files.size(path);
                         filesBySize.computeIfAbsent(size, k -> new ArrayList<>()).add(path);
                     } catch (IOException e) {
                         logger.accept("[ERROR] Could not read file size: " + path.getFileName() + " (" + e.getMessage() + ")");
                     }
                 });
        } catch (IOException e) {
            logger.accept("[FATAL] Error scanning directory tree: " + e.getMessage());
            return;
        }

        // Filter out unique sizes (Fast-Fail Rule)
        List<Path> candidateFiles = filesBySize.values().stream()
                .filter(list -> list.size() > 1)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        logger.accept("Found " + candidateFiles.size() + " files sharing identical sizes. Processing hashes...");

        // Parallel processing of hashes on candidates
        Map<String, List<Path>> hashMap = new ConcurrentHashMap<>();
        candidateFiles.parallelStream().forEach(path -> {
            try {
                String hash = generateMD5(path);
                hashMap.computeIfAbsent(hash, k -> Collections.synchronizedList(new ArrayList<>())).add(path);
            } catch (IOException e) {
                logger.accept("[ERROR] Could not hash file: " + path.getFileName() + " (" + e.getMessage() + ")");
            }
        });

        // Clean up duplicates
        logger.accept("\nAnalyzing duplicates...");
        long processedCount = 0;
        long bytesSaved = 0;
        AtomicInteger uniqueCounter = new AtomicInteger(1);

        for (Map.Entry<String, List<Path>> entry : hashMap.entrySet()) {
            List<Path> fileGroup = entry.getValue();

            if (fileGroup.size() > 1) {
                Path original = fileGroup.get(0);
                logger.accept("\nFound matching group (" + fileGroup.size() + " files):");
                logger.accept("  [KEEPING] " + original.toAbsolutePath());

                for (int i = 1; i < fileGroup.size(); i++) {
                    Path duplicate = fileGroup.get(i);
                    try {
                        long size = Files.size(duplicate);

                        if (isDryRun) {
                            String rawName = FilenameUtils.getBaseName(duplicate.getFileName().toString());
                            String ext = FilenameUtils.getExtension(duplicate.getFileName().toString());
                            Path targetPath = STAGING_DIR.resolve(rawName + "_" + uniqueCounter.getAndIncrement() + "." + ext);

                            Files.move(duplicate, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            logger.accept("  [STAGED]  " + duplicate.getFileName() + " -> " + targetPath.toAbsolutePath());
                        } else {
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
        
        // Print Summary Details (UI layer tracks the elapsed time metric externally)
        logger.accept("\n--- Scan Complete ---");
        String action = isDryRun ? "moved to staging" : "permanently deleted";
        logger.accept("Total duplicate files " + action + ": " + processedCount);
        logger.accept("Total space managed: " + (bytesSaved / (1024 * 1024)) + " MB");
    }

    private boolean isImageFile(Path path) {
        String ext = FilenameUtils.getExtension(path.getFileName().toString()).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private String generateMD5(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest.digest()) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not found", e);
        }
    }
}