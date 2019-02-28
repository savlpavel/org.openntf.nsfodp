package org.openntf.nsfodp.exporter.equinox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.openntf.nsfodp.commons.NSFODPConstants;
import org.openntf.nsfodp.commons.NSFODPUtil;
import org.openntf.nsfodp.exporter.ODPExporter;

import com.ibm.designer.domino.napi.NotesDatabase;
import com.ibm.designer.domino.napi.NotesSession;
import com.ibm.domino.napi.c.C;

import lotus.domino.NotesThread;

public class ExporterApplication implements IApplication {

	public ExporterApplication() {
	}

	@Override
	public Object start(IApplicationContext context) throws Exception {
		String databasePath = System.getProperty(NSFODPConstants.PROP_EXPORTER_DATABASE_PATH);
		if(databasePath == null) {
			throw new IllegalArgumentException(NSFODPConstants.PROP_EXPORTER_DATABASE_PATH + " cannot be empty");
		}
		Path odpDir = Paths.get(System.getProperty(NSFODPConstants.PROP_OUTPUTFILE));
		
		boolean binaryDxl = "true".equals(System.getProperty(NSFODPConstants.PROP_EXPORTER_SWIPER_FILTER));
		boolean swiperFilter = "true".equals(System.getProperty(NSFODPConstants.PROP_EXPORTER_SWIPER_FILTER));
		boolean richTextAsItemData = "true".equals(System.getProperty(NSFODPConstants.PROP_RICH_TEXT_AS_ITEM_DATA));
		
		NotesThread runner = new NotesThread(() -> {
			C.initLibrary(null);
			
			try {
				NotesSession session = new NotesSession();
				try {
					NotesDatabase database = session.getDatabaseByPath(databasePath);
					database.open();
					
					ODPExporter exporter = new ODPExporter(database);
					exporter.setBinaryDxl(binaryDxl);
					exporter.setSwiperFilter(swiperFilter);
					exporter.setRichTextAsItemData(richTextAsItemData);
					Path result = exporter.export();
					Path eclipseProject = odpDir.resolve(".project");
					if(Files.exists(eclipseProject)) {
						System.out.println("Stashing the eclipse project");
						Path tempPath = Files.createTempFile("nsfodp", ".project");
						Files.delete(tempPath);
						Files.move(eclipseProject, tempPath);
						eclipseProject = tempPath;
					} else {
						eclipseProject = null;
					}
					if(Files.exists(odpDir)) {
						NSFODPUtil.deltree(Collections.singleton(odpDir));
					}
					Files.move(result, odpDir);
					if(eclipseProject != null) {
						Files.move(eclipseProject, odpDir.resolve(".project"), StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					session.recycle();
				}
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		});
		runner.run();
		runner.join();
		
		return EXIT_OK;
	}

	@Override
	public void stop() {
	}
}
