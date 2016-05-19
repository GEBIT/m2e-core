/*******************************************************************************
 * Copyright (c) 2016 Igor Fedorenko
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Igor Fedorenko - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.internal.project;

import java.io.File;
import java.util.Set;

import javax.inject.Singleton;

import org.apache.maven.plugin.DefaultProjectArtifactsCache;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.embedder.ArtifactKey;


@Singleton
@SuppressWarnings("synthetic-access")
public class EclipseProjectArtifactsCache extends DefaultProjectArtifactsCache implements IManagedCache {

  private final ProjectCachePlunger<Key> plunger = new ProjectCachePlunger<Key>() {
    protected void flush(Key cacheKey) {
      cache.remove(cacheKey);
    }
  };

  @Override
  public void register(MavenProject project, Key cacheKey, CacheRecord record) {
    plunger.register(project, cacheKey);
  }

  @Override
  public Set<File> removeProject(File pom, ArtifactKey mavenProject, boolean forceDependencyUpdate) {
    return plunger.removeProject(pom, forceDependencyUpdate);
  };

  @Override
  public void flush() {
    super.flush();
    plunger.flush();
  }

}
