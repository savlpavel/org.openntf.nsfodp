/*
 * © Copyright Jesse Gallagher 2018
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at:
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
 * implied. See the License for the specific language governing 
 * permissions and limitations under the License.
 */
package org.openntf.xsp.extlibx.bazaar.odpcompiler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.AbstractSplitDesignElement;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.CustomControl;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.JavaSource;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.LotusScriptLibrary;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.OnDiskProject;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.XPage;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.odp.XSPCompilationResult;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.update.UpdateSite;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.util.DXLUtil;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.util.MultiPathResourceBundleSource;
import org.openntf.xsp.extlibx.bazaar.odpcompiler.util.ODPUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.xsp.library.FacesClassLoader;
import com.ibm.xsp.library.LibraryServiceLoader;
import com.ibm.xsp.library.LibraryWrapper;
import com.ibm.xsp.library.XspLibrary;
import com.ibm.xsp.page.FacesPageException;
import com.ibm.xsp.registry.CompositeComponentDefinitionImpl;
import com.ibm.xsp.registry.FacesLibraryImpl;
import com.ibm.xsp.registry.FacesProject;
import com.ibm.xsp.registry.FacesProjectImpl;
import com.ibm.xsp.registry.LibraryFragmentImpl;
import com.ibm.xsp.registry.SharableRegistryImpl;
import com.ibm.xsp.registry.UpdatableLibrary;
import com.ibm.xsp.registry.config.IconUrlSource;
import com.ibm.xsp.registry.config.ResourceBundleSource;
import com.ibm.xsp.registry.config.SimpleRegistryProvider;
import com.ibm.xsp.registry.config.XspRegistryProvider;
import com.ibm.xsp.registry.parse.ConfigParser;
import com.ibm.xsp.registry.parse.ConfigParserFactory;
import com.mindoo.domino.jna.NotesNote;
import com.mindoo.domino.jna.errors.LotusScriptCompilationError;
import com.mindoo.domino.jna.errors.NotesError;
import com.mindoo.domino.jna.gc.NotesGC;
import com.mindoo.domino.jna.utils.LegacyAPIUtils;

import lotus.domino.Database;
import lotus.domino.DxlImporter;
import lotus.domino.NotesException;
import lotus.domino.NotesFactory;

import com.ibm.commons.extension.ExtensionManager;
import com.ibm.commons.util.StringUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;
import com.ibm.designer.domino.napi.NotesAPIException;
import com.ibm.designer.runtime.domino.bootstrap.util.StreamUtil;
import com.ibm.xsp.extlib.interpreter.DynamicFacesClassLoader;
import com.ibm.xsp.extlib.interpreter.DynamicXPageBean;
import com.ibm.xsp.extlib.javacompiler.JavaCompilerException;
import com.ibm.xsp.extlib.javacompiler.JavaSourceClassLoader;

/**
 * Represents an on-disk project compilation environment.
 * 
 * <p>This class is the primary entry point for ODP compilation.</p>
 * 
 * @author Jesse Gallagher
 * @since 2.0.0
 */
public class ODPCompiler {
	private final BundleContext bundleContext;
	private final OnDiskProject odp;
	private final Set<UpdateSite> updateSites = new LinkedHashSet<>();
	private List<String> compilerOptions = DEFAULT_COMPILER_OPTIONS;
	private final PrintStream out;
	
	// XSP compiler components
	private final SharableRegistryImpl facesRegistry = new SharableRegistryImpl(getClass().getPackage().getName());
	private final FacesProject facesProject;
	private final DynamicXPageBean dynamicXPageBean = new DynamicXPageBean();
	private final ResourceBundleSource resourceBundleSource;
	private final IconUrlSource iconUrlSource = new IconUrlSource() {
		@Override public URL getIconUrl(String arg0) {
			// TODO ???
			return null;
		}
	};
	
	private static final List<String> DEFAULT_COMPILER_OPTIONS = Arrays.asList(
			"-source", "1.8",
			"-target", "1.8",
			"-g",
			"-parameters",
			"-encoding", "utf-8"
			);
	
	public ODPCompiler(BundleContext bundleContext, OnDiskProject onDiskProject, PrintStream out) throws FileNotFoundException, XMLException, IOException {
		this.bundleContext = Objects.requireNonNull(bundleContext);
		this.odp = Objects.requireNonNull(onDiskProject);
		this.out = out;
		this.facesProject = new FacesProjectImpl(getClass().getPackage().getName(), facesRegistry);
		this.resourceBundleSource = new MultiPathResourceBundleSource(odp.getResourcePaths());
	}
	
