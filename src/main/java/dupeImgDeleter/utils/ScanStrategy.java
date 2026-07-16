package dupeImgDeleter.utils;

import java.io.File;
import java.util.function.Consumer;

public interface ScanStrategy {
	/**
     * Executes the specific scanning, filtering, and deletion/staging logic.
     * 
     * @param directory The base directory to scan.
     * @param isDryRun True if files should be staged, false for permanent deletion.
     * @param logger A consumer to pipe log messages back to the UI in real-time.
     * @throws Exception If any file system or processing errors occur.
     */
    void execute(File directory, boolean isDryRun, Consumer<String> logger) throws Exception;
}
