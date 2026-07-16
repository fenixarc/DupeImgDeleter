package dupeImgDeleter.utils;

import org.apache.commons.io.FilenameUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

public class DuplicateImageDeleter extends JFrame {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Path STAGING_DIR = Paths.get("C:\\DeletedImages");

    private final JTextArea logArea;
    private final JProgressBar progressBar;
    private final JButton startButton;

    public DuplicateImageDeleter() {
        // Set up the Window Frame
        setTitle("Duplicate Image Cleaner");
        setSize(650, 450);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Text Log Area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom Panel for Controls and Progress
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        bottomPanel.add(progressBar, BorderLayout.CENTER);

        startButton = new JButton("Select Folder & Scan");
        startButton.addActionListener(e -> selectAndRunScan());
        bottomPanel.add(startButton, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void selectAndRunScan() {
        // Directory Selection
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Directory to Scan for Duplicate Images");
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            logArea.append("No directory selected.\n");
            return;
        }

        File selectedDir = fileChooser.getSelectedFile();

        // Staging vs Deletion Selection
        Object[] options = {"Stage Duplicates (Dry Run)", "Permanently Delete"};
        int modeChoice = JOptionPane.showOptionDialog(this,
                "How would you like to handle duplicate images?",
                "Select Operation Mode",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (modeChoice == JOptionPane.CLOSED_OPTION) {
            logArea.append("Operation canceled.\n");
            return;
        }

        boolean isDryRun = (modeChoice == JOptionPane.YES_OPTION);

        // Prep UI for running state
        startButton.setEnabled(false);
        logArea.setText("");
        progressBar.setIndeterminate(true);
        progressBar.setString("Scanning files...");

        // Kick off background thread execution
        ScanWorker worker = new ScanWorker(selectedDir, isDryRun);
        worker.execute();
    }

    /**
     * SwingWorker handles processing on a background thread while updating the GUI safely.
     */
    private class ScanWorker extends SwingWorker<Void, String> {
        private final File directory;
        private final boolean isDryRun;

        public ScanWorker(File directory, boolean isDryRun) {
            this.directory = directory;
            this.isDryRun = isDryRun;
        }

        @Override
        protected Void doInBackground() throws Exception {
        	// Start the execution timer
            long startTime = System.currentTimeMillis();
            
            publish("Target folder: " + directory.getAbsolutePath());
            
            if (isDryRun) {
                try {
                    Files.createDirectories(STAGING_DIR);
                    publish("Dry Run Mode: Staging duplicates in " + STAGING_DIR.toAbsolutePath());
                } catch (IOException e) {
                    publish("[ERROR] Failed to create staging directory: " + e.getMessage());
                    return null;
                }
            } else {
                publish("Warning: Permanent deletion mode active.");
            }

            publish("\nIndexing files... (Please wait)");
            Map<String, List<Path>> hashMap = new HashMap<>();

            // Gather files and calculate hashes
            try (Stream<Path> paths = Files.walk(directory.toPath())) {
                paths.filter(Files::isRegularFile)
                     .filter(DuplicateImageDeleter::isImageFile)
                     .forEach(path -> {
                         try {
                             String hash = generateMD5(path);
                             hashMap.computeIfAbsent(hash, k -> new ArrayList<>()).add(path);
                         } catch (IOException e) {
                             publish("[ERROR] Could not read file: " + path.getFileName() + " (" + e.getMessage() + ")");
                         }
                     });
            } catch (IOException e) {
                publish("[FATAL] Error scanning directory tree: " + e.getMessage());
                return null;
            }

            // Clean up duplicates
            publish("\nAnalyzing duplicates...");
            long processedCount = 0;
            long bytesSaved = 0;
            int uniqueCounter = 1;

            for (Map.Entry<String, List<Path>> entry : hashMap.entrySet()) {
                List<Path> fileGroup = entry.getValue();

                if (fileGroup.size() > 1) {
                    Path original = fileGroup.get(0);
                    publish("\nFound matching group (" + fileGroup.size() + " files):");
                    publish("  [KEEPING] " + original.toAbsolutePath());

                    for (int i = 1; i < fileGroup.size(); i++) {
                        Path duplicate = fileGroup.get(i);
                        try {
                            long size = Files.size(duplicate);

                            if (isDryRun) {
                                String rawName = FilenameUtils.getBaseName(duplicate.getFileName().toString());
                                String ext = FilenameUtils.getExtension(duplicate.getFileName().toString());
                                Path targetPath = STAGING_DIR.resolve(rawName + "_" + uniqueCounter++ + "." + ext);

                                Files.move(duplicate, targetPath, StandardCopyOption.REPLACE_EXISTING);
                                publish("  [STAGED]  " + duplicate.getFileName() + " -> " + targetPath.toAbsolutePath());
                            } else {
                                Files.delete(duplicate);
                                publish("  [DELETED] " + duplicate.toAbsolutePath());
                            }

                            processedCount++;
                            bytesSaved += size;
                        } catch (IOException e) {
                            publish("  [FAILED]  " + duplicate.getFileName() + " -> " + e.getMessage());
                        }
                    }
                }
            }
            
            // Calculate runtime metrics
            long endTime = System.currentTimeMillis();
            long durationMillis = endTime - startTime;
            String timeString = formatDuration(durationMillis);

            // Summary Info
            publish("\n--- Scan Complete ---");
            String action = isDryRun ? "moved to staging" : "permanently deleted";
            publish("Total duplicate files " + action + ": " + processedCount);
            publish("Total space managed: " + (bytesSaved / (1024 * 1024)) + " MB");
            publish("Total run time: " + timeString);

            return null;
        }
        
        private String formatDuration(long millis) {
            long totalSeconds = millis / 1000;
            if (totalSeconds < 60) {
                double preciseSeconds = millis / 1000.0;
                return String.format(Locale.US, "%.2f seconds", preciseSeconds);
            } else {
                long minutes = totalSeconds / 60;
                long seconds = totalSeconds % 60;
                return String.format(Locale.US, "%d min, %d sec", minutes, seconds);
            }
        }

        @Override
        protected void process(List<String> chunks) {
            // Append the logging text chunks onto the screen area safely
            for (String message : chunks) {
                logArea.append(message + "\n");
            }
        }

        @Override
        protected void done() {
            // Re-enable window UI components once processing concludes
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("Finished");
            startButton.setEnabled(true);
        }
    }

    private static boolean isImageFile(Path path) {
        String ext = FilenameUtils.getExtension(path.getFileName().toString()).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private static String generateMD5(Path path) throws IOException {
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

    public static void main(String[] args) {
        // Launch window inside the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new DuplicateImageDeleter().setVisible(true);
        });
    }
}