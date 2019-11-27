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
package org.eclipse.m2e.core.internal;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.apache.maven.AbstractMavenLifecycleParticipant;

import org.eclipse.m2e.core.internal.project.ILifecycleParticipant;


/**
 * Default implementation if no other is provided by the extensions. The use case for providing a custom subclass is for
 * implementing {@link #isApplicable(File)}.
 */
public class DefaultLifecycleParticipant implements ILifecycleParticipant {
  private String hint;

  private Class<? extends AbstractMavenLifecycleParticipant> impl;

  public DefaultLifecycleParticipant(String hint, Class<? extends AbstractMavenLifecycleParticipant> impl) {
    this.hint = hint;
    this.impl = impl;
  }

  /* (non-Javadoc)
   * @see org.eclipse.m2e.core.internal.project.ILifecycleParticipant#getHint()
   */
  public String getHint() {
    return hint;
  }

  /**
   * By default always applicable
   */
  public boolean isApplicable(File basedir) {
    return true;
  }

  /* (non-Javadoc)
  * @see org.eclipse.m2e.core.internal.project.ILifecycleParticipant#getParticipant()
  */
  public AbstractMavenLifecycleParticipant getParticipant(AbstractMavenLifecycleParticipant mavenParticipant) {
    if(impl == null) {
      return mavenParticipant;
    }
    try {
      try {
        Constructor<? extends AbstractMavenLifecycleParticipant> constructor = impl
            .getConstructor(AbstractMavenLifecycleParticipant.class);
        return constructor.newInstance(mavenParticipant);
      } catch(NoSuchMethodException ex) {
        // try no-arg constructor
        try {
          Constructor<? extends AbstractMavenLifecycleParticipant> constructor = impl.getConstructor();
          return constructor.newInstance();
        } catch(NoSuchMethodException ex1) {
          ExtensionReader.log.error(ex1.getMessage(), ex1);
        }
      }
    } catch(SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException
        | InvocationTargetException ex) {
      ExtensionReader.log.error(ex.getMessage(), ex);
    }
    // by default use the maven instance
    return mavenParticipant;
  }
}