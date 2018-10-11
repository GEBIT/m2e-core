
package org.eclipse.m2e.jdt.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.internal.resources.Workspace;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;


/**
 * This is a builder that shall be placed in front of the Java builder in the .project configuration file. It will check
 * if the project has the java nature and java builder. If both are there, but the .classpath file is missing, the
 * builder will add an error marker to the project and cancel the build. This prevents the java builder from building
 * the project into the default bin/-folder and thus preventing other tools like integrity or compasscript from parsing
 * files in the bin-folder (which is not configured to be an output folder, so cannot be ignored).
 *
 * <pre>
 * BIG NOTE:
 * Unfortunately this has the side effect that not only the currently building project will be aborted, but the entire
 * workspace build! Use the de.gebit.maven.m2e plugin to visualize such cases.
 * </pre>
 */
public class BuildPreventer extends IncrementalProjectBuilder {

  public static final String BUILDER_ID = "org.eclipse.m2e.jdt.buildpreventer";

  public static final String ERROR_MARKER_ID = "org.eclipse.m2e.jdt.classpathmissing";

  private static final String FOLLOWUP_ERROR_MARKER_ID = "org.eclipse.m2e.jdt.buildprevented_followup";

  public BuildPreventer() {
  }

  @Override
  protected IProject[] build(int aKind, Map<String, String> args, IProgressMonitor aMonitor) throws CoreException {
    IProject tempProject = getProject();
    if(!needsBuild()) {
      return new IProject[] {tempProject};
    }

    try {
      Map<IProject, IMarker> tempOldErrorMarkers = findAllMarkers(ERROR_MARKER_ID);
      Map<IProject, IMarker> tempOldFollowupMarkers = findAllMarkers(FOLLOWUP_ERROR_MARKER_ID);

      IProjectDescription tempDescription = tempProject.getDescription();
      if(tempDescription != null && hasJavaNature(tempDescription) && hasJavaBuilder(tempDescription)
          && !hasClasspathFile(tempProject) && hasPomFile(tempProject)) {

        preventBuild(tempProject, tempOldErrorMarkers, tempOldFollowupMarkers, aMonitor);
        forgetLastBuiltState();
        return new IProject[] {tempProject};
      }
      removeAllMarkers(tempOldErrorMarkers);
      removeAllMarkers(tempOldFollowupMarkers);
    } catch(CoreException ex) {
      // error accessing the project description, let the other builders continue
    }
    return new IProject[] {tempProject};
  }

  /**
   * @return
   */
  private boolean needsBuild() {
    final boolean[] tempResult = new boolean[1];
    IResourceDelta tempDelta = getDelta(getProject());
    if(tempDelta == null) {
      return true;
    }
    try {
      tempDelta.accept(new IResourceDeltaVisitor() {

        public boolean visit(IResourceDelta aDelta) throws CoreException {
          if(aDelta.getFlags() != IResourceDelta.MARKERS) {
            tempResult[0] = true;
            return false;
          }
          return true;
        }
      });
    } catch(CoreException ex) {
      // TODO Auto-generated catch block
      log("Error analyzing build delta.", ex);
    }

    return tempResult[0];
  }

  /**
   * @param errorMarkerId
   */
  private void removeAllMarkers(Map<IProject, IMarker> someMarkers) {
    for(IMarker tempMarker : someMarkers.values()) {
      try {
        tempMarker.delete();
      } catch(CoreException ex) {
        log("Error removing build preventer error marker on " + tempMarker.getResource().getFullPath(), ex);
      }
    }
  }

  /**
   * @param errorMarkerId
   * @return
   */
  private Map<IProject, IMarker> findAllMarkers(String aMarkerId) {
    try {
      IWorkspaceRoot tempRoot = ResourcesPlugin.getWorkspace().getRoot();
      IMarker[] tempMarkers = tempRoot.findMarkers(aMarkerId, false, IResource.DEPTH_ONE); // all projects
      if(tempMarkers != null && tempMarkers.length > 0) {
        Map<IProject, IMarker> tempResult = new HashMap<>(tempMarkers.length);
        for(IMarker tempMarker : tempMarkers) {
          tempResult.put(tempMarker.getResource().getProject(), tempMarker);
        }
        return tempResult;
      }
    } catch(CoreException ex) {
      log("Error locating build preventer error markers.", ex);
    }
    return Collections.emptyMap();
  }

