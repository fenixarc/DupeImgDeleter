package dupeImgDeleter.utils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Locale;

public class DuplicateImageDeleter extends JFrame {

    private final JTextArea logArea;
    private final JProgressBar progressBar;
    private final JButton startButton;

    public DuplicateImageDeleter() {
        setTitle("Image Utility Toolkit");
        setSize(650, 450);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

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
        // Module Selection Mode
        Object[] scanModes = {"Find Duplicate Images", "Find White Text on Black BG"};
        int modeSelection = JOptionPane.showOptionDialog(this,
                "Which scanning function would you like to execute?",
                "Select Feature Mode",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                scanModes,
                scanModes[0]);

        if (modeSelection == JOptionPane.CLOSED_OPTION) return;

        ScanStrategy selectedStrategy = (modeSelection == JOptionPane.YES_OPTION) 
                ? new DuplicateScanStrategy() 
                : null; // We will plug our new strategy class here next!

        if (selectedStrategy == null && modeSelection == JOptionPane.NO_OPTION) {
            logArea.setText("Text-detection strategy is not implemented yet!\n");
            return;
        }

        // Directory Selection
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Directory to Scan");
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            logArea.append("No directory selected.\n");
            return;
        }
        File selectedDir = fileChooser.getSelectedFile();

        // Operation Mode Option Selection
        Object[] options = {"Stage Matches (Dry Run)", "Permanently Delete"};
        int optionChoice = JOptionPane.showOptionDialog(this,
                "How would you like to handle identified images?",
                "Select Operation Mode",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (optionChoice == JOptionPane.CLOSED_OPTION) {
            logArea.append("Operation canceled.\n");
            return;
        }
        boolean isDryRun = (optionChoice == JOptionPane.YES_OPTION);

        // UI State Reset
        startButton.setEnabled(false);
        logArea.setText("");
        progressBar.setIndeterminate(true);
        progressBar.setString("Processing runtime tasks...");

        // Kick off background thread execution
        ScanWorker worker = new ScanWorker(selectedDir, isDryRun, selectedStrategy);
        worker.execute();
    }

    private class ScanWorker extends SwingWorker<Void, String> {
        private final File directory;
        private final boolean isDryRun;
        private final ScanStrategy strategy;

        public ScanWorker(File directory, boolean isDryRun, ScanStrategy strategy) {
            this.directory = directory;
            this.isDryRun = isDryRun;
            this.strategy = strategy;
        }

        @Override
        protected Void doInBackground() throws Exception {
            long startTime = System.currentTimeMillis();
            publish("Target folder: " + directory.getAbsolutePath());

            try {
                // Execute the selected strategy, passing publish as a method reference
                strategy.execute(directory, isDryRun, this::publish);
            } catch (Exception e) {
                publish("[FATAL ERROR] Strategy execution failed: " + e.getMessage());
            }

            long endTime = System.currentTimeMillis();
            publish("Total run time: " + formatDuration(endTime - startTime));
            return null;
        }

        private String formatDuration(long millis) {
            long totalSeconds = millis / 1000;
            if (totalSeconds < 60) {
                return String.format(Locale.US, "%.2f seconds", millis / 1000.0);
            } else {
                return String.format(Locale.US, "%d min, %d sec", totalSeconds / 60, totalSeconds % 60);
            }
        }

        @Override
        protected void process(List<String> chunks) {
            for (String message : chunks) {
                logArea.append(message + "\n");
            }
        }

        @Override
        protected void done() {
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("Finished");
            startButton.setEnabled(true);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new DuplicateImageDeleter().setVisible(true);
        });
    }
}