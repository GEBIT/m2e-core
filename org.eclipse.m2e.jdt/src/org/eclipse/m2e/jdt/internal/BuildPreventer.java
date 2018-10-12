
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
 * <p>
 * This is a builder that shall be placed in front of the Java builder in the .project configuration file. It will check
 * if the project has the java nature and java builder. If both are there, but the .classpath file is missing, the
 * builder will add an error marker to the project and cancel the build. This prevents the java builder from building
 * the project into the default bin/-folder and thus preventing other tools like integrity or compasscript from parsing
 * files in the bin-folder (which is not configured to be an output folder, so cannot be ignored).
 * </p>
 *
 * <pre>
 * BIG NOTE:
 * Unfortunately this has the side effect that not only the currently building project will be aborted, but the entire
 * workspace build! Use the de.gebit.maven.m2e plugin to visualize such cases.
 * </pre>
 *
 * To make this visible to the user, all other projects that were prevented from building get an additional "folloup"
 * marker that points to the project causing this.
 * <p>
 * Implementation-wise this is a little tricky: A new instance of this class will be created for every project that is
 * configured with this builder. If any such instance will prevent the build because of a missing .classpath file, it
 * will look up <b>all</b> the followup markers <b>of all other projects</b> in the workspace, delete those that are not
 * needed anymore, update those that need updating and create any missing ones.
 * </p>
 * <p>
 * When no instance will abort the build, <b>all "affected" instances</b> will look up and delete all such markers <b>of
 * all projects</b>. "Affected" means, any instance whose project has such a marker itself. The reason for this is that
 * there is no sensible way to share information about a whole workspace-build across instances. However, as soon as the
 * first instance removed all markers, subsequent instances should not do much, simply because there are no more
 * markers. Additionally, no instance can rely on another one doing the job or being availale at all.
 * </p>
 */
public class BuildPreventer extends IncrementalProjectBuilder {

  /**
   * The ID of this builder.
   */
  public static final String BUILDER_ID = "org.eclipse.m2e.jdt.buildpreventer";

  /**
   * The ID of the marker causing the entire workspace build to abort, due to missing .classpath file.
   */
  public static final String ERROR_MARKER_ID = "org.eclipse.m2e.jdt.classpathmissing";

  /**
   * The ID of the marker added on all projects that were not built because an {@link #ERROR_MARKER_ID} caused the build
   * to be aborted.
   */
  public static final String FOLLOWUP_ERROR_MARKER_ID = "org.eclipse.m2e.jdt.buildprevented_followup";

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