  /**
   * Adds an error marker to the project and cancels the build.
   *
   * @param aProject
   * @param tempOldErrorMarkers
   * @param someOldFollowupMarkers
   * @param aMonitor
   * @throws CoreException
   */
  private void preventBuild(IProject aProject, Map<IProject, IMarker> tempOldErrorMarkers,
      Map<IProject, IMarker> someOldFollowupMarkers, IProgressMonitor aMonitor) {

    aMonitor.setCanceled(true);

    // first, remove any old *error* marker (usually just one)
    try {
      boolean tempAddNewErrorMarker = true;
      for(IMarker tempMarker : tempOldErrorMarkers.values()) {
        if(aProject.equals(tempMarker.getResource())) {
          tempAddNewErrorMarker = false;
        } else {
          tempMarker.delete();
        }
      }
      if(tempAddNewErrorMarker) {
        addOriginErrorTo(aProject);
      }
    } catch(CoreException ex) {
      log("Error adding/updating/deleting error markers due to build problem at " + aProject.getName(), ex);
    }

    // and then create the follow up markers on projects that were also prevented to build
    createFollowupMarkers(aProject, someOldFollowupMarkers);
  }

  /**
   * Creates followup markers on all projects that have not been built. If the projects already have a marker, it
   *
   * @param anAbortedProject
   * @param someOldFollowupMarkers
   */
  private void createFollowupMarkers(IProject anAbortedProject, Map<IProject, IMarker> someOldFollowupMarkers) {
    List<IProject> tempUnbuiltProjects = getUnbuiltProjects();

    Map<IProject, IMarker> tempMarkersToRemove = new HashMap<>(someOldFollowupMarkers);
    for(IProject tempProject : tempUnbuiltProjects) {
      try {
        IMarker tempExistingMarker = tempMarkersToRemove.get(tempProject);
        if(isCorrectFollowupError(tempExistingMarker, anAbortedProject)) {
          tempMarkersToRemove.remove(tempProject);
          continue;
        }
        if(tempExistingMarker != null) {
          updateFollowupError(tempExistingMarker, anAbortedProject);
          tempMarkersToRemove.remove(tempProject);
        } else {
          addFollowupErrorTo(tempProject, anAbortedProject);
        }
      } catch(CoreException ex) {
        log("Problem finding/creating/updating marker at " + tempProject.getName(), ex);
      }
    }
    removeAllMarkers(tempMarkersToRemove);
  }

  /**
   * Logs the given message and exception.
   *
   * @param aString
   * @param aEx
   */
  private void log(String aMessage, Throwable ex) {
    System.out.println(aMessage);
    ex.printStackTrace();
  }

  /**
   * Returns <code>true</code> if the given project has the java nature
   *
   * @param aProject
   * @return
   * @throws CoreException
   */
  private boolean hasJavaNature(IProjectDescription aProjectDescription) {
    return aProjectDescription.hasNature(JavaCore.NATURE_ID);
  }

