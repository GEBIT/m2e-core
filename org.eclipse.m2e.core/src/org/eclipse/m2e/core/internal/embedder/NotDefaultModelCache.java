/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.eclipse.m2e.core.internal.embedder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.apache.maven.model.building.ModelCache;
import org.eclipse.aether.RepositoryCache;
import org.eclipse.aether.RepositorySystemSession;

/**
 * Implementation of {@link ModelCache} which shares keys with org.apache.maven.repository.internal.DefaultModelCache
 * and caches the models in the {@link RepositoryCache} of the {@link RepositorySystemSession}.
 */
public class NotDefaultModelCache
    implements ModelCache
{
    private static Class DefaultModelCacheKey_class;
    private static Constructor DefaultModelCacheKey_new;

    static {
        try {
            DefaultModelCacheKey_class = Class.forName("org.apache.maven.repository.internal.DefaultModelCache$Key");
            DefaultModelCacheKey_new = DefaultModelCacheKey_class.getConstructor(String.class, String.class, String.class, String.class);
            DefaultModelCacheKey_new.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException exc) {
            throw new RuntimeException("Failed to access DefaultModelCache.Key", exc);
        }
    }

    private final RepositorySystemSession session;

    private final RepositoryCache cache;

    public NotDefaultModelCache( RepositorySystemSession session )
    {
        this.session = session;
        this.cache = session.getCache();
    }

    public Object get( String groupId, String artifactId, String version, String tag )
    {
        return cache.get( session, createKey( groupId, artifactId, version, tag ) );
    }

    public void put( String groupId, String artifactId, String version, String tag, Object data )
    {
        cache.put( session, createKey( groupId, artifactId, version, tag ), data );
    }

    static Object createKey( String groupId, String artifactId, String version, String tag ) {
        try {
            return DefaultModelCacheKey_new.newInstance(groupId, artifactId, version, tag);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException exc) {
            throw new RuntimeException("Failed to create DefaultModelCache.Key", exc);
		}
    }
}