/*******************************************************************************
 * Copyright (c) 2019 GEBIT Solutions GmbH, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      GEBIT Solutions GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.m2e.core.project;

/**
 * IResolverExtension
 *
 * @author GEBIT Solutions GmbH
 */
public interface IResolverExtension {

  /**
   * Called after reading a {@link ResolverConfiguration}. An extension can then customize the configuration before it
   * is used.
   * 
   * @param projectName the name of the project
   * @param configuration the configuration read or intialized
   */
  void updateResolverConfiguration(String projectName, ResolverConfiguration configuration);

}
