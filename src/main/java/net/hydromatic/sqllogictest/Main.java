/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 * SPDX-License-Identifier: Apache-2.0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 *
 */

package net.hydromatic.sqllogictest;

import net.hydromatic.sqllogictest.executors.SqlSltTestExecutor;

import com.beust.jcommander.ParameterException;

import org.apache.calcite.sql.parser.SqlParseException;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Execute all SqlLogicTest tests.
 */
public final class Main {
  final boolean exit;
  final PrintStream out;
  final PrintStream err;
  final ExecutionOptions options;
  final String[] args;

  static final String SLT_GIT =
      "https://github.com/gregrahn/sqllogictest/archive/refs/heads/master.zip";

  static class TestLoader extends SimpleFileVisitor<Path> {
    final Main main;
    int errors = 0;
    final TestStatistics statistics;

    /**
     * Creates a new class that reads tests from a directory tree and
     * executes them.
     */
    TestLoader(Main main) {
      this.statistics = new TestStatistics(main.options.stopAtFirstError);
      this.main = main;
    }

    @Override public FileVisitResult visitFile(Path file,
        BasicFileAttributes attrs) {
      SqlSltTestExecutor executor;
      try {
        executor = main.options.getExecutor();
      } catch (IOException | SQLException e) {
        // Can't add exceptions to the overridden method visitFile
        throw new RuntimeException(e);
      }
      String extension = Utilities.getFileExtension(file.toString());
      if (attrs.isRegularFile()
          && extension != null
          && extension.equals("test")) {
        SltTestFile test = null;
        try {
          main.out.println("Running " + file);
          test = new SltTestFile(file.toString());
          test.parse();
        } catch (Exception ex) {
          main.err.println("Error while executing test " + file + ": "
              + ex.getMessage());
          this.errors++;
        }
        if (test != null) {
          try {
            TestStatistics stats = executor.execute(test, main.options);
            this.statistics.add(stats);
          } catch (SqlParseException | IOException
              | SQLException | NoSuchAlgorithmException ex) {
            // Can't add exceptions to the overridden method visitFile
            throw new IllegalArgumentException(ex);
          }
        }
      }
      return FileVisitResult.CONTINUE;
    }
  }

  int abort(@Nullable String message) {
    if (message != null) {
      err.println(message);
    }
    options.usage();
    if (exit) {
      System.exit(1);
    }
    return 1;
  }

  @Nullable File newFile(File destinationDir, ZipEntry zipEntry)
      throws IOException {
    String name = zipEntry.getName();
    name = name.replace("sqllogictest-master/", "");
    if (name.isEmpty()) {
      return null;
    }
    File destFile = new File(destinationDir, name);
    String destDirPath = destinationDir.getCanonicalPath();
    String destFilePath = destFile.getCanonicalPath();
    if (!destFilePath.startsWith(destDirPath + File.separator)) {
      throw new IOException("Entry is outside of the target dir: " + name);
    }
    return destFile;
  }

  public void install(File directory) throws IOException {
    File zip = File.createTempFile("out", ".zip", new File("."));
    out.println("Downloading SLT from " + SLT_GIT + " into "
        + zip.getAbsolutePath());
    zip.deleteOnExit();
    InputStream in = new URL(SLT_GIT).openStream();
    Files.copy(in, zip.toPath(), StandardCopyOption.REPLACE_EXISTING);

    out.println("Unzipping data");
    try (ZipInputStream zis =
         new ZipInputStream(Files.newInputStream(zip.toPath()))) {
      ZipEntry zipEntry = zis.getNextEntry();
      while (zipEntry != null) {
        File newFile = newFile(directory, zipEntry);
        if (newFile != null) {
          out.println("Creating " + newFile.getPath());
          if (zipEntry.isDirectory()) {
            if (!newFile.isDirectory() && !newFile.mkdirs()) {
              throw new IOException("Failed to create directory " + newFile);
            }
          } else {
            File parent = newFile.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
              throw new IOException("Failed to create directory " + parent);
            }

            try (FileOutputStream fos = new FileOutputStream(newFile)) {
              int len;
              byte[] buffer = new byte[1024];
              while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
              }
            }
          }
        }
        zipEntry = zis.getNextEntry();
      }
      zis.closeEntry();
    }
  }

  /** Command-line entry point. */
  public static void main(String[] args) throws IOException {
    main2(true, System.out, System.err, args);
  }

  /** As {@link #main} but does not call {@link System#exit} if {@code exit}
   * is false, and allows overriding stdout and stderr. */
  @SuppressWarnings("java:S4792") // Log configuration is safe
  public static int main2(boolean exit, PrintStream out, PrintStream err,
      String[] args) throws IOException {
    Logger rootLogger = LogManager.getLogManager().getLogger("");
    rootLogger.setLevel(Level.WARNING);
    for (Handler h : rootLogger.getHandlers()) {
      h.setLevel(Level.INFO);
    }
    return new Main(exit, out, err, args).run();
  }

  /** Creates a Main. */
  private Main(boolean exit, PrintStream out, PrintStream err, String[] args) {
    this.exit = exit;
    this.out = out;
    this.err = err;
    this.args = args;
    this.options = new ExecutionOptions();
  }

  int run() throws IOException {
    try {
      options.parse(args);
      out.println(options);
    } catch (ParameterException ex) {
      return abort(null);
    }
    if (options.help) {
      return abort(null);
    }
    if (options.sltDirectory == null) {
      return abort("Please specify the directory with the SqlLogicTest suite "
          + "using the -d flag");
    }

    File dir = new File(options.sltDirectory);
    if (dir.exists()) {
      if (!dir.isDirectory()) {
        return abort(options.sltDirectory + " is not a directory");
      }
      if (options.install) {
        err.println("Directory " + options.sltDirectory
            + " exists; skipping download");
      }
    } else {
      if (options.install) {
        install(dir);
      } else {
        return abort(options.sltDirectory
            + " does not exist and no installation was specified");
      }
    }

    TestLoader loader = new TestLoader(this);
    for (String file : options.getDirectories()) {
      Path path = Paths.get(options.sltDirectory + "/test/" + file);
      Files.walkFileTree(path, loader);
    }
    out.println("Files that could not be not parsed: " + loader.errors);
    out.println(loader.statistics);
    return 0;
  }
}
