package org.openntf.maven.nsfodp.jvm;

import static java.text.MessageFormat.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.lang3.SystemUtils;

import com.ibm.commons.util.StringUtil;
import com.ibm.commons.util.io.json.JsonException;
import com.ibm.commons.util.io.json.JsonJavaFactory;
import com.ibm.commons.util.io.json.JsonParser;

public class MacOSJVMProvider {
	public static final String API_RELEASES = "https://api.github.com/repos/ibmruntimes/semeru{0}-binaries/releases?per_page=100"; //$NON-NLS-1$
	public static final String PROVIDER_NAME = "IBM Semeru"; //$NON-NLS-1$
	public static final String JAVA_VERSION = "8"; //$NON-NLS-1$
	
	private static final Logger log = Logger.getLogger(MacOSJVMProvider.class.getName());
	
	@SuppressWarnings("unchecked")
	public static Path getJavaHome() {
		Path userHome = SystemUtils.getUserHome().toPath();
		Path jvmDir = userHome.resolve(".nsfodp").resolve("jvm"); //$NON-NLS-1$ //$NON-NLS-2$
		if(!Files.isDirectory(jvmDir)) {
			String releasesUrl = format(API_RELEASES, JAVA_VERSION);
			List<Map<String, Object>> releases = fetchGitHubReleasesList(PROVIDER_NAME, releasesUrl);
			
			// Find any applicable releases, in order, as some releases may contain only certain platforms
			List<Map<String, Object>> validReleases = releases.stream()
				.filter(release -> !(Boolean)release.get("prerelease")) //$NON-NLS-1$
				.filter(release -> !(Boolean)release.get("draft")) //$NON-NLS-1$
				.filter(release -> release.containsKey("assets")) //$NON-NLS-1$
				.collect(Collectors.toList());
			if(validReleases.isEmpty()) {
				throw new IllegalStateException(format("Unable to locate JDK build for {0}, releases URL {1}", PROVIDER_NAME, releasesUrl)); //$NON-NLS-1$
			}
			
			String qualifier = format("jdk_{0}_{1}", getOsArch(), getOsName()); //$NON-NLS-1$
			Map<String, Object> download = validReleases.stream()
				.map(release -> (List<Map<String, Object>>)release.get("assets")) //$NON-NLS-1$
				.flatMap(Collection::stream)
				.filter(asset -> !StringUtil.toString(asset.get("name")).contains("-testimage")) //$NON-NLS-1$ //$NON-NLS-2$
				.filter(asset -> !StringUtil.toString(asset.get("name")).contains("-debugimage")) //$NON-NLS-1$ //$NON-NLS-2$
				.filter(asset -> StringUtil.toString(asset.get("name")).contains("-" + qualifier + "_")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.filter(asset -> "application/x-compressed-tar".equals(asset.get("content_type")) || "application/zip".equals(asset.get("content_type"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(format("Unable to find {0} build for {1}", PROVIDER_NAME, qualifier))); //$NON-NLS-1$
			if(log.isLoggable(Level.INFO)) {
				log.info(format("Downloading {0} JDK from {1}", PROVIDER_NAME, download.get("browser_download_url")));  //$NON-NLS-1$//$NON-NLS-2$
			}
			
			String contentType = (String)download.get("content_type"); //$NON-NLS-1$
			download((String)download.get("browser_download_url"), contentType, jvmDir); //$NON-NLS-1$
			
			markExecutables(jvmDir);
		}
		return jvmDir.resolve("Contents").resolve("Home"); //$NON-NLS-1$ //$NON-NLS-2$
	}
	
	@SuppressWarnings("unchecked")
	protected static List<Map<String, Object>> fetchGitHubReleasesList(String providerName, String releasesUrl) {
		try {
			return download(new URL(releasesUrl), is -> {
				try(Reader r = new InputStreamReader(is)) {
					return (List<Map<String, Object>>)JsonParser.fromJson(JsonJavaFactory.instance, r);
				} catch (JsonException e) {
					throw new RuntimeException(e);
				}
			});
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected static void download(String url, String contentType, Path jvmDir) {
		// TODO consider replacing with NIO filesystem operations, though they don't inherently support .tar.gz
		try {
			download(new URL(url), is -> {
				if("application/zip".equals(contentType)) { //$NON-NLS-1$
					try(ZipInputStream zis = new ZipInputStream(is)) {
						extract(zis, jvmDir);
					}
				} else if("application/x-compressed-tar".equals(contentType)) { //$NON-NLS-1$
					try(GZIPInputStream gzis = new GZIPInputStream(is)) {
						try(TarArchiveInputStream tis = new TarArchiveInputStream(gzis)) {
							extract(tis, jvmDir);
						}
					}
				} else {
					throw new IllegalStateException(format("Unsupported content type: {0}", contentType));
				}
				return null;
			});
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	protected static void markExecutables(Path jvmDir) {
		Path bin = jvmDir.resolve("bin"); //$NON-NLS-1$
		if(Files.isDirectory(bin)) {
			markExecutablesInBinDir(bin);
		}
		Path jreBin = jvmDir.resolve("jre").resolve("bin"); //$NON-NLS-1$ //$NON-NLS-2$
		if(Files.isDirectory(jreBin)) {
			markExecutablesInBinDir(jreBin);
		}
	}
	
	protected static void markExecutablesInBinDir(Path bin) {
		if(bin.getFileSystem().supportedFileAttributeViews().contains("posix")) { //$NON-NLS-1$
			try {
				Files.list(bin)
					.filter(Files::isRegularFile)
					.forEach(p -> {
						try {
							Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(p));
							perms.add(PosixFilePermission.OWNER_EXECUTE);
							perms.add(PosixFilePermission.GROUP_EXECUTE);
							perms.add(PosixFilePermission.OTHERS_EXECUTE);
							Files.setPosixFilePermissions(p, perms);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
	
	protected static void extract(ZipInputStream zis, Path dest) throws IOException {
		ZipEntry entry = zis.getNextEntry();
		while(entry != null) {
			String name = entry.getName();
			
			if(StringUtil.isNotEmpty(name)) {
				// The first directory is a container
				int slashIndex = name.indexOf('/');
				if(slashIndex > -1) {
					name = name.substring(slashIndex+1);
				}
				
				if(StringUtil.isNotEmpty(name)) {
					if(log.isLoggable(Level.FINER)) {
						log.finer(format("Deploying file {0}", name));
					}
					
					Path path = dest.resolve(name);
					if(entry.isDirectory()) {
						Files.createDirectories(path);
					} else {
						Files.createDirectories(path.getParent());
						Files.copy(zis, path, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			
			zis.closeEntry();
			entry = zis.getNextEntry();
		}
	}
	
	protected static void extract(TarArchiveInputStream tis, Path dest) throws IOException {
		TarArchiveEntry entry = tis.getNextTarEntry();
		while(entry != null) {
			String name = entry.getName();

			if(StringUtil.isNotEmpty(name)) {
				// The first directory is a container
				int slashIndex = name.indexOf('/');
				if(slashIndex > -1) {
					name = name.substring(slashIndex+1);
				}
				
				if(StringUtil.isNotEmpty(name)) {
					if(log.isLoggable(Level.FINER)) {
						log.finer(format("Deploying file {0}", name));
					}
					
					Path path = dest.resolve(name);
					if(entry.isDirectory()) {
						Files.createDirectories(path);
					} else {
						Files.createDirectories(path.getParent());
						Files.copy(tis, path, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			
			entry = tis.getNextTarEntry();
		}
	}
	
	@FunctionalInterface
	public static interface IOFunction<T> {
		T apply(InputStream is) throws IOException;
	}
	
	/**
	 * @param <T> the expected return type
	 * @param url the URL to fetch
	 * @param consumer a handler for the download's {@link InputStream}
	 * @return the consumed value
	 * @throws IOException if there is an unexpected problem downloading the file or if the server
	 * 		returns any code other than {@link HttpURLConnection#HTTP_OK}
	 * @since 2.0.0
	 */
	public static <T> T download(URL url, IOFunction<T> consumer) throws IOException {
		// Domino defaults to using old protocols - bump this up for our needs here so the connection succeeds
		String protocols = StringUtil.toString(System.getProperty("https.protocols")); //$NON-NLS-1$
		try {
			System.setProperty("https.protocols", "TLSv1.2"); //$NON-NLS-1$ //$NON-NLS-2$
			HttpURLConnection conn = (HttpURLConnection)url.openConnection();
			int responseCode = conn.getResponseCode();
			try {
				if(responseCode != HttpURLConnection.HTTP_OK) {
					throw new IOException(format("Unexpected response code {0} from URL {1}", responseCode, url));
				}
				try(InputStream is = conn.getInputStream()) {
					return consumer.apply(is);
				}
			} finally {
				conn.disconnect();
			}
		} finally {
			System.setProperty("https.protocols", protocols); //$NON-NLS-1$
		}
	}
	
	private static String getOsArch() {
		return "x64";
	}
	
	private static String getOsName() {
		return "mac";
	}
}
