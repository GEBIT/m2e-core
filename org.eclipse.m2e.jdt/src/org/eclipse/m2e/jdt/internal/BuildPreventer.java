
package org.eclipse.m2e.jdt.internal;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;


/**
 * This is a builder that shall be placed in front of the Java builder in the .project configuration file. It will check
 * if the project has the java nature and java builder. If both are there, but the .classpath file is missing, the
 * builder will add an error marker to the project and cancel the build. This prevents the java builder from building
 * the project into the default bin/-folder and thus preventing other tools like integrity or compasscript from parsing
 * files in the bin-folder (which is not configured to be an output folder, so cannot be ignored).
 */
public class BuildPreventer extends IncrementalProjectBuilder {

  public static final String BUILDER_ID = "org.eclipse.m2e.jdt.buildpreventer";

  public static final String ERROR_MARKER_ID = "org.eclipse.m2e.jdt.classpathmissing";

  public BuildPreventer() {
  }

  @Override
  protected IProject[] build(int aKind, Map<String, String> args, IProgressMonitor aMonitor) throws CoreException {
    IProject tempProject = getProject();
    try {
      IProjectDescription tempDescription = tempProject.getDescription();
      if(tempDescription != null && hasJavaNature(tempDescription) && hasJavaBuilder(tempDescription)
          && !hasClasspathFile(tempProject)) {

        preventBuild(tempProject, aMonitor);
        return null;
      }
      removeMarker(tempProject);
    } catch(CoreException ex) {
      // error accessing the project description, let the other builders continue
    }
    return null;
  }

  /**
   * Adds an error marker to the project and cancels the build.
   * 
   * @param aProject
   * @param aMonitor
   * @throws CoreException
   */
  private void preventBuild(IProject aProject, IProgressMonitor aMonitor) {
    addErrorTo(aProject);
    aMonitor.setCanceled(true);
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
   * Adds a persistent syntax error marker to the given file.
   * 
   * @param aFile the file to add the marker to.
   */
  public void addErrorTo(IProject aProject) {
    if(!aProject.isAccessible()) {
      return;
    }

    try {
      IMarker[] tempMarkers = aProject.findMarkers(ERROR_MARKER_ID, false, IResource.DEPTH_ZERO);
      if(tempMarkers != null && tempMarkers.length > 0) {
        return; // no need to add another one
      }
      createMarkerFor(aProject);

    } catch(CoreException ex) {
      log("Cannot add syntax error marker to: " + aProject.getName(), ex);
    }
  }

  /**
   * Creates an error marker for the given file.
   * 
   * @param aFile
   * @return
   * @throws CoreException
   */
  private IMarker createMarkerFor(IProject aProject) throws CoreException {
    IMarker tempMarker = aProject.createMarker(ERROR_MARKER_ID);
    Map<String, Object> tempAttributes = new HashMap<String, Object>();
    tempAttributes.put(IMarker.PRIORITY, IMarker.PRIORITY_HIGH);
    tempAttributes.put(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
    tempAttributes.put(IMarker.MESSAGE,
        ".classpath file missing, project will not be built although it has the java nature and builder");
    tempMarker.setAttributes(tempAttributes);
    return tempMarker;
  }

  private void removeMarker(IProject aProject) {
    if(!aProject.isAccessible()) {
      return;
    }

    try {
      IMarker[] tempMarkers = aProject.findMarkers(ERROR_MARKER_ID, false, IResource.DEPTH_ZERO);
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