  /**
   * Returns <code>true</code> if the given project has the java builder configured
   *
   * @param aProject
   * @return
   * @throws CoreException
   */
  private boolean hasJavaBuilder(IProjectDescription aProjectDescription) {
    ICommand[] tempBuildSpec = aProjectDescription.getBuildSpec();
    for(ICommand tempCommand : tempBuildSpec) {
      if(JavaCore.BUILDER_ID.equals(tempCommand.getBuilderName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns <code>true</code> if the given project has an existing .classpath file
   *
   * @param aProject
   * @return
   */
  private boolean hasClasspathFile(IProject aProject) {
    IFile tempFile = aProject.getFile(".classpath");
    return tempFile.exists();
  }

  /**
   * Returns <code>true</code> if the given project has an existing pom.xml file
   *
   * @param aProject
   * @return
   */
  private boolean hasPomFile(IProject aProject) {
    IFile tempFile = aProject.getFile("pom.xml");
    return tempFile.exists();
  }

  /**
   * Adds a persistent syntax error marker to the given project.
   *
   * @param aFile the file to add the marker to.
   * @throws CoreException
   */
  public void addOriginErrorTo(IProject aProject) throws CoreException {
    addErrorTo(aProject, ERROR_MARKER_ID,
        ".classpath file missing, this and other projects will not be built. Alt-F5 on this project may fix it.",
        getMarkerLocationValueFor(aProject), IMarker.PRIORITY_HIGH);
  }

  /**
   * Adds a persistent syntax error marker to the given file.
   *
   * @param someOldFollowupMarkers
   * @param aFile the file to add the marker to.
   * @throws CoreException
   */
  public void addFollowupErrorTo(IProject aProject, IProject anAbortedProject) throws CoreException {
    addErrorTo(aProject, FOLLOWUP_ERROR_MARKER_ID,
        "Project was not built because of project " + anAbortedProject.getName(),
        getMarkerLocationValueFor(anAbortedProject), IMarker.PRIORITY_HIGH);
  }

  /**
   * Adds a persistent syntax error marker to the given file.
   *
   * @param aLocation
   * @param aFile the file to add the marker to.
   * @throws CoreException
   */
  public void addErrorTo(IProject aProject, String aMarkerId, String aMessage, String aLocation, int aPriority)
      throws CoreException {
    if(!aProject.isAccessible()) {
      return;
    }

//    IMarker[] tempMarkers = aProject.findMarkers(aMarkerId, false, IResource.DEPTH_ZERO);
//    if(tempMarkers != null && tempMarkers.length > 0) {
//      return; // no need to add another one
//    }
    createMarkerFor(aProject, aMarkerId, aMessage, aLocation, aPriority);
  }

  /**
   * Creates an error marker for the given file.
   *
   * @param aPriority
   * @param aMessage
   * @param aMarkerId
   * @param aFile
   * @return
   * @throws CoreException
   */
  private IMarker createMarkerFor(IProject aProject, String aMarkerId, String aMessage, String aLocation, int aPriority)
      throws CoreException {
    IMarker tempMarker = aProject.createMarker(aMarkerId);
    Map<String, Object> tempAttributes = new HashMap<String, Object>();
    tempAttributes.put(IMarker.PRIORITY, aPriority);
    tempAttributes.put(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
    tempAttributes.put(IMarker.MESSAGE, aMessage);
    tempAttributes.put(IMarker.LOCATION, aLocation);
    tempMarker.setAttributes(tempAttributes);
    return tempMarker;
  }

  /**
   * @param tempBadExistingMarker
   * @param tempProject
   * @throws CoreException
   */
  private void updateFollowupError(IMarker aBadExistingMarker, IProject aNewAbortedProject) throws CoreException {
    aBadExistingMarker.setAttribute(IMarker.LOCATION, getMarkerLocationValueFor(aNewAbortedProject));
  }

  /**
   * @param aMarker the marker to check, or <code>null</code> if it does not exist
   * @param anAbortedProject
   * @return
   * @throws CoreException
   */
  private boolean isCorrectFollowupError(IMarker aMarker, IProject anAbortedProject) throws CoreException {
    if(aMarker != null) {
      String tempLocation = aMarker.getAttribute(IMarker.LOCATION, null);
      if(getMarkerLocationValueFor(anAbortedProject).equals(tempLocation)) {
        return true;
      }
    }
    return false;
  }

  private String getMarkerLocationValueFor(IProject anAbortedProject) {
    return anAbortedProject.getName();
  }

  /**
   * Returns the list of projects that have not been build during the current build cycle. This should only be called
   * when the build preventer is about to cancel the build. This builder's project is not included in the list.
   */
  private List<IProject> getUnbuiltProjects() {
    IWorkspace tempWorkspace = ResourcesPlugin.getWorkspace();
    if(tempWorkspace instanceof Workspace) {
      Workspace tempWS = (Workspace) tempWorkspace;
      IBuildConfiguration[] tempBuildOrder = tempWS.getBuildOrder();
      List<IProject> tempResult = new ArrayList<IProject>();
      for(IBuildConfiguration tempConfig : tempBuildOrder) {
        IProject tempProject = tempConfig.getProject();
        if(!hasBeenBuilt(tempProject) && !tempProject.equals(getProject())) {
          tempResult.add(tempProject);
        }
      }
      return tempResult;
    }
    return Collections.emptyList();
  }

  private void removeMarker(IProject aProject, String aMarkerId) {
    if(!aProject.isAccessible()) {
      return;
    }

    try {
      IMarker[] tempMarkers = aProject.findMarkers(aMarkerId, false, IResource.DEPTH_ZERO);
      if(tempMarkers == null || tempMarkers.length == 0) {
        return; // nothing to remove
      }
      for(IMarker tempMarker : tempMarkers) {
        tempMarker.delete();
      }
    } catch(CoreException ex) {
      log("Cannot remove error marker from: " + aProject.getName(), ex);
    }
  }
}
