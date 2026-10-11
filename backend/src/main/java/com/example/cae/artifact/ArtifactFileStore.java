package com.example.cae.artifact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Only DATA_ROOT relative, regular files can be registered or served. */
@Service
public class ArtifactFileStore {
  public static final long JSON_LIMIT = 2 * 1024 * 1024;
  private final Path root;
  private final ObjectMapper mapper;

  public ArtifactFileStore(@Value("${DATA_ROOT:../.data}") String dataRoot, ObjectMapper mapper) {
    root = Path.of(dataRoot).toAbsolutePath().normalize();
    this.mapper =
        mapper
            .copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public Path root() {
    return root;
  }

  public Path jobRoot(String jobNo) {
    if (jobNo == null || !jobNo.matches("CAE-[0-9]{8}-[0-9]{6,}"))
      throw new IllegalArgumentException("Unsafe Job workspace identity");
    return safe("jobs/" + jobNo);
  }

  public Path safe(String relative) {
    if (relative == null
        || relative.isBlank()
        || relative.startsWith("/")
        || relative.contains("\\")
        || relative.contains(":")
        || relative.indexOf('\0') >= 0
        || relative.length() > 500)
      throw new IllegalArgumentException("Unsafe artifact relative path");
    for (String part : relative.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IllegalArgumentException("Artifact traversal is not allowed");
    Path path = root.resolve(relative).normalize();
    check(path);
    return path;
  }

  public Path requireJob(String relative, String jobNo) {
    Path path = safe(relative);
    if (!path.startsWith(jobRoot(jobNo)) || path.equals(jobRoot(jobNo)))
      throw new IllegalArgumentException(
          "Artifact URI does not belong to the requested Job workspace");
    return path;
  }

  public void check(Path path) {
    if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root))
      throw new IllegalArgumentException("Artifact is outside DATA_ROOT");
    Path current = root;
    if (Files.isSymbolicLink(current))
      throw new IllegalArgumentException("DATA_ROOT cannot be a symbolic link");
    for (Path part : root.relativize(path)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current))
        throw new IllegalArgumentException("Artifact symbolic links are forbidden");
    }
  }

  public JsonDocument readJson(Path path) {
    check(path);
    try (SeekableByteChannel channel = open(path)) {
      if (channel.size() > JSON_LIMIT)
        throw new IllegalArgumentException("Synthetic JSON exceeds 2 MiB");
      BasicFileAttributes before = attributes(path);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (channel.read(buffer) != -1) {
        buffer.flip();
        if ((long) bytes.size() + buffer.remaining() > JSON_LIMIT)
          throw new IllegalArgumentException("Synthetic JSON exceeds 2 MiB");
        bytes.write(buffer.array(), 0, buffer.remaining());
        buffer.clear();
      }
      byte[] encoded = bytes.toByteArray();
      check(path);
      BasicFileAttributes after = attributes(path);
      if (before.size() != encoded.length
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
        throw new IllegalArgumentException("Artifact changed during JSON reading");
      Map<String, Object> value =
          mapper.readValue(encoded, new TypeReference<Map<String, Object>>() {});
      return new JsonDocument(path, value, hex(encoded), encoded.length);
    } catch (IOException error) {
      throw new IllegalArgumentException("Synthetic result JSON is unavailable or invalid", error);
    }
  }

  public FileDescriptor describe(Path path) {
    check(path);
    try (SeekableByteChannel channel = open(path)) {
      BasicFileAttributes before = attributes(path);
      String checksum = digest(channel);
      check(path);
      BasicFileAttributes after = attributes(path);
      if (channel.size() != before.size()
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
        throw new IllegalArgumentException("Artifact changed during checksum calculation");
      return new FileDescriptor(
          root.relativize(path).toString().replace('\\', '/'),
          path.getFileName().toString(),
          before.size(),
          checksum);
    } catch (IOException error) {
      throw new IllegalArgumentException("Artifact could not be read", error);
    }
  }

  public FileDescriptor writeImmutable(Path path, byte[] bytes) {
    check(path);
    String expected = hex(bytes);
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectories(path.getParent());
        check(path);
        Path temporary = Files.createTempFile(path.getParent(), ".result-", ".tmp");
        try {
          Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
          try (var channel =
              java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
          }
          // A hard-link create is atomic and cannot overwrite an existing parser version.
          try {
            Files.createLink(path, temporary);
          } catch (java.nio.file.FileAlreadyExistsException concurrentWriter) {
            /* verify below */
          }
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
      FileDescriptor existing = describe(path);
      if (existing.sizeBytes() != bytes.length || !existing.checksum().equals(expected))
        throw new IllegalArgumentException("Existing derived artifact cannot be overwritten");
      return existing;
    } catch (IOException error) {
      throw new IllegalArgumentException("Derived artifact could not be committed", error);
    }
  }

  public InputStream openVerified(Map<String, Object> artifact) {
    Path path = safe(artifact.get("uri").toString());
    Path temporary = null;
    Path directory = null;
    try (SeekableByteChannel channel = open(path)) {
      long size = ((Number) artifact.get("sizeBytes")).longValue();
      if (channel.size() != size) throw new IllegalArgumentException("Artifact size changed");
      directory = Files.createTempDirectory("cae-download-");
      temporary = Files.createTempFile(directory, "artifact-", ".tmp");
      MessageDigest digest = sha();
      long copied = 0;
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      try (var output = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        while (channel.read(buffer) != -1) {
          buffer.flip();
          copied += buffer.remaining();
          if (copied > size)
            throw new IllegalArgumentException("Artifact grew during download verification");
          digest.update(buffer.asReadOnlyBuffer());
          while (buffer.hasRemaining()) output.write(buffer);
          buffer.clear();
        }
        output.force(true);
      }
      if (copied != size
          || !HexFormat.of().formatHex(digest.digest()).equals(artifact.get("checksum")))
        throw new IllegalArgumentException(
            "Artifact checksum or size no longer matches its metadata");
      check(path);
      Path snapshot = temporary;
      Path privateDirectory = directory;
      InputStream source =
          Files.newInputStream(snapshot, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      temporary = null;
      directory = null;
      // Return the private verified snapshot. An in-place source mutation cannot alter these bytes.
      return new FilterInputStream(source) {
        @Override
        public void close() throws IOException {
          try {
            super.close();
          } finally {
            try {
              Files.deleteIfExists(snapshot);
            } finally {
              Files.deleteIfExists(privateDirectory);
            }
          }
        }
      };
    } catch (RuntimeException | IOException error) {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      if (directory != null)
        try {
          Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
      throw new IllegalArgumentException("Registered artifact is unavailable or changed", error);
    }
  }

  private SeekableByteChannel open(Path path) throws IOException {
    if (!attributes(path).isRegularFile())
      throw new IllegalArgumentException("Artifact must be a regular file");
    return Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
  }

  private static BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static String digest(SeekableByteChannel channel) throws IOException {
    MessageDigest digest = sha();
    ByteBuffer bytes = ByteBuffer.allocate(8192);
    while (channel.read(bytes) != -1) {
      bytes.flip();
      digest.update(bytes);
      bytes.clear();
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String hex(byte[] value) {
    return HexFormat.of().formatHex(sha().digest(value));
  }

  private static MessageDigest sha() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public record JsonDocument(
      Path path, Map<String, Object> value, String checksum, long sizeBytes) {}

  public record FileDescriptor(String uri, String fileName, long sizeBytes, String checksum) {}
}
