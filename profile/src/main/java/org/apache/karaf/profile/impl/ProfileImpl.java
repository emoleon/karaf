/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.profile.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.apache.karaf.profile.Profile;

import static org.apache.karaf.profile.impl.Utils.assertNotNull;
import static org.apache.karaf.profile.impl.Utils.assertTrue;


/**
 * This immutable profile implementation.
 */
final class ProfileImpl implements Profile {

    private static final Pattern ALLOWED_PROFILE_NAMES_PATTERN = Pattern.compile("^[A-Za-z0-9]+[\\.A-Za-z0-9_-]*$");

    private final String profileId;
    private final Map<String, String> attributes;
    private final List<String> parents = new ArrayList<>();
    private final Map<String, byte[]> fileConfigurations = new HashMap<>();
    private final Map<String, Map<String, String>> configurations = new HashMap<>();
    private final boolean isOverlay;
    private int hash;

    // Only the {@link ProfileBuilder} should construct this
    ProfileImpl(String profileId, List<String> parents, Map<String, byte[]> fileConfigs, boolean isOverlay) {

        assertNotNull(profileId, "profileId is null");
        assertNotNull(parents, "parents is null");
        assertNotNull(fileConfigs, "fileConfigs is null");
        assertTrue(ALLOWED_PROFILE_NAMES_PATTERN.matcher(profileId).matches(), "Profile id '" + profileId + "' is invalid. Profile id must be: lower-case letters, numbers, and . _ or - characters");

        this.profileId = profileId;
        this.isOverlay = isOverlay;

        // Parents
        this.parents.addAll(parents);

        // File configurations and derived configurations
        for (Entry<String, byte[]> entry : fileConfigs.entrySet()) {
            String fileKey = entry.getKey();
            byte[] bytes = entry.getValue();
            fileConfigurations.put(fileKey, bytes);
            if (fileKey.endsWith(Profile.PROPERTIES_SUFFIX)) {
                String pid = fileKey.substring(0, fileKey.indexOf(Profile.PROPERTIES_SUFFIX));
                configurations.put(pid, Collections.unmodifiableMap(Utils.toProperties(bytes)));
            }
        }

        // Attributes are agent configuration with prefix 'attribute.'
        attributes = getPrefixedMap(ATTRIBUTE_PREFIX);
    }

    public String getId() {
        return profileId;
    }

    @Override
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public Map<String, String> getConfig() {
        return getPrefixedMap(CONFIG_PREFIX);
    }

    @Override
    public Map<String, String> getSystem() {
        return getPrefixedMap(SYSTEM_PREFIX);
    }

    private Map<String, String> getPrefixedMap(String prefix) {
        Map<String, String> map = new HashMap<>();
        Map<String, String> agentConfig = configurations.get(Profile.INTERNAL_PID);
        if (agentConfig != null) {
            int prefixLength = prefix.length();
            for (Entry<String, String> entry : agentConfig.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(prefix)) {
                    map.put(key.substring(prefixLength), entry.getValue());
                }
            }
        }
        return map;
    }

    @Override
    public List<String> getLibraries() {
        return getContainerConfigList(ConfigListType.LIBRARIES);
    }

    @Override
    public List<String> getEndorsedLibraries() {
        return getContainerConfigList(ConfigListType.ENDORSED);
    }

    @Override
    public List<String> getExtensionLibraries() {
        return getContainerConfigList(ConfigListType.EXTENSIONS);
    }

    @Override
    public List<String> getBootLibraries() {
        return getContainerConfigList(ConfigListType.BOOT);
    }

    @Override
    public List<String> getBundles() {
        return getContainerConfigList(ConfigListType.BUNDLES);
    }

    @Override
    public List<String> getFeatures() {
        return getContainerConfigList(ConfigListType.FEATURES);
    }

    @Override
    public List<String> getRepositories() {
        return getContainerConfigList(ConfigListType.REPOSITORIES);
    }

    @Override
    public List<String> getOverrides() {
        return getContainerConfigList(ConfigListType.OVERRIDES);
    }

    @Override
    public List<String> getOptionals() {
        return getContainerConfigList(ConfigListType.OPTIONALS);
    }

    @Override
    public List<String> getParentIds() {
        return Collections.unmodifiableList(parents);
    }

    @Override
    public boolean isAbstract() {
        return Boolean.parseBoolean(getAttributes().get(ABSTRACT));
    }

    @Override
    public boolean isHidden() {
        return Boolean.parseBoolean(getAttributes().get(HIDDEN));
    }

    public boolean isOverlay() {
        return isOverlay;
    }

    @Override
    public Map<String, byte[]> getFileConfigurations() {
        return Collections.unmodifiableMap(fileConfigurations);
    }

    @Override
    public Set<String> getConfigurationFileNames() {
        return Collections.unmodifiableSet(fileConfigurations.keySet());
    }

    @Override
    public byte[] getFileConfiguration(String fileName) {
        return fileConfigurations.get(fileName);
    }

    public Map<String, Map<String, String>> getConfigurations() {
        return Collections.unmodifiableMap(configurations);
    }

    @Override
    public Map<String, String> getConfiguration(String pid) {
        Map<String, String> config = configurations.get(pid);
        config = config != null ? config : Collections.<String, String> emptyMap();
        return Collections.unmodifiableMap(config);
    }

    private List<String> getContainerConfigList(ConfigListType type) {
        Map<String, String> containerProps = getConfiguration(Profile.INTERNAL_PID);
        List<String> rc = new ArrayList<>();
        String prefix = type + ".";
        for (Map.Entry<String, String> e : containerProps.entrySet()) {
            if ((e.getKey()).startsWith(prefix)) {
                rc.add(e.getValue());
            }
        }
        return rc;
    }

    @Override
    public int hashCode() {
        if (hash == 0) {
            CRC32 crc = new CRC32();
            crc.update(profileId.getBytes());
            List<String> keys = new ArrayList<>(fileConfigurations.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                crc.update(key.getBytes());
                crc.update(fileConfigurations.get(key));
            }
            hash = (int) crc.getValue();
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProfileImpl)) return false;
        ProfileImpl other = (ProfileImpl) obj;

        // Equality based on identity
        return profileId.equals(other.profileId)
                && fileConfigurations.equals(other.fileConfigurations);
    }

    @Override
    public String toString() {
        return "Profile[id=" + profileId + ",attrs=" + getAttributes() + "]";
    }

    enum ConfigListType {
        BUNDLES("bundle"),
        FEATURES("feature"),
        ENDORSED("endorsed"),
        EXTENSIONS("extension"),
        BOOT("boot"),
        LIBRARIES("library"),
        OPTIONALS("optional"),
        OVERRIDES("override"),
        REPOSITORIES("repository");

        private String value;

        private ConfigListType(String value) {
            this.value = value;
        }

        public String toString() {
            return value;
        }
    }
}
