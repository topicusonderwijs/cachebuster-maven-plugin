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
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.BaseEncoding;
import com.googlecode.streamflyer.core.Modifier;
import com.googlecode.streamflyer.core.ModifyingWriter;
import com.googlecode.streamflyer.regex.AbstractMatchProcessor;
import com.googlecode.streamflyer.regex.MatchProcessorResult;
import com.googlecode.streamflyer.regex.RegexModifier;

@Mojo(name = "fingerprint", defaultPhase = LifecyclePhase.COMPILE,
		requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class FingerprintMojo extends AbstractMojo
{
	private static final int DEFAULT_BUFFER_SIZE = 4096;

	private static final String CSS_IMG_PATTERN = "(url\\([\"|']?)(.*?)(\\?.*?|#.*?)?([\"|']?\\))";

	// @Component
	// private BuildContext buildContext;

	// @Parameter(defaultValue = "${project}", property = "project", required =
	// true, readonly = true)
	// private MavenProject project;

	private boolean isInitialized = false;

	@Parameter(required = true, defaultValue = "${basedir}/src/main/webapp")
	private File stylesheetSourceDirectory;

	// base directory for css relative url resolving
	@Parameter(required = true, defaultValue = "${basedir}/src/main/webapp")
	private File stylesheetBaseDirectory;

	@Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
	private File outputDirectory;

	private Path stylesheetSourcePath;

	private Path stylesheetBasePath;

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
			throw new MojoFailureException("Stylesheet source directory '"
				+ stylesheetSourceDirectory + "' does not exist!");
		}

		stylesheetSourcePath =
			FileSystems.getDefault().getPath(stylesheetSourceDirectory.getAbsolutePath());

		stylesheetBasePath =
			FileSystems.getDefault().getPath(stylesheetBaseDirectory.getAbsolutePath());

		outputPath = FileSystems.getDefault().getPath(outputDirectory.getAbsolutePath());

		isInitialized = true;
	}

	private void executeInternal() throws MojoExecutionException, MojoFailureException
	{
		Stopwatch watch = Stopwatch.createStarted();

		Iterator<File> fileIterator =
			FileUtils.iterateFiles(stylesheetSourceDirectory, new SuffixFileFilter(".css"),
				TrueFileFilter.INSTANCE);
		while (fileIterator.hasNext())
		{
			fingerprintIfChanged(fileIterator.next());
		}

		getLog().info(
			"Complete fingerprint job finished in " + watch.stop().elapsed(TimeUnit.MILLISECONDS)
				+ " ms");
	}

	private void fingerprintIfChanged(File file) throws MojoExecutionException,
			MojoFailureException
	{
		getLog().debug("Source file: " + file.getAbsolutePath());
		Path output = createOutputDirectory(file);
		File outputFile = new File(output.toString(), file.getName());
		getLog().debug("Output ifle: " + outputFile.getAbsolutePath());

		if (!outputFile.exists() || FileUtils.isFileOlder(outputFile, file))
		{
			getLog().info("Fingerprinting source: " + file.getName() + "...");
			char[] buffer = new char[DEFAULT_BUFFER_SIZE];
			try (Reader reader = createReader(file); Writer writer = createWriter(outputFile))
			{
				int numRead = 0;
				do
				{
					numRead = reader.read(buffer, 0, buffer.length);
					if (numRead > 0)
						writer.write(buffer, 0, numRead);
				}
				while (numRead != -1);
				outputFile.setLastModified(file.lastModified());
			}
			catch (IOException e)
			{
				throw new MojoFailureException("I/O exception during fingerprinting "
					+ file.getAbsolutePath() + "!", e);
			}
		}
		else
		{
			getLog().info("Bypassing source: " + file.getName() + " (not modified)");
		}
	}

	private Path createOutputDirectory(File file) throws MojoExecutionException
	{
		Path output = Paths.get(file.getAbsolutePath()).getParent();
		Path relativeParentPath = stylesheetSourcePath.relativize(output);
		Path fullOutputPath =
			relativeParentPath != null ? Paths.get(outputPath.toString(),
				relativeParentPath.toString()) : outputPath;
		if (!fullOutputPath.toFile().exists() && !fullOutputPath.toFile().mkdirs())
		{
			throw new MojoExecutionException("Cannot create output directory "
				+ fullOutputPath.toString());
		}
		return fullOutputPath;
	}

	private Reader createReader(File file) throws MojoFailureException
	{
		try
		{
			return new BufferedReader(new InputStreamReader(new FileInputStream(file),
				Charsets.UTF_8));
		}
		catch (FileNotFoundException e)
		{
			throw new MojoFailureException("File " + file.getAbsolutePath() + " not found!", e);
		}
	}

	private Writer createWriter(File outputFile) throws MojoFailureException
	{
		try
		{
			return new ModifyingWriter(new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(outputFile))), createModifier());
		}
		catch (FileNotFoundException e)
		{
			throw new MojoFailureException("File " + outputFile.getAbsolutePath()
				+ " cannot be opened for writing!", e);
		}
	}

	private Modifier createModifier()
	{
		return new RegexModifier(CSS_IMG_PATTERN, Pattern.MULTILINE, new RegexProcessor(), 0, 2048);
	}

	class RegexProcessor extends AbstractMatchProcessor
	{
		private static final String DEFAULT_DIGEST_ALGORITHM = "MD5";

		private static final String DEFAULT_VERSION_PREFIX = "-ver-";

		@Override
		public MatchProcessorResult process(StringBuilder characterBuffer,
				int firstModifiableCharacterInBuffer, MatchResult matchResult)
		{
			int start = matchResult.start();
			int end = matchResult.end();

			Path imgFile = null;
			if (matchResult.group(2).startsWith("../"))
			{
				imgFile = stylesheetBasePath.resolve(matchResult.group(2)).normalize();
			}
			else
			{
				imgFile =
					FileSystems.getDefault().getPath(stylesheetSourceDirectory.getAbsolutePath(),
						matchResult.group(2));
			}

			if (!imgFile.toFile().exists())
			{
				throw new RuntimeException("File '" + imgFile.toFile().getAbsolutePath()
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
			String fingerprintedFilename =
				String.format("%s%s%s%s.%s", imgPath, imgBaseName, DEFAULT_VERSION_PREFIX,
					md5Checksum, imgExtension);
			getLog().debug("Fingerprinted: " + fingerprintedFilename);
			return fingerprintedFilename;
		}
	}
}
