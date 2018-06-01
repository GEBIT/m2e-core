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

package org.eclipse.m2e.core.internal.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

import org.codehaus.plexus.util.MatchPatterns;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.M2EUtils;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.embedder.MavenExecutionContext;
import org.eclipse.m2e.core.internal.lifecyclemapping.LifecycleMappingFactory;
import org.eclipse.m2e.core.internal.markers.IMavenMarkerManager;
import org.eclipse.m2e.core.internal.project.registry.MavenProjectFacade;
import org.eclipse.m2e.core.internal.project.registry.ProjectRegistryManager;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.ResolverConfiguration;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.ILifecycleMapping;
import org.eclipse.m2e.core.project.configurator.MojoExecutionKey;


public class MavenBuilder extends IncrementalProjectBuilder implements DeltaProvider {
  static final Logger log = LoggerFactory.getLogger(MavenBuilder.class);

  private static final String PROJ_PROP_IGNORED_PATHES = "ignoredPathes"; //$NON-NLS-1$

  private static final String ELEMENT_IGNORED_PATHES = "ignoredPathes"; //$NON-NLS-1$

  private static final String ELEMENT_IGNORE = "ignore"; //$NON-NLS-1$

  public static QualifiedName PPROP_FORCE_BUILD = new QualifiedName(MavenBuilder.class.getName(), "forceBuild"); //$NON-NLS-1$

  final MavenBuilderImpl builder = new MavenBuilderImpl(this);

  final ProjectRegistryManager projectManager = MavenPluginActivator.getDefault().getMavenProjectManagerImpl();

  private abstract class BuildMethod<T> {

    final IProjectConfigurationManager configurationManager = MavenPlugin.getProjectConfigurationManager();

    final IMavenMarkerManager markerManager = MavenPluginActivator.getDefault().getMavenMarkerManager();

    public BuildMethod() {

    }

    public final T execute(final int kind, final Map<String, String> args, IProgressMonitor monitor)
        throws CoreException {
      final IProject project = getProject();
      markerManager.deleteMarkers(project, kind == FULL_BUILD || kind == CLEAN_BUILD, IMavenConstants.MARKER_BUILD_ID);
      final IFile pomResource = project.getFile(IMavenConstants.POM_FILE_NAME);
      if(pomResource == null) {
        return null;
      }

      ResolverConfiguration resolverConfiguration = configurationManager.getResolverConfiguration(project);

      if(resolverConfiguration == null) {
        // TODO unit test me
        return null;
      }

      final MavenExecutionContext context = projectManager.createExecutionContext(pomResource, resolverConfiguration);

      return context.execute(new ICallable<T>() {
        @Override
        public T call(IMavenExecutionContext context, IProgressMonitor monitor) throws CoreException {
          final IMavenProjectFacade projectFacade = getProjectFacade(project, monitor);

          if(projectFacade == null) {
            return null;
          }

          MavenProject mavenProject;
          try {
            // make sure projectFacade has MavenProject instance loaded
            mavenProject = projectFacade.getMavenProject(monitor);
          } catch(CoreException ce) {
            //unable to read the project facade
            addErrorMarker(project, ce);
            return null;
          }

          return context.execute(mavenProject, new ICallable<T>() {
            public T call(IMavenExecutionContext context, IProgressMonitor monitor) throws CoreException {
              ILifecycleMapping lifecycleMapping = configurationManager.getLifecycleMapping(projectFacade);
              if(lifecycleMapping == null) {
                return null;
              }

              Map<MojoExecutionKey, List<AbstractBuildParticipant>> buildParticipantsByMojoExecutionKey = lifecycleMapping
                  .getBuildParticipants(projectFacade, monitor);

              return method(context, projectFacade, buildParticipantsByMojoExecutionKey, kind, args, monitor);
            }
          }, monitor);
        }
      }, monitor);
    }

    abstract T method(IMavenExecutionContext context, IMavenProjectFacade projectFacade,
        Map<MojoExecutionKey, List<AbstractBuildParticipant>> buildParticipantsByMojoExecutionKey, int kind,
        Map<String, String> args, IProgressMonitor monitor) throws CoreException;

    void addErrorMarker(IProject project, Exception e) {
      String msg = e.getMessage();
      String rootCause = M2EUtils.getRootCauseMessage(e);
      if(msg != null && !msg.equals(rootCause)) {
        msg = msg + ": " + rootCause; //$NON-NLS-1$
      }

      markerManager.addMarker(project, IMavenConstants.MARKER_BUILD_ID, msg, 1, IMarker.SEVERITY_ERROR);
    }

