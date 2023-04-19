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

import com.beust.jcommander.ParameterException;

import net.hydromatic.sqllogictest.executors.SqlSltTestExecutor;
import org.apache.calcite.sql.parser.SqlParseException;

import org.checkerframework.checker.nullness.qual.Nullable;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Execute all SqlLogicTest tests.
 */
public class Main {

  static class TestLoader extends SimpleFileVisitor<Path> {
    int errors = 0;
    final TestStatistics statistics;
    public final ExecutionOptions options;

    /**
     * Creates a new class that reads tests from a directory tree and executes them.
     */
    TestLoader(ExecutionOptions options) {
      this.statistics = new TestStatistics(options.stopAtFirstError);
      this.options = options;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      SqlSltTestExecutor executor;
      try {
        executor = this.options.getExecutor();
      } catch (IOException | SQLException e) {
        // Can't add exceptions to the overridden method visitFile
        throw new RuntimeException(e);
      }
      String extension = Utilities.getFileExtension(file.toString());
      if (attrs.isRegularFile() && extension != null && extension.equals("test")) {
        SltTestFile test = null;
        try {
          System.out.println("Running " + file);
          test = new SltTestFile(file.toString());
          test.parse();
        } catch (Exception ex) {
          System.err.println("Error while executing test " + file + ": " + ex.getMessage());
          this.errors++;
        }
        if (test != null) {
          try {
            TestStatistics stats = executor.execute(test, options);
            this.statistics.add(stats);
          } catch (SqlParseException | IOException |
                   SQLException | NoSuchAlgorithmException ex) {
            // Can't add exceptions to the overridden method visitFile
            throw new IllegalArgumentException(ex);
          }
        }
      }
      return FileVisitResult.CONTINUE;
    }
  }

  static int abort(boolean abort, ExecutionOptions options, @Nullable String message) {
    if (message != null)
      System.err.println(message);
    options.usage();
    if (abort) {
      System.exit(1);
    }
    return 1;
  }

  @SuppressWarnings("java:S4792") // Log configuration is safe
  public static void main(String[] argv) throws IOException {
    main2(true, argv);
  }

  /** As {@link #main} but does not call {@link System#exit} if {@code exit}
   * is false. */
  public static int main2(boolean exit, String[] argv) throws IOException {
    Logger rootLogger = LogManager.getLogManager().getLogger("");
    rootLogger.setLevel(Level.WARNING);
    for (Handler h : rootLogger.getHandlers()) {
      h.setLevel(Level.INFO);
    }

    ExecutionOptions options = new ExecutionOptions();
    try {
      options.parse(argv);
      System.out.println(options);
    } catch (ParameterException ex) {
      return abort(exit, options, null);
    }
    if (options.help)
      return abort(exit, options, null);

    TestLoader loader = new TestLoader(options);
    URL root = Thread.currentThread().getContextClassLoader().getResource("test");
    for (String file : options.getDirectories()) {
      Path path = Paths.get(root.getPath(), file);
      Files.walkFileTree(path, loader);
    }
    System.out.println("Files that could not be not parsed: " + loader.errors);
    System.out.println(loader.statistics);
    return 0;
  }
}
