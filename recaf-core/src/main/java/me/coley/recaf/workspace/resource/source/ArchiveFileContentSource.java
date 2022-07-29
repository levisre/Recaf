package me.coley.recaf.workspace.resource.source;

import me.coley.recaf.io.ByteSource;
import me.coley.recaf.io.ByteSourceConsumer;
import me.coley.recaf.io.ByteSourceElement;
import me.coley.recaf.io.ByteSources;
import me.coley.recaf.util.ReflectUtil;
import software.coley.llzip.ZipArchive;
import software.coley.llzip.ZipCompressions;
import software.coley.llzip.ZipIO;
import software.coley.llzip.part.LocalFileHeader;
import software.coley.llzip.util.ByteData;
import software.coley.llzip.util.ByteDataUtil;
import software.coley.llzip.util.FileMapUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Origin location information of archive files.
 *
 * @author Matt Coley
 */
public abstract class ArchiveFileContentSource extends ContainerContentSource<LocalFileHeader> {
	protected ArchiveFileContentSource(SourceType type, Path path) {
		super(type, path);
	}

	@Override
	protected void consumeEach(ByteSourceConsumer<LocalFileHeader> entryHandler) throws IOException {
		try (Stream<ByteSourceElement<LocalFileHeader>> s = stream()) {
			s.forEach(ByteSources.consume(entryHandler));
		}
	}

	@Override
	protected Stream<ByteSourceElement<LocalFileHeader>> stream() throws IOException {
		ByteData data = FileMapUtil.map(getPath());
		ZipArchive archive;
		try {
			archive = ZipIO.readJvm(data);
		} catch (Exception ex) {
			data.close();
			throw ex;
		}
		return archive.getLocalFiles().stream()
				.map(x -> new ByteSourceElement<>(x, new LocalFileHeaderSource(x)))
				.onClose(() -> {
					try {
						data.close();
					} catch(IOException ex) {
						ReflectUtil.propagate(ex);
					}
				});
	}

	@Override
	protected boolean isClass(LocalFileHeader fileHeader, ByteSource content) throws IOException {
		// If the fileHeader name does not have the "CAFEBABE" magic header, its not a class.
		return matchesClass(content.peek(17));
	}

	@Override
	protected boolean isClass(LocalFileHeader entry, byte[] content) throws IOException {
		// If the fileHeader name does not have the "CAFEBABE" magic header, its not a class.
		return matchesClass(content);
	}

	@Override
	protected String getPathName(LocalFileHeader entry) {
		return entry.getLinkedDirectoryFileHeader().getFileNameAsString();
	}

	private static final class LocalFileHeaderSource implements ByteSource {
		private final LocalFileHeader fileHeader;
		private ByteData decompressed;

		LocalFileHeaderSource(LocalFileHeader fileHeader) {
			this.fileHeader = fileHeader;
		}

		@Override
		public byte[] readAll() throws IOException {
			return ByteDataUtil.toByteArray(decompress());
		}

		@Override
		public byte[] peek(int count) throws IOException {
			ByteData data = decompress();
			long length = data.length();
			if (length < count)
				count = (int) length;
			byte[] bytes = new byte[count];
			data.get(0L, bytes, 0, count);
			return bytes;
		}

		@Override
		public InputStream openStream() throws IOException {
			// Delegate to byte source
			return ByteSources.forZip(decompress()).openStream();
		}

		private ByteData decompress() throws IOException {
			ByteData decompressed = this.decompressed;
			if (decompressed == null) {
				return this.decompressed = ZipCompressions.decompress(fileHeader);
			}
			return decompressed;
		}
	}
}
