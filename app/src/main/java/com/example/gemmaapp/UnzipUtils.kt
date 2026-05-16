package com.example.gemmaapp

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object UnzipUtils {
    /**
     * Unzips a file to a target directory.
     * @return true if successful, false otherwise.
     */
    fun unzip(zipFile: File, targetDirectory: File): Boolean {
        try {
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val destFile = File(targetDirectory, entry.name)
                    // Security check to prevent Zip Path Traversal (Zip Slip)
                    val destDirPath = targetDirectory.canonicalPath
                    val destFilePath = destFile.canonicalPath
                    if (!destFilePath.startsWith(destDirPath + File.separator)) {
                        throw SecurityException("Entry is outside of the target dir: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
