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

package org.eclipse.m2e.core.internal.project.registry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionConstraint;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import org.apache.maven.model.Model;
import org.apache.maven.repository.LocalArtifactRepository;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IWorkspaceClassifierResolver;


public final class EclipseWorkspaceArtifactRepository extends LocalArtifactRepository implements WorkspaceReader {
  private static final GenericVersionScheme versionScheme = new GenericVersionScheme();

  private final transient ProjectRegistryManager.Context context;

  private static final ThreadLocal<Boolean> disabled = new ThreadLocal<Boolean>();

  private WorkspaceRepository workspaceRepository;

  public EclipseWorkspaceArtifactRepository(ProjectRegistryManager.Context context) {
    this.context = context;
    this.workspaceRepository = new WorkspaceRepository("ide", getClass()); //$NON-NLS-1$
  }

  protected File resolveAsEclipseProject(String groupId, String artifactId, String baseVersion, String classifier,
      String extension) {
    if(isDisabled()) {
      return null;
    }

    if(context == null) { // XXX this is actually a bug
      return null;
    }

    if(!classifier.isEmpty()) {
      // cannot resolve these from the workspace
      return null;
    }

    // check in the workspace, note that workspace artifacts never have classifiers
    IFile pom = getWorkspaceArtifact(groupId, artifactId, baseVersion);
    if(pom == null || !pom.isAccessible()) {
      VersionConstraint constraint;
      try {
        constraint = versionScheme.parseVersionConstraint(baseVersion);
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject projectLikeArtifact = root.getProject(artifactId);
        if(!projectLikeArtifact.isAccessible()) {
          pom = null;
        } else if(projectLikeArtifact.exists()) {
          // has it a pom?
          IFile projectPom = projectLikeArtifact.getFile("pom.xml");
          if(projectPom.exists()) {
            // does it match?
            IMavenProjectFacade facade = context.state.getProjectFacade(projectPom);
            if(facade != null) {
              ArtifactKey artifactKey = facade.getArtifactKey();
              if(groupId.equals(artifactKey.getGroupId()) && artifactId.equals(artifactKey.getArtifactId())
                  && constraint.containsVersion(versionScheme.parseVersion(artifactKey.getVersion()))) {
                pom = projectPom;
              }
            } else {
              try (InputStream pomIS = projectPom.getContents()) {
                Model model = MavenPlugin.getMaven().readModel(pomIS);
                String pomGroupId = model.getGroupId();
                String pomVersion = model.getVersion();
                if(pomGroupId == null && model.getParent() != null) {
                  pomGroupId = model.getParent().getGroupId();
                }
                if(pomVersion == null && model.getParent() != null) {
                  pomVersion = model.getParent().getVersion();
                }
                if(groupId.equals(pomGroupId) && artifactId.equals(model.getArtifactId())
                    && constraint.containsVersion(versionScheme.parseVersion(pomVersion))) {
                  pom = projectPom;
                }
              } catch(CoreException | IOException ex) {
                // TODO Auto-generated catch block
              }
            }
          }
        }
      } catch(InvalidVersionSpecificationException e) {
        // broken version range spec does not match anything
      }
      if(pom == null) {
        return null;
      }
    }
    if(context.pom != null && pom.equals(context.pom)) {
      return null;
    }

    if(context.resolverConfiguration.shouldResolveWorkspaceProjects()) {
      IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
      IPath file = pom.getLocation();
      if(!"pom".equals(extension) && !"xml".equals(extension)) { //$NON-NLS-1$
        MavenProjectFacade facade = context.state.getProjectFacade(pom);
        if(facade != null) {
          IWorkspaceClassifierResolver resolver = MavenPlugin.getWorkspaceClassifierResolverManager().getResolver();
          IPath location = resolver.resolveClassifier(facade, classifier);

          if(location == null) {
            location = facade.getOutputLocation();
          }

          if(location != null) {
            IResource res = root.findMember(location);
            if(res != null) {
              return res.getLocation().toFile();
            }
          }
        }
        return null;
      }

      if(file != null) {
        return file.toFile();
      }
    }

    return null;
  }

