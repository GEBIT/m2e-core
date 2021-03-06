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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
import org.eclipse.core.runtime.jobs.ISchedulingRule;

import org.codehaus.plexus.util.MatchPatterns;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.M2EUtils;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.builder.plexusbuildapi.PlexusBuildAPI;
import org.eclipse.m2e.core.internal.embedder.MavenExecutionContext;
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

  public static String PROP_FORCE_BUILD = "m2e.forceBuild"; //$NON-NLS-1$

  public static String PROP_ERROR_MARKER_COUNT = "m2e.errorMarkerCount"; //$NON-NLS-1$

  public static String PROP_MAVEN_MARKER_COUNT = "m2e.mavenMarkerCount"; //$NON-NLS-1$

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
      boolean needsBuild = false;
      try {
        needsBuild = isBuildNeeded(kind, monitor);
      } catch(CoreException ex) {
        methodBuild.addErrorMarker(getProject(), ex);
        throw ex;
      }
      if(needsBuild) { // this is outside the above try-catch, since the build handles error markers itself
        return methodBuild.execute(kind, args, monitor);
      }
      log.debug("Not building project {} because the resource changes only occurred in its output or ignored folders",
          getProject().getName());
      return getProjectDependencies(getProject(), monitor);
    } finally {
      log.debug("Built project {} in {} ms", getProject().getName(), System.currentTimeMillis() - start); //$NON-NLS-1$
    }
  }

  private Map<String, Object> getBuildContext() throws CoreException {
    Map<String, Object> contextState = (Map<String, Object>) getProject()
        .getSessionProperty(PlexusBuildAPI.BUILD_CONTEXT_KEY);
    if(contextState == null) {
      contextState = new HashMap<String, Object>();
      getProject().setSessionProperty(PlexusBuildAPI.BUILD_CONTEXT_KEY, contextState);
    }
    return contextState;
  }

  /**
   * @param kind
   * @return
   */
  private boolean isBuildNeeded(int buildKind, IProgressMonitor monitor) throws CoreException {
    IProject project = getProject();
    if(buildKind == FULL_BUILD || buildKind == CLEAN_BUILD) {
      return true;
    }

    // if there are error markers (probably compilation errors), do build if a change has been triggered in a dependant
    // project
    IMarker[] projectMarkers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

    // first pass: if we have a classpath error do NOT build (now)
    for(IMarker problem : projectMarkers) {
      if(problem.getType().startsWith("org.eclipse.jdt")) {
        // check for CAT_BUILDPATH, need to wait for other project first
        if(problem.getAttribute("categoryId", 0) == 10) {
          // cannot build due to other project -> ignore
          log.info("Marker " + problem.getType() + " severity "
              + problem.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) + " found: do not build now.");
          return false;
        }
      }
    }

    // if a build has been forced, do perform it in any case
    if(getBuildContext().get(PROP_FORCE_BUILD) != null) {
      getBuildContext().put(PROP_FORCE_BUILD, null);
      return true;
    }

    // second pass: if we have build errors that either may go away because we will generate something, or that will
    // go away as a relevant change in a project we depend on is detected, we will give it a try now. Otherwise we
    // will not build to prevent endless build loops.
    IResourceDelta projectDelta = getDelta(project);
    int relevantMarkerCount = 0;
    int mavenMarkerCount = 0;
    for(IMarker problem : projectMarkers) {
      if(IMavenConstants.MARKER_BUILD_PARTICIPANT_ID.equals(problem.getType())) {
        if(IMarker.SEVERITY_ERROR == problem.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
          ++mavenMarkerCount;
        }
      }
      if(problem.getType().startsWith("org.eclipse.jdt")) {
        if(IMarker.SEVERITY_ERROR == problem.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
          ++relevantMarkerCount;
        }
      }
    }
    Integer previousMarkerCount = (Integer) getBuildContext().get(PROP_ERROR_MARKER_COUNT);
    getBuildContext().put(PROP_ERROR_MARKER_COUNT, relevantMarkerCount > 0 ? relevantMarkerCount : null);
    if(mavenMarkerCount > 0) {
      return true;
    }
    if(previousMarkerCount == null && relevantMarkerCount > 0) {
      // we had no errors before -> maybe the maven build will generate something that will make them go away
    } else if(previousMarkerCount != null && previousMarkerCount.intValue() != relevantMarkerCount) {
      // the number of error markers has changed: If it goes to zero we want to run maven steps to produce the correct
      // output, and if it is changed give maven a chance to do something which will affect the error count. If it's 
      // not affected another build will not be attempted.
      return true;
    }

    if(projectDelta == null) {
      // if there is no delta -> build as we cannot tell and we're better safe than sorry (maybe intitial build)
      return true;
    }

    // then we would build if any resource outside the exclusions is modified
    return isBuildNeededDueToDelta(project, projectDelta, monitor);
  }

  /**
   * Check if a build is needed. It will consider a list of exclusions which are known to be irrelevant to the build (if
   * changed).
   * 
   * @param project the project to check for
   * @param projectDelta the delta for the given project, must not be <code>null</code>
   * @param monitor a monitor
   * @return <code>true</code> if the build for the project should proceed
   * @throws CoreException
   */
  private boolean isBuildNeededDueToDelta(IProject project, IResourceDelta projectDelta, IProgressMonitor monitor)
      throws CoreException {

    if(projectDelta.getAffectedChildren().length == 0) {
      // from the spec of getDelta(), we should not need to build, but in practice, we need to
      // build whenever a parent project changes, for which we do not get a delta, probably because
      // it is a different build
      if("true".equals(System.getProperty("m2e.noBuildOnEmptyDelta"))) {
        // configured to not build on empty delta. This might leave dependent projects with build errors, especially
        // if the delta is from a parent in which new sources were added (generated)
        return false;
      }
      if(projectDelta.getFlags() == 0 || (projectDelta.getFlags() & IResourceDelta.DESCRIPTION) != 0) {
        // affects project description, i.e. static project dependencies -> ignore
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
            if(resource.getProjectRelativePath().isPrefixOf(forceBuildPath)) {
              // must decide later
              return true;
            }
            if(forceBuildPath.isPrefixOf(resource.getProjectRelativePath())) {
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
    IMavenProjectFacade facade = projectManager.getProject(project);
    if(facade != null) {
      return facade.getIgnoredPathes();
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
