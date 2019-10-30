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

package org.eclipse.m2e.core.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import org.apache.maven.AbstractMavenLifecycleParticipant;

import org.eclipse.m2e.core.internal.archetype.ArchetypeCatalogFactory;
import org.eclipse.m2e.core.internal.builder.IIncrementalBuildFramework;
import org.eclipse.m2e.core.internal.project.ILifecycleParticipant;
import org.eclipse.m2e.core.project.IMavenProjectChangedListener;


/**
 * Extension reader
 * 
 * @author Eugene Kuleshov
 */
public class ExtensionReader {
  private static final Logger log = LoggerFactory.getLogger(ExtensionReader.class);

  public static final String EXTENSION_ARCHETYPES = IMavenConstants.PLUGIN_ID + ".archetypeCatalogs"; //$NON-NLS-1$

  public static final String EXTENSION_PROJECT_CHANGED_EVENT_LISTENERS = IMavenConstants.PLUGIN_ID
      + ".mavenProjectChangedListeners"; //$NON-NLS-1$

  public static final String EXTENSION_INCREMENTAL_BUILD_FRAMEWORKS = IMavenConstants.PLUGIN_ID
      + ".incrementalBuildFrameworks"; //$NON-NLS-1$

  public static final String EXTENSION_LIFECYCLE_PARTICIPANTS = IMavenConstants.PLUGIN_ID + ".lifecycleParticipants"; //$NON-NLS-1$

  private static final String ELEMENT_LOCAL_ARCHETYPE = "local"; //$NON-NLS-1$

  private static final String ELEMENT_REMOTE_ARCHETYPE = "remote"; //$NON-NLS-1$

  private static final String ATTR_NAME = "name"; //$NON-NLS-1$

  private static final String ATTR_URL = "url"; //$NON-NLS-1$

  private static final String ATTR_DESCRIPTION = "description"; //$NON-NLS-1$

  private static final String ELEMENT_LISTENER = "listener"; //$NON-NLS-1$

  public static List<ArchetypeCatalogFactory> readArchetypeExtensions() {
    List<ArchetypeCatalogFactory> archetypeCatalogs = new ArrayList<ArchetypeCatalogFactory>();

    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint archetypesExtensionPoint = registry.getExtensionPoint(EXTENSION_ARCHETYPES);
    if(archetypesExtensionPoint != null) {
      IExtension[] archetypesExtensions = archetypesExtensionPoint.getExtensions();
      for(IExtension extension : archetypesExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        IContributor contributor = extension.getContributor();
        for(IConfigurationElement element : elements) {
          ArchetypeCatalogFactory factory = readArchetypeCatalogs(element, contributor);
          // archetypeManager.addArchetypeCatalogFactory(factory);
          archetypeCatalogs.add(factory);
        }
      }
    }
    return archetypeCatalogs;
  }

  private static ArchetypeCatalogFactory readArchetypeCatalogs(IConfigurationElement element,
      IContributor contributor) {
    if(ELEMENT_LOCAL_ARCHETYPE.equals(element.getName())) {
      String name = element.getAttribute(ATTR_NAME);
      if(name != null) {
        Bundle[] bundles = Platform.getBundles(contributor.getName(), null);
        URL catalogUrl = null;
        for(int i = 0; i < bundles.length; i++ ) {
          Bundle bundle = bundles[i];
          catalogUrl = bundle.getEntry(name);
          if(catalogUrl != null) {
            String description = element.getAttribute(ATTR_DESCRIPTION);
            String url = catalogUrl.toString();
            return new ArchetypeCatalogFactory.LocalCatalogFactory(url, description, false);
          }
        }
        log.error("Unable to find Archetype catalog " + name + " in " + contributor.getName());
      }
    } else if(ELEMENT_REMOTE_ARCHETYPE.equals(element.getName())) {
      String url = element.getAttribute(ATTR_URL);
      if(url != null) {
        String description = element.getAttribute(ATTR_DESCRIPTION);
        return new ArchetypeCatalogFactory.RemoteCatalogFactory(url, description, false);
      }
    }
    return null;
  }

