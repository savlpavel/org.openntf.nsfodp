/*
 * Copyright (c) 2018-2025 Jesse Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.nsfodp.exporter.equinox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.Collections;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.openntf.nsfodp.commons.NSFODPConstants;
import org.openntf.nsfodp.commons.NSFODPUtil;
import org.openntf.nsfodp.commons.odp.notesapi.NDatabase;
import org.openntf.nsfodp.commons.odp.notesapi.NotesAPI;
import org.openntf.nsfodp.exporter.ODPExporter;

import lotus.domino.NotesThread;

public class ExporterApplication implements IApplication {

	public ExporterApplication() {
	}

	@Override
	public Object start(IApplicationContext context) throws Exception {
		String notesIni = System.getenv(NSFODPConstants.PROP_NOTESINI);
		if(notesIni != null && !notesIni.isEmpty()) {
			String execDir = System.getenv("Notes_ExecDirectory"); //$NON-NLS-1$
			try(NotesAPI api = NotesAPI.get()) {
				api.NotesInitExtended(execDir, "=" + notesIni); //$NON-NLS-1$
			}
		}
		
		String databasePath = System.getenv(NSFODPConstants.PROP_EXPORTER_DATABASE_PATH);
		if(databasePath == null) {
			throw new IllegalArgumentException(MessageFormat.format(Messages.ExporterApplication_dbPathCannotBeEmpty, NSFODPConstants.PROP_EXPORTER_DATABASE_PATH));
		}
		Path odpDir = Paths.get(System.getenv(NSFODPConstants.PROP_OUTPUTFILE));
		
		boolean binaryDxl = "true".equals(System.getenv(NSFODPConstants.PROP_EXPORTER_BINARY_DXL)); //$NON-NLS-1$
		boolean swiperFilter = "true".equals(System.getenv(NSFODPConstants.PROP_EXPORTER_SWIPER_FILTER)); //$NON-NLS-1$
		boolean richTextAsItemData = "true".equals(System.getenv(NSFODPConstants.PROP_RICH_TEXT_AS_ITEM_DATA)); //$NON-NLS-1$
		String projectName = System.getenv(NSFODPConstants.PROP_PROJECT_NAME);
		
		NotesThread runner = new NotesThread(() -> {
			try(NotesAPI session = NotesAPI.get()) {
				try(NDatabase database = session.openDatabase(databasePath)) {
				
					ODPExporter exporter = new ODPExporter(database);
					exporter.setBinaryDxl(binaryDxl);
					exporter.setSwiperFilter(swiperFilter);
					exporter.setRichTextAsItemData(richTextAsItemData);
					exporter.setProjectName(projectName);
					Path result = exporter.export();
					Path eclipseProject = odpDir.resolve(".project"); //$NON-NLS-1$
					if(Files.exists(eclipseProject)) {
						Path tempPath = Files.createTempFile("nsfodp", ".project"); //$NON-NLS-1$ //$NON-NLS-2$
						Files.move(eclipseProject, tempPath, StandardCopyOption.REPLACE_EXISTING);
						eclipseProject = tempPath;
					} else {
						eclipseProject = null;
					}
					if(Files.exists(odpDir)) {
						NSFODPUtil.deltree(Collections.singleton(odpDir));
					}
					Files.createDirectories(odpDir);
					NSFODPUtil.moveDirectory(result, odpDir);
					if(eclipseProject != null) {
						Files.move(eclipseProject, odpDir.resolve(".project"), StandardCopyOption.REPLACE_EXISTING); //$NON-NLS-1$
					}
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
