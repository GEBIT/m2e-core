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
   * @return <code>null</code> if no replacement is available
   */
  public AbstractMavenLifecycleParticipant getParticipant();
}
