package nl.topicus.plugins.maven.cachebuster;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import nl.topicus.plugins.maven.cachebuster.exception.MatchProcessorException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import com.github.rwitzel.streamflyer.core.Modifier;
import com.github.rwitzel.streamflyer.core.ModifyingWriter;
import com.github.rwitzel.streamflyer.regex.AbstractMatchProcessor;
import com.github.rwitzel.streamflyer.regex.MatchProcessorResult;
import com.github.rwitzel.streamflyer.regex.RegexModifier;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.BaseEncoding;

@Mojo(name = "fingerprint", defaultPhase = LifecyclePhase.COMPILE,
		requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class FingerprintMojo extends AbstractMojo
{
	private static final int DEFAULT_BUFFER_SIZE = 4096;

	private static final String CSS_IMG_PATTERN = "(url\\([\"|']?)(.*?)(\\?.*?|#.*?)?([\"|']?\\))";

	private boolean isInitialized = false;

	@Parameter(required = true)
	private File stylesheetSourceDirectory;

	// base directory for css relative url resolving
	@Parameter(required = true)
	private Map<String, String> stylesheetBaseDirectories;

	@Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
	private File outputDirectory;

	private Path stylesheetSourcePath;

	private Map<String, Path> stylesheetBasePaths;

	private Path outputPath;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException
	{
		init();
		executeInternal();
	}

	private void init() throws MojoFailureException
	{
		if (isInitialized)
			return;

		if (!stylesheetSourceDirectory.exists())
		{
			throw new MojoFailureException(
				"Stylesheet source directory '" + stylesheetSourceDirectory + "' does not exist!");
		}

		stylesheetSourcePath =
			FileSystems.getDefault().getPath(stylesheetSourceDirectory.getAbsolutePath());

		stylesheetBasePaths =
			stylesheetBaseDirectories.entrySet().stream().collect(Collectors.toMap(Entry::getKey,
				v -> FileSystems.getDefault().getPath(new File(v.getValue()).getAbsolutePath())));

		outputPath = FileSystems.getDefault().getPath(outputDirectory.getAbsolutePath());

		isInitialized = true;
	}

	private void executeInternal() throws MojoExecutionException, MojoFailureException
	{
		Stopwatch watch = Stopwatch.createStarted();

		Iterator<File> fileIterator = FileUtils.iterateFiles(stylesheetSourceDirectory,
			new SuffixFileFilter(".css"), TrueFileFilter.INSTANCE);
		while (fileIterator.hasNext())
		{
			fingerprintIfChanged(fileIterator.next());
		}

		getLog().info("Complete fingerprint job finished in "
			+ watch.stop().elapsed(TimeUnit.MILLISECONDS) + " ms");
	}

	private void fingerprintIfChanged(File cssFile)
			throws MojoExecutionException, MojoFailureException
	{
		getLog().debug("Source file: " + cssFile.getAbsolutePath());
		Path output = createOutputDirectory(cssFile);
		File outputFile = new File(output.toString(), cssFile.getName());
		File tempOutputFile = new File(output.toString(), cssFile.getName() + ".tmp");
		getLog().debug("Output file: " + outputFile.getAbsolutePath());

		if (!outputFile.exists() || FileUtils.isFileOlder(outputFile, cssFile))
		{
			getLog().info("Fingerprinting source: " + cssFile.getName() + "...");
			if (outputFile.exists() && !outputFile.delete())
			{
				getLog()
					.error("Failed to delete output file '" + outputFile.getAbsolutePath() + "'!");
			}
			char[] buffer = new char[DEFAULT_BUFFER_SIZE];
			try (Reader reader = createReader(cssFile);
					Writer writer = createWriter(cssFile, tempOutputFile))
			{
				int numRead = 0;
				do
				{
					numRead = reader.read(buffer, 0, buffer.length);
					if (numRead > 0)
						writer.write(buffer, 0, numRead);
				}
				while (numRead != -1);
			}
			catch (IOException e)
			{
				tempOutputFile.delete();
				throw new MojoFailureException(
					"I/O exception during fingerprinting " + cssFile.getAbsolutePath() + "!", e);
			}
			if (!tempOutputFile.renameTo(outputFile))
			{
				getLog().error(
					"Failed to rename temporary output file '" + tempOutputFile.getAbsolutePath()
						+ "' to '" + outputFile.getAbsolutePath() + "'!");
			}
		}
		else
		{
			getLog().info("Bypassing source: " + cssFile.getName() + " (not modified)");
		}
	}

	private Path createOutputDirectory(File cssFile) throws MojoExecutionException
	{
		Path output = Paths.get(cssFile.getAbsolutePath()).getParent();
		Path relativeParentPath = stylesheetSourcePath.relativize(output);
		Path fullOutputPath = relativeParentPath != null
			? Paths.get(outputPath.toString(), relativeParentPath.toString()) : outputPath;
		if (!fullOutputPath.toFile().exists() && !fullOutputPath.toFile().mkdirs())
		{
			throw new MojoExecutionException(
				"Cannot create output directory " + fullOutputPath.toString());
		}
		return fullOutputPath;
	}

	private Reader createReader(File file) throws MojoFailureException
	{
		try
		{
			return new BufferedReader(
				new InputStreamReader(new FileInputStream(file), Charsets.UTF_8));
		}
		catch (FileNotFoundException e)
		{
			throw new MojoFailureException("File " + file.getAbsolutePath() + " not found!", e);
		}
	}

	private Writer createWriter(File cssFile, File outputFile) throws MojoFailureException
	{
		try
		{
			return new ModifyingWriter(
				new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile))),
				createModifier(cssFile));
		}
		catch (FileNotFoundException e)
		{
			throw new MojoFailureException(
				"File " + outputFile.getAbsolutePath() + " cannot be opened for writing!", e);
		}
	}

	private Modifier createModifier(File cssFile)
	{
		return new RegexModifier(CSS_IMG_PATTERN, Pattern.MULTILINE, new RegexProcessor(cssFile), 0,
			2048);
	}

	class RegexProcessor extends AbstractMatchProcessor
	{
		private static final String DEFAULT_DIGEST_ALGORITHM = "MD5";

		private static final String DEFAULT_VERSION_PREFIX = "-ver-";

		private Path sharedPath;

		private Path path;

		public RegexProcessor(File cssFile)
		{
			this.sharedPath = stylesheetBasePaths.get("shared");
			this.path = stylesheetBasePaths.get(cssFile.getName());
		}

		@Override
		public MatchProcessorResult process(StringBuilder characterBuffer,
				int firstModifiableCharacterInBuffer, MatchResult matchResult)
		{
			int start = matchResult.start();
			int end = matchResult.end();

			Path imgFile = null;
			if (matchResult.group(2).startsWith("data:"))
			{
				return new MatchProcessorResult(matchResult.end(), true);
			}
			else if (matchResult.group(2).startsWith("../"))
			{
				imgFile = path == null ? null
					: path.resolve(matchResult.group(2).substring(3)).normalize();
				if (sharedPath != null && (imgFile == null || !imgFile.toFile().exists()))
				{
					imgFile = sharedPath.resolve(matchResult.group(2).substring(3)).normalize();
				}
			}
			else
			{
				imgFile = FileSystems.getDefault()
					.getPath(stylesheetSourceDirectory.getAbsolutePath(), matchResult.group(2));
			}

			if (imgFile == null || !imgFile.toFile().exists())
			{
				throw new RuntimeException(
					"File '" + (imgFile != null ? imgFile.toFile().getAbsolutePath() : "<unknown>")
						+ "' does not exist!");
			}

			byte[] buffer = new byte[1024];
			int numRead = 0;
			MessageDigest md = getMD5MessageDigest();
			try (InputStream is = Files.newInputStream(imgFile))
			{
				DigestInputStream dis = new DigestInputStream(is, md);

				do
				{
					numRead = dis.read(buffer, 0, buffer.length);
				}
				while (numRead != -1);

			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			byte[] digest = md.digest();
			String md5Checksum = BaseEncoding.base16().encode(digest);

			String fingerprintedFilename = getVersionedFileName(matchResult.group(2), md5Checksum);

			StringBuilder replacement = new StringBuilder();
			replacement.append(matchResult.group(1)).append(fingerprintedFilename);
			if (matchResult.group(3) != null)
				replacement.append(matchResult.group(3));
			replacement.append(matchResult.group(4));

			characterBuffer.delete(start, end);
			characterBuffer.insert(start, replacement);

			return createResult(matchResult, start + replacement.length(), true);

			// continue matching behind the end of the matched text
			// return new MatchProcessorResult(matchResult.end(), true);
		}

		private MessageDigest getMD5MessageDigest()
		{
			try
			{
				return MessageDigest.getInstance(DEFAULT_DIGEST_ALGORITHM);
			}
			catch (NoSuchAlgorithmException e)
			{
				throw new MatchProcessorException("Digest algorithm MD5 not available!", e);
			}
		}

		private String getVersionedFileName(String fileName, String md5Checksum)
		{
			String imgPath = FilenameUtils.getFullPath(fileName);
			String imgBaseName = FilenameUtils.getBaseName(fileName);
			String imgExtension = FilenameUtils.getExtension(fileName);
			String fingerprintedFilename = String.format("%s%s%s%s.%s", imgPath, imgBaseName,
				DEFAULT_VERSION_PREFIX, md5Checksum, imgExtension);
			getLog().debug("Fingerprinted: " + fingerprintedFilename);
			return fingerprintedFilename;
		}
	}
}