    IMavenProjectFacade getProjectFacade(final IProject project, final IProgressMonitor monitor) throws CoreException {
      final IFile pomResource = project.getFile(IMavenConstants.POM_FILE_NAME);

      // facade refresh should be forced whenever pom.xml has changed
      // there is no delta info for full builds
      // but these are usually forced from Project/Clean
      // so assume pom did not change
      boolean force = false;

      IResourceDelta delta = getDelta(project);
      if(delta != null) {
        delta = delta.findMember(pomResource.getFullPath());
        force = delta != null && delta.getKind() == IResourceDelta.CHANGED;
      }

      IMavenProjectFacade projectFacade = projectManager.getProject(project);

      if(force || projectFacade == null || projectFacade.isStale()) {
        projectManager.refresh(Collections.singleton(pomResource), monitor);
        projectFacade = projectManager.getProject(project);
        if(projectFacade == null) {
          // error marker should have been created
          return null;
        }
      }

      return projectFacade;
    }
  }

  private BuildMethod<IProject[]> methodBuild = new BuildMethod<IProject[]>() {
    @Override
    protected IProject[] method(IMavenExecutionContext context, IMavenProjectFacade projectFacade,
        Map<MojoExecutionKey, List<AbstractBuildParticipant>> buildParticipantsByMojoExecutionKey, int kind,
        Map<String, String> args, IProgressMonitor monitor) throws CoreException {

      Set<IProject> dependencies = builder.build(context.getSession(), projectFacade, kind, args,
          buildParticipantsByMojoExecutionKey, monitor);

      if(dependencies.isEmpty()) {
        return null;
      }

      return dependencies.toArray(new IProject[dependencies.size()]);
    }
  };

  private BuildMethod<Void> methodClean = new BuildMethod<Void>() {
    @Override
    protected Void method(IMavenExecutionContext context, IMavenProjectFacade projectFacade,
        Map<MojoExecutionKey, List<AbstractBuildParticipant>> buildParticipantsByMojoExecutionKey, int kind,
        Map<String, String> args, IProgressMonitor monitor) throws CoreException {

      builder.clean(context.getSession(), projectFacade, buildParticipantsByMojoExecutionKey, monitor);

      return null;
    }
  };

  protected IProject[] build(final int kind, final Map<String, String> args, final IProgressMonitor monitor)
      throws CoreException {
    log.debug("Building project {}", getProject().getName()); //$NON-NLS-1$
    final long start = System.currentTimeMillis();
    try {
      if(isBuildNeeded(kind, monitor)) {
        return methodBuild.execute(kind, args, monitor);
      }
      log.debug("Not building project {} because the resource changes only occurred in its output or ignored folders",
          getProject().getName());
      return getProjectDependencies(getProject(), monitor);
    } finally {
      log.debug("Built project {} in {} ms", getProject().getName(), System.currentTimeMillis() - start); //$NON-NLS-1$
    }
  }

  /**
   * @param kind
   * @return
   */
  private boolean isBuildNeeded(int buildKind, IProgressMonitor monitor) throws CoreException {
    IProject project = getProject();
    if(buildKind == FULL_BUILD || buildKind == CLEAN_BUILD) {
      project.setPersistentProperty(PPROP_FORCE_BUILD, null);
      return true;
    }

    for(IMarker problem : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
      log.info(
          "Marker " + problem.getType() + " severity " + problem.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO));
      if(problem.getType().startsWith("org.eclipse.jdt")) {
        // check for CAT_BUILDPATH, need to wait for other project first
        if(problem.getAttribute("categoryId", 0) == 10) {
          // cannot build due to other project -> ignore
          return false;
        }
        if(IMarker.SEVERITY_ERROR == problem.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
          // force build to give a chance to correct errors
          log.error("Build due to marker " + problem.getType());
          return true;
        }
      }
    }

    if(project.getPersistentProperty(PPROP_FORCE_BUILD) != null) {
      project.setPersistentProperty(PPROP_FORCE_BUILD, null);
      return true;
    }
    IResourceDelta projectDelta = getDelta(project);
    if(projectDelta == null) {
      return true;
    }

