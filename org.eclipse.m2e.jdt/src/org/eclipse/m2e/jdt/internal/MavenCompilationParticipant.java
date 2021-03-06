/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.jdt.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.compiler.CompilationParticipant;

import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.builder.MavenBuilder;


/**
 * Special participant that gets notified whenever the output path is cleared. Then the next build must be performed in
 * any case.
 */
public class MavenCompilationParticipant extends CompilationParticipant {
  static final Logger log = LoggerFactory.getLogger(MavenCompilationParticipant.class);

  public MavenCompilationParticipant() {
  }

  /**
   * Activate for Maven projects
   */
  public boolean isActive(IJavaProject project) {
    try {
      return project.getProject().hasNature(IMavenConstants.NATURE_ID);
    } catch(CoreException ex) {
      log.warn("Failed to determine project's " + project.getProject().getName() + " nature.");
    }
    return super.isActive(project);
  }

  /**
   * Mark the project as needing a build
   */
  public void cleanStarting(IJavaProject project) {
    try {
      project.getProject().setPersistentProperty(MavenBuilder.PPROP_FORCE_BUILD, "true");
    } catch(CoreException ex) {
      log.warn("Failed to mark project " + project.getProject().getName() + " for building.");
    }
  }
}
