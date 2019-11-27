/*******************************************************************************
 * Copyright (c) 2019 GEBIT Solutions GmbH
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      GEBIT Solutions GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.m2e.core.internal.project;

import java.io.File;

import org.apache.maven.AbstractMavenLifecycleParticipant;


/**
 * ILifecycleParticipant
 */
public interface ILifecycleParticipant {
  /**
   * Get the role hint associated with the participant. Will be used to match configured participants.
   */
  public String getHint();

  /**
   * Get replacement implementation in m2e scope for the given participant. This is needed for core extensions, as these
   * are not otherwise available out of a MavenProject context.
   * 
   * @param mavenParticipant the participant create by maven. If the replacement participant has a constructur with
   *          {@link AbstractMavenLifecycleParticipant} argument it is passed to it.
   * @return <code>mavenParticipant</code> if no replacement is available, never <code>null</code>
   */
  public AbstractMavenLifecycleParticipant getParticipant(AbstractMavenLifecycleParticipant mavenParticipant);

  /**
   * Check whether the given participant is applicable in the given context
   * 
   * @param basedir the Maven basedir (aggregator root)
   * @return <code>true</code> if the extension is applicable
   */
  public boolean isApplicable(File basedir);
}
