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
package org.openntf.nsfodp.eclipse.m2e.job;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;
import org.openntf.nsfodp.eclipse.m2e.Messages;
import org.openntf.nsfodp.eclipse.m2e.ODPPDEUtil;

public class DeployNSFJob extends Job {
	
	private final IProject project;

	public DeployNSFJob(IProject project) {
		super(Messages.DeployNSFJob_label);
		this.project = project;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		return ODPPDEUtil.INSTANCE.executeMavenGoal(project, monitor, "deploy"); //$NON-NLS-1$
	}

}