  public static List<IMavenProjectChangedListener> readProjectChangedEventListenerExtentions() {
    ArrayList<IMavenProjectChangedListener> listeners = new ArrayList<IMavenProjectChangedListener>();

    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint mappingsExtensionPoint = registry.getExtensionPoint(EXTENSION_PROJECT_CHANGED_EVENT_LISTENERS);
    if(mappingsExtensionPoint != null) {
      IExtension[] mappingsExtensions = mappingsExtensionPoint.getExtensions();
      for(IExtension extension : mappingsExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals(ELEMENT_LISTENER)) {
            try {
              listeners.add((IMavenProjectChangedListener) element.createExecutableExtension("class")); //$NON-NLS-1$
            } catch(CoreException ex) {
              log.error(ex.getMessage(), ex);
            }
          }
        }
      }
    }

    return listeners;
  }

  public static List<IIncrementalBuildFramework> readIncrementalBuildFrameworks() {
    ArrayList<IIncrementalBuildFramework> frameworks = new ArrayList<IIncrementalBuildFramework>();

    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint mappingsExtensionPoint = registry.getExtensionPoint(EXTENSION_INCREMENTAL_BUILD_FRAMEWORKS);
    if(mappingsExtensionPoint != null) {
      IExtension[] mappingsExtensions = mappingsExtensionPoint.getExtensions();
      for(IExtension extension : mappingsExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals("framework")) {
            try {
              frameworks.add((IIncrementalBuildFramework) element.createExecutableExtension("class")); //$NON-NLS-1$
            } catch(CoreException ex) {
              log.error(ex.getMessage(), ex);
            }
          }
        }
      }
    }

    return frameworks;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, ILifecycleParticipant> readLifecycleParticipants() {
    Map<String, ILifecycleParticipant> lifecycleParticipants = new HashMap<>();

    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint lifecycleParticipantsPoint = registry.getExtensionPoint(EXTENSION_LIFECYCLE_PARTICIPANTS);
    if(lifecycleParticipantsPoint != null) {
      IExtension[] mappingsExtensions = lifecycleParticipantsPoint.getExtensions();
      for(IExtension extension : mappingsExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        String hint = null;
        Class<? extends AbstractMavenLifecycleParticipant> impl = null;
        AbstractMavenLifecycleParticipant participant = null;
        for(IConfigurationElement element : elements) {
          if(element.getName().equals("participant")) {
            hint = element.getAttribute("hint");
            String implClass = element.getAttribute("class");
            if(implClass != null) {
              Bundle contributor = Platform.getBundle(element.getContributor().getName());
              try {
                impl = (Class<? extends AbstractMavenLifecycleParticipant>) contributor.loadClass(implClass);
              } catch(ClassNotFoundException ex) {
                log.error(ex.getMessage(), ex);
              }
            }
          }
        }
        if(hint != null) {
          LifecycleParticipant lifecycleParticipant = new LifecycleParticipant(hint, impl);
          lifecycleParticipants.put(hint, lifecycleParticipant);
        }
      }
    }

    return lifecycleParticipants;
  }

  private static class LifecycleParticipant implements ILifecycleParticipant {
    private String hint;

    private Class<? extends AbstractMavenLifecycleParticipant> impl;

    LifecycleParticipant(String hint, Class<? extends AbstractMavenLifecycleParticipant> impl) {
      this.hint = hint;
      this.impl = impl;
    }

    /* (non-Javadoc)
     * @see org.eclipse.m2e.core.internal.project.ILifecycleParticipant#getHint()
     */
    public String getHint() {
      return hint;
    }

    /* (non-Javadoc)
    * @see org.eclipse.m2e.core.internal.project.ILifecycleParticipant#getParticipant()
    */
    public AbstractMavenLifecycleParticipant getParticipant(AbstractMavenLifecycleParticipant mavenParticipant) {
      if(impl == null) {
        return mavenParticipant;
      }
      try {
        try {
          Constructor<? extends AbstractMavenLifecycleParticipant> constructor = impl
              .getConstructor(AbstractMavenLifecycleParticipant.class);
          return constructor.newInstance(mavenParticipant);
        } catch(NoSuchMethodException ex) {
          // try no-arg constructor
          try {
            Constructor<? extends AbstractMavenLifecycleParticipant> constructor = impl.getConstructor();
            return constructor.newInstance();
          } catch(NoSuchMethodException ex1) {
            log.error(ex1.getMessage(), ex1);
          }
        }
      } catch(SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException
          | InvocationTargetException ex) {
        log.error(ex.getMessage(), ex);
      }
      // by default use the maven instance
      return mavenParticipant;
    }
  }
}