    if(projectDelta.getAffectedChildren().length == 0) {
      // from the spec of getDelta(), we should not need to build, but in practice, we need to
      // build whenever a parent project changes, for which we do not get a delta, probably because
      // it is a different build
      if("true".equals(System.getProperty("m2e.noBuildOnEmptyDelta"))) {
        // configured to not build on empty delta. This might leave dependent projects with build errors, especially
        // if the delta is from a parent in which new sources were added (generated)
        return false;
      }
      return true;
    }

    final List<IPath> ignorableOutputPaths = getIgnorableOutputPaths(project);
    String[] ignorePathes = getProjectIgnoredPathes(project, monitor);
    if(ignorableOutputPaths.isEmpty() && ignorePathes.length == 0) {
      // can't decide, so build
      return true;
    }

    final List<IPath> fourceBuildPaths = getCompileSourcePaths(project);
    final MatchPatterns ignorePatterns = MatchPatterns.from(ignorePathes);

    boolean isNeeded[] = new boolean[] {false};
    // check if there is any entry in the delta that is not in the outputLocation or testOutputLocation
    try {
      projectDelta.accept(new IResourceDeltaVisitor() {
        public boolean visit(IResourceDelta delta) throws CoreException {
          IResource resource = delta.getResource();
          if(resource == null) {
            return true;
          }

          switch(resource.getType()) {
            case IResource.PROJECT: {
              if(delta.getFlags() == IResourceDelta.DESCRIPTION) {
                isNeeded[0] = true;
                return false; // no need to check children
              }
              break;
            }
          }
          if(delta.getKind() == IResourceDelta.NO_CHANGE) {
            return true;
          }
          for(IPath forceBuildPath : fourceBuildPaths) {
            if(forceBuildPath.isPrefixOf(resource.getFullPath())) {
              isNeeded[0] = true;
              return false; // no need to check children
            }
          }
          for(IPath ignorePath : ignorableOutputPaths) {
            if(ignorePath.isPrefixOf(resource.getFullPath())) {
              return false; // no need to check children
            }
          }
          String projectRelativePath = resource.getProjectRelativePath().toOSString();
          if(ignorePatterns.matches(projectRelativePath, false)) {
            return false; // no need to check children
          }
          if(resource.getType() != IResource.FILE) {
            return true;
          }
          isNeeded[0] = true;
          // no need to check any other part of the delta
          throw new OperationCanceledException();
        }
      });
    } catch(OperationCanceledException ex) {
      // ignore
    } catch(CoreException ex) {
      log.error(ex.getMessage(), ex);
    }
    return isNeeded[0];
  }

  private String[] getProjectIgnoredPathes(IProject project, IProgressMonitor monitor) throws CoreException {
    // TODO this does not merge configuration from profiles
    IMavenProjectFacade facade = projectManager.getProject(project);
    if(facade != null) {
      MavenProject mavenProject = facade.getMavenProject(monitor);
      if(mavenProject != null) {
        String[] result = null;
        PluginManagement pluginManagement = mavenProject.getPluginManagement();
        Plugin metadataPlugin = pluginManagement.getPluginsAsMap()
            .get(LifecycleMappingFactory.LIFECYCLE_MAPPING_PLUGIN_GROUPID + ":" //$NON-NLS-1$
                + LifecycleMappingFactory.LIFECYCLE_MAPPING_PLUGIN_ARTIFACTID);
        if(metadataPlugin != null) {
          Xpp3Dom configurationDom = (Xpp3Dom) metadataPlugin.getConfiguration();
          if(configurationDom != null) {
            Xpp3Dom ignoresDom = configurationDom.getChild(ELEMENT_IGNORED_PATHES);
            if(ignoresDom != null) {
              Xpp3Dom[] ignores = ignoresDom.getChildren(ELEMENT_IGNORE);
              if(ignores != null && ignores.length > 0) {
                result = new String[ignores.length];
                for(int i = 0; i < ignores.length; ++i) {
                  result[i] = ignores[i].getValue().trim();
                }
              } else if(!ignoresDom.getValue().isEmpty()) {
                String[] ignoresValues = ignoresDom.getValue().split(",");
                result = new String[ignoresValues.length];
                for(int i = 0; i < ignoresValues.length; ++i) {
                  result[i] = ignoresValues[i].trim();
                }
              }
              if(result != null) {
                // normalize separators
                for(int i = 0; i < result.length; ++i) {
                  result[i] = result[i].replace(File.separatorChar == '/' ? '\\' : '/', File.separatorChar);
                  if(result[i].endsWith(File.separator)) {
                    result[i] += "**";
                  }
                }
                return result;
              }
            }
          }
        }
      }
    }
    return new String[0];
  }

  /**
   * Returns a list of project relative paths that, when changed, shall trigger a build.
   *
   * @param project
   * @return
   */
  private List<IPath> getCompileSourcePaths(IProject project) {
    IMavenProjectFacade facade = projectManager.getProject(project);
    if(facade != null) {
      List<IPath> compileSourcePaths = new ArrayList<>(3);
      IPath[] sourceDirs = facade.getCompileSourceLocations();
      if(sourceDirs != null) {
        compileSourcePaths.addAll(Arrays.asList(sourceDirs));
      }
      return compileSourcePaths;
    }
    return Collections.emptyList();
  }

  /**
   * Returns a list of project relative paths that, when changed, shall not trigger a build.
   *
   * @param project
   * @return
   */
  private List<IPath> getIgnorableOutputPaths(IProject project) {
    IMavenProjectFacade facade = projectManager.getProject(project);
    if(facade != null) {
      List<IPath> ignorablePaths = new ArrayList<>(3);
      IPath buildDir = facade.getBuildOutputPath();
      if(buildDir != null) {
        ignorablePaths.add(buildDir);
      }
      IPath outputLocation = facade.getOutputLocation();
      if(outputLocation != null) {
        if(buildDir == null || !buildDir.isPrefixOf(outputLocation)) {
          ignorablePaths.add(outputLocation);
        }
      }
      IPath testOutputLocation = facade.getTestOutputLocation();
      if(testOutputLocation != null) {
        if(buildDir == null || !buildDir.isPrefixOf(testOutputLocation)) {
          ignorablePaths.add(testOutputLocation);
        }
      }
      return ignorablePaths;
    }
    return Collections.emptyList();
  }

  /**
   * Used to calculate the workspace dependencies of the given project in order to get build deltas for them next time
   * again.
   *
   * @param project
   * @return
   */
  private IProject[] getProjectDependencies(IProject project, IProgressMonitor monitor) {
    IMavenProjectFacade facade = projectManager.getProject(project);
    if(facade != null) {
      try {
        MavenProject mavenProject = facade.getMavenProject(monitor);
        List<Dependency> dependencies = mavenProject.getDependencies();
        if(dependencies != null) {
          List<IProject> result = new ArrayList<>(dependencies.size());
          for(Dependency dep : dependencies) {
            MavenProjectFacade depFacade = projectManager.getMavenProject(dep.getGroupId(), dep.getArtifactId(),
                dep.getVersion());
            if(depFacade != null) {
              result.add(depFacade.getProject());
            }
          }
          return result.toArray(new IProject[result.size()]);
        }
      } catch(CoreException ex) {
        log.error("Unable to get maven project for {}: {}", project.getName(), ex.getMessage(), ex);
      }
    }
    return null;
  }

  protected void clean(final IProgressMonitor monitor) throws CoreException {
    log.debug("Cleaning project {}", getProject().getName()); //$NON-NLS-1$
    final long start = System.currentTimeMillis();

    try {
      methodClean.execute(CLEAN_BUILD, Collections.<String, String> emptyMap(), monitor);
    } finally {
      log.debug("Cleaned project {} in {} ms", getProject().getName(), System.currentTimeMillis() - start); //$NON-NLS-1$
    }
  }

  private static final List<BuildDebugHook> debugHooks = new ArrayList<BuildDebugHook>();

  public static void addDebugHook(BuildDebugHook hook) {
    synchronized(debugHooks) {
      for(BuildDebugHook other : debugHooks) {
        if(other == hook) {
          return;
        }
      }
      debugHooks.add(hook);
    }
  }

  public static void removeDebugHook(BuildDebugHook hook) {
    synchronized(debugHooks) {
      ListIterator<BuildDebugHook> iter = debugHooks.listIterator();
      while(iter.hasNext()) {
        if(iter.next() == hook) {
          iter.remove();
          break;
        }
      }
    }
  }

  public static Collection<BuildDebugHook> getDebugHooks() {
    synchronized(debugHooks) {
      return new ArrayList<BuildDebugHook>(debugHooks);
    }
  }

  public ISchedulingRule getRule(int kind, Map<String, String> args) {
    if(MavenPlugin.getMavenConfiguration().buildWithNullSchedulingRule()) {
      return null;
    }
    return super.getRule(kind, args);
  }
}