      // optimization: let only those builders remove markers that have an error marker or followup marker themselves
      if(tempOldErrorMarkers.get(tempProject) != null || tempOldFollowupMarkers.get(tempProject) != null) {
        removeAllMarkers(tempOldErrorMarkers);
        removeAllMarkers(tempOldFollowupMarkers);
      }
    } catch(CoreException ex) {
      // error accessing the project description, let the other builders continue
    }
    return new IProject[] {tempProject};
  }

  /**
   * @return <code>true</code> if there are changes in the delta that make us need to check for build prevention
   */
  private boolean needsBuild() {
    final boolean[] tempResult = new boolean[1];
    IResourceDelta tempDelta = getDelta(getProject());
    if(tempDelta == null) { // e.g. a "Clean" build => force building
      return true;
    }
    try {
      tempDelta.accept(new IResourceDeltaVisitor() {

        public boolean visit(IResourceDelta aDelta) throws CoreException {
          int tempKind = aDelta.getKind();
          if((tempKind & (IResourceDelta.ADDED | IResourceDelta.REMOVED)) != 0) {
            tempResult[0] = true;
            return false;
          }

          int tempFlags = aDelta.getFlags();
          if(tempFlags == 0) {
            return true;
          }
          if(tempFlags != IResourceDelta.MARKERS) {
            tempResult[0] = true;
            return false;
          }
          return true;
        }
      });
    } catch(CoreException ex) {
      log("Error analyzing build delta.", ex);
    }

    return tempResult[0];
  }

  /**
   * Removes all the given markers.
   *
   * @param someMarkers the markers to remove
   */
  private void removeAllMarkers(Map<IProject, IMarker> someMarkers) {
    if(someMarkers.isEmpty()) { // to simplify debugging
      return;
    }

    for(IMarker tempMarker : someMarkers.values()) {
      try {
        tempMarker.delete();
      } catch(CoreException ex) {
        log("Error removing build preventer error marker on " + tempMarker.getResource().getFullPath(), ex);
      }
    }
  }

  /**
   * Locates all markers of the given ID on all projects in the workspace
   *
   * @param aMarkerId the ID of the markers to locate
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
   * This method does the following:
   * <ol>
   * <li>it immediately cancels the progress monitor, so that no further concurrent builders may be invoked</li>
   * <li>it adds an {@link #ERROR_MARKER_ID error marker} to the given project (if not available yet)</li>
   * <li>it removes all other existing {@link #ERROR_MARKER_ID error markers} on other projects</li>
   * <li>it ensures that followup markers will exist on exactly those projects that are not built during this cycle</li>
   * </ol>
   *
   * @param aProject the project this builder runs on
   * @param someOldErrorMarkers all error markers existing prior to this build
   * @param someOldFollowupMarkers all followup markers existing prior to this build
   * @param aMonitor the monitor to cancel, causing the entire workspace build to be canceled
   * @throws CoreException
   */
  private void preventBuild(IProject aProject, Map<IProject, IMarker> someOldErrorMarkers,
      Map<IProject, IMarker> someOldFollowupMarkers, IProgressMonitor aMonitor) {

    aMonitor.setCanceled(true);

    // first, remove any old *error* marker (usually just one)
    try {
      boolean tempAddNewErrorMarker = true;
      for(IMarker tempMarker : someOldErrorMarkers.values()) {
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
   * Ensures that followup markers
   * <ul>
   * <li>are updated on all projects where they are not up to date</li>
   * <li>are created on all projects that need, but lack one</li>
   * <li>are removed on all projects not needing one anymore</li>
   * </ul>
   * on all projects that have not been built. If the projects already have a marker, it
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
   * Adds a persistent error marker to the given project.
   *
   * @param aProject the project to add the marker to.
   * @see #ERROR_MARKER_ID
   * @throws CoreException
   */
  public void addOriginErrorTo(IProject aProject) throws CoreException {
    addErrorTo(aProject, ERROR_MARKER_ID,
        ".classpath file missing, this and other projects will not be built. Alt-F5 on this project may fix it.",
        getMarkerLocationValueFor(aProject), IMarker.PRIORITY_HIGH);
  }

  /**
   * Adds a persistent followup marker to the given file.
   *
   * @param aProject the project to add the marker to.
   * @param anAbortedProject the project that caused the build to be aborted
   * @see #FOLLOWUP_ERROR_MARKER_ID
   * @throws CoreException
   */
  public void addFollowupErrorTo(IProject aProject, IProject anAbortedProject) throws CoreException {
    addErrorTo(aProject, FOLLOWUP_ERROR_MARKER_ID, getFollowupMarkerMessageFor(anAbortedProject),
        getMarkerLocationValueFor(anAbortedProject), IMarker.PRIORITY_HIGH);
  }

  /**
   * Returns the message for a followup marker, pointing to the project that aborted the build.
   *
   * @param anAbortedProject the project that caused the build to be aborted
   */
  private String getFollowupMarkerMessageFor(IProject anAbortedProject) {
    return "Project was not built because of project " + anAbortedProject.getName();
  }

  /**
   * Adds a persistent marker to the given file.
   *
   * @param aLocation
   * @param aProject the project to add the marker to.
   * @throws CoreException
   */
  public void addErrorTo(IProject aProject, String aMarkerId, String aMessage, String aLocation, int aPriority)
      throws CoreException {
    if(!aProject.isAccessible()) {
      return;
    }
    createMarkerFor(aProject, aMarkerId, aMessage, aLocation, aPriority);
  }

  /**
   * Creates an error marker for the given project.
   *
   * @param aProject
   * @param aPriority
   * @param aMessage
   * @param aMarkerId
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
   * Updates the attributes of an existing marker.
   * 
   * @param aBadExistingMarker
   * @param aNewAbortedProject
   * @throws CoreException
   */
  private void updateFollowupError(IMarker aBadExistingMarker, IProject aNewAbortedProject) throws CoreException {
    aBadExistingMarker.setAttributes(new String[] {IMarker.MESSAGE, IMarker.LOCATION},
        new Object[] {getFollowupMarkerMessageFor(aNewAbortedProject), getMarkerLocationValueFor(aNewAbortedProject)});
  }

  /**
   * Checks whether the given marker is already up to date regarding the project that aborted the build. Returns
   * <code>false</code> if the project needs to be {@link #updateFollowupError(IMarker, IProject) updated}
   * 
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

  /**
   * Returns the value for the given project to be set for the IMarker.LOCATION attribute.
   * 
   * @param anAbortedProject
   */
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
}
