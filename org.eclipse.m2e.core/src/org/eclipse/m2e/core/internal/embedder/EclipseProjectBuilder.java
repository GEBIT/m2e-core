
package org.eclipse.m2e.core.internal.embedder;

import java.util.HashMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

import org.apache.maven.model.building.ModelCache;
import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.NotDefaultModelCache;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;

import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.project.registry.ProjectRegistryManager;
import org.eclipse.m2e.core.project.IMavenProjectFacade;


/**
 */
@Component(role = ProjectBuilder.class)
public class EclipseProjectBuilder extends DefaultProjectBuilder {
  @Requirement
  protected Logger logger;

  protected org.apache.maven.project.DefaultProjectBuilder.InternalConfig createInternalConfig(
      ProjectBuildingRequest request, ModelCache modelCache) {
      return new InternalConfig(request);
    }
  
    class InternalConfig extends DefaultProjectBuilder.InternalConfig {
      public InternalConfig(ProjectBuildingRequest request) {
      super(request, new NotDefaultModelCache(request.getRepositorySession()), new ProjectCacheMap());
      }
    }

  class ProjectCacheMap extends HashMap<String, MavenProject> {
    public MavenProject get(Object key) {
      String[] gav = ((String) key).split(":");
      ProjectRegistryManager tempProjectRegistryManager = MavenPluginActivator.getDefault()
          .getMavenProjectManagerImpl();
      IMavenProjectFacade projectFacade = tempProjectRegistryManager.getMavenProject(gav[0], gav[1], gav[2]);
      MavenProject mavenProject = null;
      if(projectFacade != null) {
        try {
          // use and cache an instance if it's a workspace project
          mavenProject = projectFacade.getMavenProject(new NullProgressMonitor());
        } catch(CoreException ex) {
          logger.error(ex.getMessage(), ex);
        }
      }
//      if(mavenProject == null) {
//        Map<MavenProjectFacade, MavenProject> contextProjects = tempProjectRegistryManager.getContextProjects();
//        for(Map.Entry<MavenProjectFacade, MavenProject> entry : contextProjects.entrySet()) {
//          ArtifactKey projectArtifactKey = entry.getKey().getArtifactKey();
//          if(projectArtifactKey.getGroupId().equals(gav[0]) && projectArtifactKey.getArtifactId().equals(gav[1])
//              && projectArtifactKey.getVersion().equals(gav[2])) {
//            mavenProject = entry.getValue();
//          }
//        }
//      }
      if(mavenProject == null) {
        mavenProject = super.get(key);
      }
      return mavenProject;
    }
  }
}
