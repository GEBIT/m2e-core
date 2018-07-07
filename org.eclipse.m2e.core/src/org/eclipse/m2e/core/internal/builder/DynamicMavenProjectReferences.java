
package org.eclipse.m2e.core.internal.builder;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IDynamicReferenceProvider;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import org.eclipse.m2e.core.embedder.ArtifactRef;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.project.registry.MavenProjectFacade;
import org.eclipse.m2e.core.internal.project.registry.ProjectRegistryManager;
import org.eclipse.m2e.core.project.IMavenProjectFacade;


public class DynamicMavenProjectReferences implements IDynamicReferenceProvider {
  static final Logger log = LoggerFactory.getLogger(DynamicMavenProjectReferences.class);

  final ProjectRegistryManager projectManager = MavenPluginActivator.getDefault().getMavenProjectManagerImpl();

  @Override
  public List<IProject> getDependentProjects(IBuildConfiguration buildConfiguration) throws CoreException {
    IProject input = buildConfiguration.getProject();
    IMavenProjectFacade facade = projectManager.getProject(input);

    List<IProject> references = new ArrayList<>();
    if(facade != null || !input.hasNature("org.eclipse.jdt.core.javanature")) {
      for (ArtifactRef ref : facade.getMavenProjectArtifacts()) {
        MavenProjectFacade depFacade = projectManager.getMavenProject(ref.getGroupId(), ref.getArtifactId(),
            ref.getVersion());
        if(depFacade != null) {
          references.add(depFacade.getProject());
        }
      }
      if(facade.getParentArtifactKey() != null) {
        MavenProjectFacade depFacade = projectManager.getMavenProject(facade.getParentArtifactKey().getGroupId(),
            facade.getParentArtifactKey().getArtifactId(), facade.getParentArtifactKey().getVersion());
        if(depFacade != null) {
          references.add(depFacade.getProject());
        }
      }
    }

    return references;
  }
}
