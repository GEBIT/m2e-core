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

package org.apache.maven.project;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.maven.model.building.ModelCache;
import org.eclipse.aether.RepositoryCache;
import org.eclipse.aether.RepositorySystemSession;

/**
 * Copy of org.apache.maven.repository.internal.DefaultModelCache, which is package private
 */
public class NotDefaultModelCache
    implements ModelCache
{

    private final RepositorySystemSession session;

    private final RepositoryCache cache;
    
    private static final Map<Class, KeyAdapter> foreignKeyMap = new WeakHashMap<Class, KeyAdapter>();

    public NotDefaultModelCache( RepositorySystemSession session )
    {
        this.session = session;
        this.cache = session.getCache();
    }

    public Object get( String groupId, String artifactId, String version, String tag )
    {
        return cache.get( session, new Key( groupId, artifactId, version, tag ) );
    }

    public void put( String groupId, String artifactId, String version, String tag, Object data )
    {
        cache.put( session, new Key( groupId, artifactId, version, tag ), data );
    }

    static class Key
    {

        private final String groupId;

        private final String artifactId;

        private final String version;

        private final String tag;

        private final int hash;

        public Key( String groupId, String artifactId, String version, String tag )
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.tag = tag;

            int h = 17;
            h = h * 31 + this.groupId.hashCode();
            h = h * 31 + this.artifactId.hashCode();
            h = h * 31 + this.tag.hashCode();
            hash = h;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( null == obj || !getClass().equals( obj.getClass() ) )
            {
                // try with reflection
                KeyAdapter keyAdapter = null; 
                try {
                    if ( ! foreignKeyMap.containsKey( obj.getClass() ) ) {
                        keyAdapter = new KeyAdapter( obj.getClass() );
                        foreignKeyMap.put( obj.getClass(), keyAdapter );
                    }
                    keyAdapter = foreignKeyMap.get( obj.getClass() );
                    if ( keyAdapter == null ) {
                        return false;
                    }
                    return artifactId.equals( keyAdapter.getArtifactId( obj ) ) 
                            && groupId.equals( keyAdapter.getGroupId( obj ) )
                            && equalsVersion( version, keyAdapter.getVersion( obj ) ) 
                            && tag.equals( keyAdapter.getTag( obj ) );
                } catch (Exception exc) {
                    // then it obviously is not possible
                    foreignKeyMap.put( obj.getClass(), null );
                }
                return false;
            }

            Key that = (Key) obj;
            return artifactId.equals( that.artifactId ) && groupId.equals( that.groupId )
                && equalsVersion( version, that.version )
                && tag.equals( that.tag );
        }

        @Override
        public int hashCode()
        {
            return hash;
        }

        private boolean equalsVersion( String v1, String v2 ) {
            if ( v1.equals( v2 ) ) {
                return true;
            }
            int i1 = v1.indexOf( '$' );
            int i2 = v2.indexOf( '$' );
            if ( i1 >= 0 && i2 >= 0) {
                return v2.substring( 0, i2 ).equals( v1.substring( 0, i1 ) );
            } else if ( i1 >= 0 ) {
                return v2.startsWith( v1.substring( 0, i1 ) );
            } else if ( i2 >= 0 ) {
                return v1.startsWith( v2.substring( 0, i2 ) );
            } else {
                return false;
            }
        }
    }
    static class KeyAdapter {
        private Field groupIdField;

        private Field artifactIdField;

        private Field versionField;

        private Field tagField;

        KeyAdapter( Class keyClass ) throws NoSuchFieldException, SecurityException {
            artifactIdField = keyClass.getDeclaredField("artifactId");
            artifactIdField.setAccessible(true);

            groupIdField = keyClass.getDeclaredField("groupId");
            groupIdField.setAccessible(true);
            
            versionField = keyClass.getDeclaredField("version");
            versionField.setAccessible(true);
            
            tagField = keyClass.getDeclaredField("tag");
            tagField.setAccessible(true);
        }

        public String getArtifactId( Object key ) throws IllegalArgumentException, IllegalAccessException {
            return (String) artifactIdField.get( key ); 
        }
        
        public String getGroupId( Object key ) throws IllegalArgumentException, IllegalAccessException {
        	return (String) groupIdField.get( key ); 
        }
        
        public String getVersion( Object key ) throws IllegalArgumentException, IllegalAccessException {
        	return (String) versionField.get( key ); 
        }
        
        public String getTag( Object key ) throws IllegalArgumentException, IllegalAccessException {
        	return (String) tagField.get( key ); 
        }
    }
}