	public OnDiskProject getOnDiskProject() {
		return odp;
	}
	
	public void addUpdateSite(UpdateSite updateSite) {
		this.updateSites.add(updateSite);
	}
	
	public void setCompilerOptions(Collection<String> compilerOptions) {
		if(compilerOptions == null) {
			this.compilerOptions = DEFAULT_COMPILER_OPTIONS;
		} else {
			this.compilerOptions = new ArrayList<>(compilerOptions);
		}
	}
	
	/**
	 * Runs the compilation process:
	 * 
	 * <ol>
	 * 	<li>Installs all bundles from the provided update sites</li>
	 *	<li>Initializes plugin contributions from installed bundles</li>
	 * 	<li>Compiles all XPage artifacts</li>
	 * 	<li>Constructs the NSF from the on-disk project</li>
	 * 	<li>Uninstalls any installed bundles</li>
	 * </ol>
	 * 
	 * @return a {@link Path} representing the created database
	 * @throws Exception if there is a problem compiling any component
	 */
	public synchronized Path compile() throws Exception {
		Collection<Bundle> bundles = installBundles();
		try {
			initRegistry();

			// Compile Java classes
			Collection<String> dependencies = ODPUtil.expandRequiredBundles(bundleContext, odp.getRequiredBundles());
			String[] classPath = dependencies.toArray(new String[dependencies.size()]);
			JavaSourceClassLoader classLoader = new JavaSourceClassLoader(getClass().getClassLoader(), compilerOptions, classPath);
			
			compileJavaSources(classLoader);
			compileCustomControls(classLoader);
			compileXPages(classLoader);
			
			lotus.domino.Session lotusSession = NotesFactory.createSession();
			try {
				Path file = createDatabase(lotusSession);
				Database database = lotusSession.getDatabase("", file.toAbsolutePath().toString());
				DxlImporter importer = lotusSession.createDxlImporter();
				importer.setDesignImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);
				importer.setAclImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE);
				importer.setReplaceDbProperties(true);
				importer.setReplicaRequiredForReplaceOrUpdate(false);
				
				importBasicElements(importer, database);
				importFileResources(importer, database);
				importLotusScriptLibraries(importer, database);
				
				Set<String> compiledClassNames = new HashSet<>(classLoader.getCompiledClassNames());
				importCustomControls(importer, database, classLoader, compiledClassNames);
				importXPages(importer, database, classLoader, compiledClassNames);
				importJavaElements(importer, database, classLoader, compiledClassNames);
				
				
				return file;
			} finally {
				lotusSession.recycle();
			}
		} finally {
			uninstallBundles(bundles);
		}
	}
	
	// *******************************************************************************
	// * Bundle manipulation methods
	// *******************************************************************************
	private Collection<Bundle> installBundles() {
		debug("Installing bundles");
		
		Collection<Bundle> result = updateSites.stream()
			.map(UpdateSite::getBundleURIs)
			.flatMap(Collection::stream)
			.map(this::installBundle)
			.collect(Collectors.toList()).stream() // Force waiting until installation is complete
			.map(this::startBundle)
			.collect(Collectors.toList());
		debug(MessageFormat.format("- Installed {0,choice,0#no bundles|1# 1 bundle|1<{0} bundles}", result.size()));
		return result;
	}
	
	private void uninstallBundles(Collection<Bundle> bundles) {
		debug("Uninstalling bundles");
		
		bundles.stream().forEach(t -> {
			try {
				t.uninstall();
			} catch (BundleException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	/**
	 * Initializes the internal Faces registry with the newly-added plugins.
	 */
	private void initRegistry() {
		debug("Initializing libraries");

		List<Object> libraries = ExtensionManager.findServices((List<Object>)null, LibraryServiceLoader.class, "com.ibm.xsp.Library");
		libraries.stream()
			.filter(lib -> lib instanceof XspLibrary)
			.map(XspLibrary.class::cast)
			.map(lib -> new LibraryWrapper(lib.getLibraryId(), lib))
			.map(wrapper -> {
				SimpleRegistryProvider provider = new SimpleRegistryProvider();
				provider.init(wrapper);
				return provider;
			})
			.map(XspRegistryProvider::getRegistry)
			.forEach(facesRegistry::addDepend);
		facesRegistry.refreshReferences();
	}
	
	/**
	 * Installs the provided bundle.
	 * 
	 * @param uri the platform-accessible URI to the bundle
	 * @return the installed {@link Bundle} object
	 */
	private Bundle installBundle(URI uri) {
		Bundle bundle = null;
		try {
			BundleContext bundleContext = ODPCompilerActivator.instance.getBundle().getBundleContext();
			bundle = bundleContext.installBundle(uri.toString());
		} catch(Exception e) {
			// Ignore
		}
		return bundle;
	}
	private Bundle startBundle(Bundle bundle) {
		try {
			if(bundle.getHeaders().get("Eclipse-SourceBundle") == null && bundle.getHeaders().get("Fragment-Host") == null) {
				bundle.start();
			}
		} catch (BundleException e) {
			e.printStackTrace();
			// Ignore
		}
		return bundle;
	}
	
	// *******************************************************************************
	// * Class compilation methods
	// *******************************************************************************
	
	private Map<String, Class<?>> compileJavaSources(JavaSourceClassLoader classLoader) throws FileNotFoundException, XMLException, IOException, JavaCompilerException {
		debug("Compiling Java source");
		
		Map<String, CharSequence> sources = odp.getJavaSourceFiles().entrySet().stream()
			.map(entry ->
				// Convert to a map of class name -> source
				entry.getValue().stream()
					.collect(Collectors.toMap(
						source -> ODPUtil.toJavaClassName(entry.getKey().relativize(source.getDataFile())),
						JavaSource::getSource
					))
			)
			.map(Map::entrySet)
			.flatMap(Set::stream)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		
		debug(MessageFormat.format("- Compiling {0,choice,0#no classes|1# 1 class|1<{0} classes}", sources.size()));
		return classLoader.addClasses(sources);
	}
	
	// *******************************************************************************
	// * XSP compilation methods
	// *******************************************************************************
	
	private Map<CustomControl, XSPCompilationResult> compileCustomControls(JavaSourceClassLoader classLoader) throws Exception {
		debug("Compiling custom controls");
		
		ConfigParser configParser = ConfigParserFactory.getParserInstance();
		FacesClassLoader facesClassLoader = new DynamicFacesClassLoader(dynamicXPageBean, classLoader);
		
		Map<CustomControl, XSPCompilationResult> result = new LinkedHashMap<>();
		
		List<CustomControl> ccs = odp.getCustomControls();
		for(CustomControl cc : ccs) {
			Document xspConfig = cc.getXspConfig().get();
			
			String namespace = StringUtil.trim(DOMUtil.evaluateXPath(xspConfig, "/faces-config/faces-config-extension/namespace-uri/text()").getStringValue());
			Path fileName = odp.getBaseDirectory().relativize(cc.getXspConfigFile());
			LibraryFragmentImpl fragment = (LibraryFragmentImpl)configParser.createFacesLibraryFragment(
					facesProject,
					facesClassLoader,
					fileName.toString(),
					xspConfig.getDocumentElement(),
					resourceBundleSource,
					iconUrlSource,
					namespace
			);
			
			UpdatableLibrary library = getLibrary(namespace);
			library.addLibraryFragment(fragment);
			
			// Load the definition to refresh its parent ref
			CompositeComponentDefinitionImpl def = (CompositeComponentDefinitionImpl)library.getDefinition(cc.getControlName());
			def.refreshReferences();
		}
		
		// Now that they're all defined, try to compile them in a queue
		for(CustomControl cc : ccs) {
			XSPCompilationResult compilationResult = compileXSP(cc, classLoader);
			result.put(cc, compilationResult);
		}
		
		return result;
	}
	
	private Map<XPage, XSPCompilationResult> compileXPages(JavaSourceClassLoader classLoader) throws Exception {
		debug("Compiling XPages");
		Map<XPage, XSPCompilationResult> result = new LinkedHashMap<>();
		
		for(XPage xpage : odp.getXPages()) {
			XSPCompilationResult compilationResult = compileXSP(xpage, classLoader);
			result.put(xpage, compilationResult);
		}
		
		return result;
	}
	
	// *******************************************************************************
	// * NSF manipulation methods
	// *******************************************************************************
	
	/**
	 * Creates a new, non-replica copy of the stub blank database for population
	 * in the local temp directory.
	 * 
	 * @return a {@link Path} representing the new NSF file
	 * @throws IOException if there is a problem creating the file
	 * @throws NotesException if there is an API-level problem creating the copy
	 */
	private Path createDatabase(lotus.domino.Session lotusSession) throws IOException, NotesException {
		debug("Creating destination NSF");
		Path temp = Files.createTempFile("odpcompilertemp", ".nsf");
		temp.toFile().deleteOnExit();
		
		try(OutputStream os = Files.newOutputStream(temp)) {
			try(InputStream is = getClass().getResourceAsStream("/res/blank.nsf")) {
				StreamUtil.copyStream(is, os);
			}
		}
		
		Path nsf = Files.createTempFile("odpcompiler", ".nsf");
		Files.delete(nsf);
		lotus.domino.Database tempDatabase = lotusSession.getDatabase("", temp.toAbsolutePath().toString());
		tempDatabase.createCopy("", nsf.toAbsolutePath().toString());
		tempDatabase.remove();
		
		return nsf;
	}
	
	private void importBasicElements(DxlImporter importer, Database database) throws NotesException, IOException {
		debug("Importing basic design elements");
		for(Map.Entry<Path, String> entry : odp.getDirectDXLElements().entrySet()) {
			if(StringUtil.isNotEmpty(entry.getValue())) {
				try {
					importDxl(importer, entry.getValue(), database, "Basic element " + odp.getBaseDirectory().relativize(entry.getKey()));
				} catch(NotesException ne) {
					throw new NotesException(ne.id, "Exception while importing element " + odp.getBaseDirectory().relativize(entry.getKey()), ne);
				}
			}
		}
	}
	
	private void importFileResources(DxlImporter importer, Database database) throws NotesException, XMLException, IOException {
		debug("Importing file resources");
		for(AbstractSplitDesignElement res : odp.getFileResources()) {
			Document dxlDoc = res.getDxl();
			Path filePath = odp.getBaseDirectory().relativize(res.getDataFile());
			importDxl(importer, DOMUtil.getXMLString(dxlDoc), database, res.getClass().getSimpleName() + " " + filePath);
		}
	}
	
	private void importCustomControls(DxlImporter importer, Database database, JavaSourceClassLoader classLoader, Set<String> compiledClassNames) throws IOException, XMLException, NotesException {
		debug("Importing custom controls");
		
		List<CustomControl> ccs = odp.getCustomControls();
		for(CustomControl cc : ccs) {
			Document dxlDoc = importXSP(importer, database, classLoader, compiledClassNames, cc);
			
			String xspConfig = cc.getXspConfigSource();
			byte[] xspConfigData = xspConfig.getBytes();
			DXLUtil.writeItemFileData(dxlDoc, "$ConfigData", xspConfigData);
			DXLUtil.writeItemNumber(dxlDoc, "$ConfigSize", xspConfigData.length);
			
			importer.importDxl(DOMUtil.getXMLString(dxlDoc), database);
		}
	}
	
	private void importXPages(DxlImporter importer, Database database, JavaSourceClassLoader classLoader, Set<String> compiledClassNames) throws IOException, XMLException, NotesException {
		debug("Importing XPages");
		
		List<XPage> xpages = odp.getXPages();
		for(XPage xpage : xpages) {
			Document dxlDoc = importXSP(importer, database, classLoader, compiledClassNames, xpage);
			importer.importDxl(DOMUtil.getXMLString(dxlDoc), database);
		}
	}
	
	private Document importXSP(DxlImporter importer, Database database, JavaSourceClassLoader classLoader, Set<String> compiledClassNames, XPage xpage) throws XMLException, IOException {
		String className = xpage.getJavaClassName();
		byte[] byteCode = classLoader.getClassByteCode(className);
		String innerClassName = xpage.getJavaClassName() + '$' + xpage.getJavaClassSimpleName() + "Page";
		byte[] innerByteCode = classLoader.getClassByteCode(innerClassName);

		String xspSource = xpage.getSource();
		byte[] xspSourceData = xspSource.getBytes();
		
		Document dxlDoc = xpage.getDxl();
		
		DXLUtil.writeItemFileData(dxlDoc, "$ClassData0", byteCode);
		DXLUtil.writeItemNumber(dxlDoc, "$ClassSize0", byteCode.length);
		DXLUtil.writeItemFileData(dxlDoc, "$ClassData1", innerByteCode);
		DXLUtil.writeItemNumber(dxlDoc, "$ClassSize1", innerByteCode.length);
		DXLUtil.writeItemFileData(dxlDoc, "$FileData", xspSourceData);
		DXLUtil.writeItemNumber(dxlDoc, "$FileSize", xspSourceData.length);
		
		String[] classIndex = new String[] { "WEB-INF/classes/" + ODPUtil.toJavaPath(className), "WEB-INF/classes/" + ODPUtil.toJavaPath(innerClassName) };
		DXLUtil.writeItemString(dxlDoc, "$ClassIndexItem", true, classIndex);
		
		// Drain them from the later queue
		compiledClassNames.remove(className);
		compiledClassNames.remove(innerClassName);
		
		return dxlDoc;
	}
	
	private void importJavaElements(DxlImporter importer, Database database, JavaSourceClassLoader classLoader, Set<String> compiledClassNames) throws FileNotFoundException, XMLException, IOException, NotesException, NumberFormatException, NotesAPIException {
		debug("Importing Java design elements");
		
		Map<Path, List<JavaSource>> javaSourceFiles = odp.getJavaSourceFiles();
		for(Map.Entry<Path, List<JavaSource>> entry : javaSourceFiles.entrySet()) {
			for(JavaSource source : entry.getValue()) {
				// TODO outside of Code/Java, I think that compiled files are two notes: one
				// 	for the source and one for the compiled bytecode
				// TODO figure out how embedded classes work
				// 	If they end up all in the class loader, perhaps iterate through that
				// 	and match generated classes to their parents. But what about non-public,
				//	non-inner classes in the same file?
				
				Path filePath = entry.getKey().relativize(source.getDataFile());
				String className = ODPUtil.toJavaClassName(filePath);
				compiledClassNames.remove(className);
				byte[] byteCode = classLoader.getClassByteCode(className);
				
				Document dxlDoc = source.getDxl();
				
				DXLUtil.writeItemFileData(dxlDoc, "$ClassData0", byteCode);
				DXLUtil.writeItemNumber(dxlDoc, "$ClassSize0", byteCode.length);
				
				List<String> classIndexItem = new ArrayList<>();
				classIndexItem.add("WEB-INF/classes/" + ODPUtil.toJavaPath(className));
				
				// Also look for any inner classes that were compiled
				List<String> innerClasses = classLoader.getCompiledClassNames().stream()
						.filter(cname -> cname.matches("^" + Pattern.quote(className) + "[\\.\\$].+$"))
						.collect(Collectors.toList());
				for(int i = 0; i < innerClasses.size(); i++) {
					String innerClassName = innerClasses.get(i);
					compiledClassNames.remove(innerClassName);
					byte[] innerByteCode = classLoader.getClassByteCode(innerClassName);
					DXLUtil.writeItemFileData(dxlDoc, "$ClassData" + (i+1), innerByteCode);
					DXLUtil.writeItemNumber(dxlDoc, "$ClassSize" + (i+1), innerByteCode.length);
					classIndexItem.add("WEB-INF/classes/" + ODPUtil.toJavaPath(innerClassName));
				}
				DXLUtil.writeItemString(dxlDoc, "$ClassIndexItem", true, classIndexItem.toArray(new CharSequence[classIndexItem.size()]));
				
				importDxl(importer, DOMUtil.getXMLString(dxlDoc), database, "Java class " + className);
			}
		}
		
		// Create standalone class files for remaining classes
		for(String leftoverClassName : compiledClassNames) {
			String fileName = "WEB-INF/classes/" + ODPUtil.toJavaPath(leftoverClassName);
			byte[] leftoverByteCode = classLoader.getClassByteCode(leftoverClassName);
			ODPUtil.importFileResource(importer, leftoverByteCode, database, fileName, "~C4g", "w");
		}
	}
	
	private void importLotusScriptLibraries(DxlImporter importer, Database database) throws Exception {
		debug("Importing LotusScript libraries");
		
		List<String> noteIds = new ArrayList<>();
		for(LotusScriptLibrary lib : odp.getLotusScriptLibraries()) {
			Document dxlDoc = lib.getDxl();
			String script = lib.getSource();
			int chunkSize = 60 * 1024;
			for(int startIndex = 0; startIndex < script.length(); startIndex += chunkSize) {
				int endIndex = Math.min(startIndex+chunkSize, script.length());
				String scriptChunk = script.substring(startIndex, endIndex);
				Element el = DXLUtil.writeItemString(dxlDoc, "$ScriptLib", false, scriptChunk);
				el.setAttribute("sign", "true");
				el.setAttribute("summary", "false");
				
				noteIds.addAll(importDxl(importer, DOMUtil.getXMLString(dxlDoc), database, "LotusScript library " + odp.getBaseDirectory().relativize(lib.getDataFile())));
			}
		}

		debug("- Compiling LotusScript");
		// In lieu of a dependency graph, just keep bashing at the list until it's done
		Queue<String> remaining = new ArrayDeque<>(noteIds);
		for(int i = 0; i < noteIds.size(); i++) {
			Queue<String> nextPass = new ArrayDeque<>();
			
			String noteId;
			while((noteId = remaining.poll()) != null) {
				lotus.domino.Document doc = database.getDocumentByID(noteId);
				try {
					NotesGC.runWithAutoGC(() -> {
						NotesNote notesNote = LegacyAPIUtils.toNotesNote(doc);
						notesNote.compileLotusScript();
						notesNote.update();
						return null;
					});
				} catch(LotusScriptCompilationError err) {
					nextPass.add(noteId);
				} catch(NotesError err) {
					if(err.getId() == 12051) { // Same as above, but not encapsulated
						nextPass.add(noteId);
					} else {
						throw err;
					}
				}
			}
			
			remaining = nextPass;
			if(nextPass.isEmpty()) {
				break;
			}
		}
		if(!remaining.isEmpty()) {
			throw new RuntimeException("Unable to compile LotusScript in note IDs: " + remaining);
		}
	}
	
	// *******************************************************************************
	// * Internal utility methods
	// *******************************************************************************
	
	private void debug(Object message, Object... params) {
		if(out != null) {
			out.println(StringUtil.format(StringUtil.toString(message), params));
		}
	}
	
	private UpdatableLibrary getLibrary(String namespace) {
		UpdatableLibrary library = (UpdatableLibrary)facesRegistry.getLocalLibrary(namespace);
		if(library == null) {
			try {
				library = new FacesLibraryImpl(facesRegistry, namespace);
				// TODO this is probably properly done by creating a FacesProjectImpl
				// - it can then register the library fragments itself
				Field localLibsField = facesRegistry.getClass().getDeclaredField("_localLibs");
				localLibsField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<String, UpdatableLibrary> localLibs = (Map<String, UpdatableLibrary>)localLibsField.get(facesRegistry);
				localLibs.put(namespace, library);
			} catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		return library;
	}
	
	private XSPCompilationResult compileXSP(XPage xpage, JavaSourceClassLoader classLoader) throws Exception {
		try {
			String xspSource = xpage.getSource();
			String javaSource = dynamicXPageBean.translate(xpage.getJavaClassName(), xpage.getPageName(), xspSource, facesRegistry);
			Class<?> compiled = classLoader.addClass(xpage.getJavaClassName(), javaSource);
			return new XSPCompilationResult(javaSource, compiled);
		} catch(FacesPageException e) {
			throw new RuntimeException("Exception while converting XSP element " + odp.getBaseDirectory().relativize(xpage.getDataFile()), e);
		}
	}
	
	private static final boolean DEBUG_DXL = false;
	
	private List<String> importDxl(DxlImporter importer, String dxl, Database database, String name) throws NotesException, IOException {
		try {
			if(DEBUG_DXL) {
				String tempFileName = "c:\\temp\\dxl\\" + name.replace('/', '-').replace('\\', '-') + ".xml";
				try(OutputStream os = Files.newOutputStream(Paths.get(tempFileName))) {
					os.write(dxl.getBytes());
				}
			}
			
			importer.importDxl(dxl, database);
			
			List<String> importedIds = new ArrayList<>();
			String noteId = importer.getFirstImportedNoteID();
			while(StringUtil.isNotEmpty(noteId)) {
				importedIds.add(noteId);
				noteId = importer.getNextImportedNoteID(noteId);
			}
			return importedIds;
		} catch(NotesException ne) {
			if(ne.text.contains("DXL importer operation failed")) {
				debug("Exception while importing " + name);
				String log = importer.getLog();
				debug(log);
				debug(dxl);
			}
			throw ne;
		}
	}
}