  private IFile getWorkspaceArtifact(String groupId, String artifactId, String version) {
    Map<ArtifactKey, Collection<IFile>> workspaceArtifacts = context.state.getWorkspaceArtifacts(groupId, artifactId);
    if(workspaceArtifacts.isEmpty()) {
      return null;
    }
    VersionConstraint constraint;
    try {
      constraint = versionScheme.parseVersionConstraint(version);
    } catch(InvalidVersionSpecificationException e) {
      return null; // broken version range spec does not match anything
    }
    TreeMap<Version, ArtifactKey> matchingArtifacts = new TreeMap<>();
    // in vast majority of cases there will be single workspace artifact with matching groupId and artifactId
    for(ArtifactKey workspaceArtifact : workspaceArtifacts.keySet()) {
      try {
        Version workspaceVersion = versionScheme.parseVersion(workspaceArtifact.getVersion());
        if(constraint.containsVersion(workspaceVersion)) {
          matchingArtifacts.put(workspaceVersion, workspaceArtifact);
        }
      } catch(InvalidVersionSpecificationException e) {
        // this can't happen with GenericVersionScheme
      }
    }
    if(matchingArtifacts.isEmpty()) {
      // maybe the artifact is outdated -- if it is, return it anyway, because maybe the version had just been updated
      for(Collection<IFile> artifacts : workspaceArtifacts.values()) {
        for(IFile pom : artifacts) {
          MavenProjectFacade projectFacade = context.state.getProjectFacade(pom);
          if(projectFacade != null && projectFacade.isStale()) {
            return pom;
          }
        }
      }
      return null;
    }
    ArtifactKey matchingArtifact = matchingArtifacts.values().iterator().next();
    return workspaceArtifacts.get(matchingArtifact).iterator().next();
  }

  public File findArtifact(Artifact artifact) {
    return resolveAsEclipseProject(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(),
        artifact.getClassifier(), artifact.getExtension());
  }

  public org.apache.maven.artifact.Artifact find(org.apache.maven.artifact.Artifact artifact) {
    File file = resolveAsEclipseProject(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(),
        artifact.getClassifier(), artifact.getType());

    if(file != null) {
      artifact.setFile(file);
      artifact.setResolved(true);
    }

    return artifact;
  }

  public boolean hasLocalMetadata() {
    return false; // XXX
  }

  public static void setDisabled(boolean disable) {
    disabled.set(disable ? Boolean.TRUE : null);
  }

  public static boolean isDisabled() {
    return Boolean.TRUE.equals(disabled.get());
  }

  public int hashCode() {
    return 0; // no state
  }

  public boolean equals(Object obj) {
    return obj instanceof EclipseWorkspaceArtifactRepository;
  }

  public List<String> findVersions(Artifact artifact) {
    return findVersions(artifact.getGroupId(), artifact.getArtifactId());
  }

  @Override
  public List<String> findVersions(org.apache.maven.artifact.Artifact artifact) {
    return findVersions(artifact.getGroupId(), artifact.getArtifactId());
  }

  private List<String> findVersions(String groupId, String artifactId) {
    ArrayList<String> versions = new ArrayList<String>();

    if(isDisabled()) {
      return versions;
    }

    if(context == null) { // XXX this is actually a bug
      return versions;
    }

    for(MavenProjectFacade facade : context.state.getProjects()) {
      ArtifactKey artifactKey = facade.getArtifactKey();
      if(groupId.equals(artifactKey.getGroupId()) && artifactId.equals(artifactKey.getArtifactId())) {
        versions.add(artifactKey.getVersion());
      }
    }

    return versions;
  }

  public WorkspaceRepository getRepository() {
    return workspaceRepository;
  }

}
