/*
 * SonarQube Java
 * Copyright (C) 2012-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.java.api;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class TheCache {
  public static IdentityHashMap<InputFile, Object> changedInputFiles = new IdentityHashMap<>();
  public static List<Consumer<InputFile>> actionsForCachedFiles = new ArrayList<>();

  private static final Logger LOG = Loggers.get(TheCache.class);

  public static Path cachingDir = Path.of("/tmp", "caching-test");
  public static Path source2ClassFilesCachePath = cachingDir.resolve("source2class.json");
  public static Path hash2UcfgsCachePath = cachingDir.resolve("hash2ucfg.json");
  public static Path ucfgStorage = cachingDir.resolve("ucfgs");
  public static MessageDigest md;

  static {
    if (Files.notExists(cachingDir)) {
      try {
        Files.createDirectories(cachingDir);
      } catch (IOException e) {
        LOG.error("Could not create caching directory " + cachingDir, e);
        e.printStackTrace();
      }

      if (Files.notExists(ucfgStorage)) {
        try {
          Files.createDirectories(ucfgStorage);
        } catch (IOException e) {
          LOG.error("Could not create ucfg directory " + ucfgStorage, e);
          e.printStackTrace();
        }
      }
    }

    try {
      // TODO: different algo better (collision resistance)?
      md = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  public TheCache(Path binariesPath) {
    this.binariesPath = binariesPath;
  }

  public final Path binariesPath;

  public Map<String, List<String>> source2ClassFilesCache = (Map<String, List<String>>) loadCache(source2ClassFilesCachePath);
  public Map<Integer, List<String>> hash2UcfgsCache = (Map<Integer, List<String>>) loadCache(hash2UcfgsCachePath);


  private Object loadCache(Path cacheFile) {
    try (
      var fileIn = Files.newInputStream(cacheFile);
      var objIn = new ObjectInputStream(fileIn)
    ) {
      return objIn.readObject();
    } catch (Exception e) {
      LOG.warn("Could not load cache from " + cacheFile.toAbsolutePath(), e);
      return new HashMap<>();
    }
  }

  @CheckForNull
  public Collection<Path> cachedUcfgs(InputFile inputFile) {
    var classFiles = getClassFiles(inputFile);
    if (classFiles == null) return null;

    var hash = hashClassFiles(classFiles);
    return getUcfgFiles(hash);
  }

  @CheckForNull
  Collection<Path> getUcfgFiles(int hash) {
    var cacheHit = hash2UcfgsCache.get(hash);
    if (cacheHit == null) return null;
    else return cacheHit.stream().map(Path::of).collect(Collectors.toList());
  }

  public int hashClassFiles(Collection<Path> classFiles) {
    return classFiles.stream().map(this::hashSum)
      .sorted()
      .collect(Collectors.toList())
      .hashCode();
    // TODO: probably not great to use hashCode() here. Need better conversion to avoid collisions.
  }

  @CheckForNull
  public Collection<Path> getClassFiles(InputFile inputFile) {
    var fileKey = inputFile.key();
    var cacheHit = source2ClassFilesCache.get(fileKey);
    if (cacheHit == null) return null;
    else return cacheHit.stream()
      .map(this::classNameToFilePath)
      .collect(Collectors.toList());
  }

  private String hashSum(Path path) {
    try (var in = Files.newInputStream(path);
         DigestInputStream dis = new DigestInputStream(in, md)
    ) {
      // TODO: long better than byte array (efficiency)?
      return new String(dis.readAllBytes());
    } catch (IOException e) {
      LOG.warn("Unable to hash the sum", e);
      return "";
    }
  }

  public Path classNameToFilePath(String qualifiedClassName) {
    return binariesPath.resolve(qualifiedClassName.replace('.', File.separatorChar) + ".class");
  }

  public void storeCache(Object cache, Path cacheFile) {
    try (
      var fOut = Files.newOutputStream(cacheFile);
      var objOut = new ObjectOutputStream(fOut);
    ) {
      objOut.writeObject(cache);
    } catch (Exception e) {
      LOG.warn("Could not write cache to " + cacheFile.toAbsolutePath(), e);
    }
  }
}
