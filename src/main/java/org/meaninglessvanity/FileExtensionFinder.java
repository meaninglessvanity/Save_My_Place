package org.meaninglessvanity;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileExtensionFinder implements FileFilter {
    private final List<String> fileNames;
    private final List<File> foundFiles;

    /**
     * Constructor for FileExtensionFinder
     */
    public FileExtensionFinder(List<String> fileNames) {
        this.fileNames = fileNames;
        this.foundFiles = new ArrayList<>();
    }

    @Override
    public boolean accept(File pathname) {
        // accept anything that is a folder or matches the fileName string
        return pathname.isDirectory() || endsWithExtensions(pathname);
    }

    /**
     * Searches recursively for all files with the provided filename/extension string
     */
    public List<File> findFiles(List<File> fileList) {
        for (File file : fileList) {
            if (file.isDirectory()) {
                findFiles(Arrays.asList(file.listFiles(this)));
            } else if (endsWithExtensions(file)) {
                foundFiles.add(file);
            }
        }
        return foundFiles;
    }

    public boolean endsWithExtensions(File file) {
        return fileNames.stream().filter(fn ->file.getName().toLowerCase().endsWith(fn)).findFirst().isPresent();
    }
}
