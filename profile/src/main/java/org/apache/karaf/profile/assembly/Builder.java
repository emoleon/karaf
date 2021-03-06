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
package org.apache.karaf.profile.assembly;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.felix.utils.properties.InterpolationHelper;
import org.apache.felix.utils.properties.Properties;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.internal.download.DownloadCallback;
import org.apache.karaf.features.internal.download.DownloadManager;
import org.apache.karaf.features.internal.download.Downloader;
import org.apache.karaf.features.internal.download.StreamProvider;
import org.apache.karaf.features.internal.download.impl.DownloadManagerHelper;
import org.apache.karaf.features.internal.model.Bundle;
import org.apache.karaf.features.internal.model.Conditional;
import org.apache.karaf.features.internal.model.ConfigFile;
import org.apache.karaf.features.internal.model.Dependency;
import org.apache.karaf.features.internal.model.Feature;
import org.apache.karaf.features.internal.model.Features;
import org.apache.karaf.features.internal.model.JaxbUtil;
import org.apache.karaf.features.internal.repository.BaseRepository;
import org.apache.karaf.features.internal.resolver.ResourceBuilder;
import org.apache.karaf.features.internal.service.Deployer;
import org.apache.karaf.features.internal.util.MapUtils;
import org.apache.karaf.kar.internal.Kar;
import org.apache.karaf.profile.Profile;
import org.apache.karaf.profile.ProfileBuilder;
import org.apache.karaf.profile.impl.Profiles;
import org.apache.karaf.util.config.PropertiesLoader;
import org.apache.karaf.util.maven.Parser;
import org.ops4j.pax.url.mvn.MavenResolver;
import org.ops4j.pax.url.mvn.MavenResolvers;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.jar.JarFile.MANIFEST_NAME;

public class Builder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Builder.class);

    private static final String FEATURES_REPOSITORIES = "featuresRepositories";
    private static final String FEATURES_BOOT = "featuresBoot";

    public static enum Stage {
        Startup, Boot, Installed
    }
    
    static class RepositoryInfo {
        Stage stage;
        boolean addAll;
    }
    
    //
    // Input parameters
    //

    List<String> profilesUris = new ArrayList<>();
    boolean defaultAddAll = true;
    Stage defaultStage = Stage.Startup;
    Map<String, RepositoryInfo> kars = new LinkedHashMap<>();
    Map<String, Stage> profiles = new LinkedHashMap<>();
    Map<String, RepositoryInfo> repositories = new LinkedHashMap<>();
    Map<String, Stage> features = new LinkedHashMap<>();
    Map<String, Stage> bundles = new LinkedHashMap<>();
    String javase = "1.7";
    String environment = null;
    boolean useReferenceUrls;
    boolean use24SyntaxForStartup;
    boolean ignoreDependencyFlag;
    int defaultStartLevel = 50;
    Path homeDirectory;

    private ScheduledExecutorService executor;
    private DownloadManager manager;
    private Path etcDirectory;
    private Path systemDirectory;
    private Map<String, Profile> allProfiles;

    public static Builder newInstance() {
        return new Builder();
    }

    public Builder defaultStage(Stage stage) {
        this.defaultStage = stage;
        return this;
    }

    public Builder defaultAddAll(boolean addAll) {
        this.defaultAddAll = addAll;
        return this;
    }

    public Builder profilesUris(String... profilesUri) {
        Collections.addAll(this.profilesUris, profilesUri);
        return this;
    }

    public Builder kars(String... kars) {
        return kars(defaultStage, defaultAddAll, kars);
    }

    public Builder kars(boolean addAll, String... kars) {
        return kars(defaultStage, addAll, kars);
    }

    public Builder kars(Stage stage, boolean addAll, String... kars) {
        for (String kar : kars) {
            RepositoryInfo info = new RepositoryInfo();
            info.stage = stage;
            info.addAll = addAll;
            this.kars.put(kar, info);
        }
        return this;
    }

    public Builder repositories(String... repositories) {
        return repositories(defaultStage, defaultAddAll, repositories);
    }

    public Builder repositories(boolean addAll, String... repositories) {
        return repositories(defaultStage, addAll, repositories);
    }

    public Builder repositories(Stage stage, boolean addAll, String... repositories) {
        for (String repository : repositories) {
            RepositoryInfo info = new RepositoryInfo();
            info.stage = stage;
            info.addAll = addAll;
            this.repositories.put(repository, info);
        }
        return this;
    }

    public Builder features(String... features) {
        return features(defaultStage, features);
    }

    public Builder features(Stage stage, String... features) {
        for (String feature : features) {
            this.features.put(feature, stage);
        }
        return this;
    }

    public Builder bundles(String... bundles) {
        return bundles(defaultStage, bundles);
    }

    public Builder bundles(Stage stage, String... bundles) {
        for (String bundle : bundles) {
            this.bundles.put(bundle, stage);
        }
        return this;
    }

    public Builder profiles(String... profiles) {
        return profiles(defaultStage, profiles);
    }

    public Builder profiles(Stage stage, String... profiles) {
        for (String profile : profiles) {
            this.profiles.put(profile, stage);
        }
        return this;
    }

    public Builder homeDirectory(Path homeDirectory) {
        if (homeDirectory == null) {
            throw new IllegalArgumentException("homeDirectory is null");
        }
        this.homeDirectory = homeDirectory;
        return this;
    }

    public Builder javase(String javase) {
        if (javase == null) {
            throw new IllegalArgumentException("javase is null");
        }
        this.javase = javase;
        return this;
    }

    public Builder environment(String environment) {
        this.environment = environment;
        return this;
    }

    public Builder useReferenceUrls() {
        return useReferenceUrls(true);
    }

    public Builder useReferenceUrls(boolean useReferenceUrls) {
        this.useReferenceUrls = useReferenceUrls;
        return this;
    }

    public Builder use24SyntaxForStartup() {
        return use24SyntaxForStartup(true);
    }

    public Builder use24SyntaxForStartup(boolean use24SyntaxForStartup) {
        this.use24SyntaxForStartup = use24SyntaxForStartup;
        return this;
    }

    public Builder defaultStartLevel(int defaultStartLevel) {
        this.defaultStartLevel = defaultStartLevel;
        return this;
    }

    public Builder ignoreDependencyFlag() {
        return ignoreDependencyFlag(true);
    }

    public Builder ignoreDependencyFlag(boolean ignoreDependencyFlag) {
        this.ignoreDependencyFlag = ignoreDependencyFlag;
        return this;
    }

    public Builder staticFramework() {
        // TODO: load this from resources
        return staticFramework("4.0.0-SNAPSHOT");
    }

    public Builder staticFramework(String version) {
        return this.defaultStage(Stage.Startup)
                   .useReferenceUrls()
                   .kars(Stage.Startup, true, "mvn:org.apache.karaf.features/static/" + version + "/kar");
    }

    public void generateAssembly() throws Exception {
        if (javase == null) {
            throw new IllegalArgumentException("javase is not set");
        }
        if (homeDirectory == null) {
            throw new IllegalArgumentException("homeDirectory is not set");
        }
        try {
            doGenerateAssembly();
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private void doGenerateAssembly() throws Exception {
        systemDirectory = homeDirectory.resolve("system");
        etcDirectory = homeDirectory.resolve("etc");

        LOGGER.info("Generating karaf assembly: " + homeDirectory);

        //
        // Create download manager
        //
        Dictionary<String, String> props = new Hashtable<>();
        MavenResolver resolver = MavenResolvers.createMavenResolver(props, "org.ops4j.pax.url.mvn");
        executor = Executors.newScheduledThreadPool(8);
        manager = new CustomDownloadManager(resolver, executor);

        //
        // Unzip kars
        //
        LOGGER.info("Unzipping kars");
        Map<String, RepositoryInfo> repositories = new LinkedHashMap<>(this.repositories);
        Downloader downloader = manager.createDownloader();
        for (String kar : kars.keySet()) {
            downloader.download(kar, null);
        }
        downloader.await();
        for (String karUri : kars.keySet()) {
            Kar kar = new Kar(manager.getProviders().get(karUri).getFile().toURI());
            kar.extract(systemDirectory.toFile(), homeDirectory.toFile());
            RepositoryInfo info = kars.get(karUri);
            for (URI repositoryUri : kar.getFeatureRepos()) {
                repositories.put(repositoryUri.toString(), info);
            }
        }

        //
        // Propagate feature installation from repositories
        //
        Map<String, Stage> features = new LinkedHashMap<>(this.features);
        Map<String, Features> karRepositories = loadRepositories(manager, repositories.keySet(), false);
        for (String repo : repositories.keySet()) {
            RepositoryInfo info = repositories.get(repo);
            if (info.addAll) {
                for (Feature feature : karRepositories.get(repo).getFeature()) {
                    features.put(feature.getId(), info.stage);
                }
            }
        }

        //
        // Load profiles
        //
        LOGGER.info("Loading profiles");
        allProfiles = new HashMap<>();
        for (String profilesUri : profilesUris) {
            String uri = profilesUri;
            if (uri.startsWith("jar:") && uri.contains("!/")) {
                uri = uri.substring("jar:".length(), uri.indexOf("!/"));
            }
            if (!uri.startsWith("file:")) {
                downloader = manager.createDownloader();
                downloader.download(uri, null);
                downloader.await();
                StreamProvider provider = manager.getProviders().get(uri);
                profilesUri = profilesUri.replace(uri, provider.getFile().toURI().toString());
            }
            URI profileURI = URI.create(profilesUri);
            Path profilePath;
            try {
                profilePath = Paths.get(profileURI);
            } catch (FileSystemNotFoundException e) {
                // file system does not exist, try to create it
                FileSystem fs = FileSystems.newFileSystem(profileURI, new HashMap<String, Object>(), Builder.class.getClassLoader());
                profilePath = fs.provider().getPath(profileURI);
            }
            allProfiles.putAll(Profiles.loadProfiles(profilePath));
        }

        // Generate profiles
        Profile startupProfile = generateProfile(Stage.Startup, profiles, repositories, features, bundles);
        Profile bootProfile = generateProfile(Stage.Boot, profiles, repositories, features, bundles);
        Profile installedProfile = generateProfile(Stage.Installed, profiles, repositories, features, bundles);

        //
        // Compute overall profile
        //
        Profile overallProfile = ProfileBuilder.Factory.create(UUID.randomUUID().toString())
                .setParents(Arrays.asList(startupProfile.getId(), bootProfile.getId(), installedProfile.getId()))
                .getProfile();
        Profile overallOverlay = Profiles.getOverlay(overallProfile, allProfiles, environment);
        Profile overallEffective = Profiles.getEffective(overallOverlay, false);

        manager = new CustomDownloadManager(resolver, executor, overallEffective);

        Hashtable<String, String> agentProps = new Hashtable<>(overallEffective.getConfiguration("org.ops4j.pax.url.mvn"));
        final Map<String, String> properties = new HashMap<>();
        properties.put("karaf.default.repository", "system");
        InterpolationHelper.performSubstitution(agentProps, new InterpolationHelper.SubstitutionCallback() {
            @Override
            public String getValue(String key) {
                return properties.get(key);
            }
        }, false, false, true);

        //
        // Write config and system properties
        //
        Path configPropertiesPath = etcDirectory.resolve("config.properties");
        Properties configProperties = new Properties(configPropertiesPath.toFile());
        configProperties.putAll(overallEffective.getConfig());
        configProperties.save();

        Path systemPropertiesPath = etcDirectory.resolve("system.properties");
        Properties systemProperties = new Properties(systemPropertiesPath.toFile());
        systemProperties.putAll(overallEffective.getSystem());
        systemProperties.save();

        //
        // Download libraries
        //
        // TODO: handle karaf 2.x and 3.x libraries
        LOGGER.info("Downloading libraries");
        downloader = manager.createDownloader();
        downloadLibraries(downloader, overallEffective.getLibraries(), "lib");
        downloadLibraries(downloader, overallEffective.getEndorsedLibraries(), "lib/endorsed");
        downloadLibraries(downloader, overallEffective.getExtensionLibraries(), "lib/ext");
        downloadLibraries(downloader, overallEffective.getBootLibraries(), "lib/boot");
        downloader.await();

        //
        // Write all configuration files
        //
        for (Map.Entry<String, byte[]> config : overallEffective.getFileConfigurations().entrySet()) {
            Path configFile = etcDirectory.resolve(config.getKey());
            Files.createDirectories(configFile.getParent());
            Files.write(configFile, config.getValue());
        }

        //
        // Startup stage
        //
        Profile startupEffective = startupStage(startupProfile);

        //
        // Boot stage
        //
        Set<Feature> allBootFeatures = bootStage(bootProfile, startupEffective);

        //
        // Installed stage
        //
        installStage(installedProfile, allBootFeatures);
    }

    private void downloadLibraries(Downloader downloader, List<String> libraries, final String path) throws MalformedURLException {
        for (String library : libraries) {
            downloader.download(library, new DownloadCallback() {
                @Override
                public void downloaded(final StreamProvider provider) throws Exception {
                    synchronized (provider) {
                        Path input = provider.getFile().toPath();
                        Path output = homeDirectory.resolve(path).resolve(input.getFileName().toString());
                        Files.copy(input, output);
                    }
                }
            });
        }
    }

    private void installStage(Profile installedProfile, Set<Feature> allBootFeatures) throws Exception {
        Downloader downloader;//
        // Handle installed profiles
        //
        Profile installedOverlay = Profiles.getOverlay(installedProfile, allProfiles, environment);
        Profile installedEffective = Profiles.getEffective(installedOverlay, false);

        downloader = manager.createDownloader();

        // Load startup repositories
        Map<String, Features> installedRepositories = loadRepositories(manager, installedEffective.getRepositories(), true);
        // Compute startup feature dependencies
        Set<Feature> allInstalledFeatures = new HashSet<>();
        for (Features repo : installedRepositories.values()) {
            allInstalledFeatures.addAll(repo.getFeature());
        }
        Set<Feature> installedFeatures = new LinkedHashSet<>();
        // Add boot features for search
        allInstalledFeatures.addAll(allBootFeatures);
        for (String feature : installedEffective.getFeatures()) {
            addFeatures(installedFeatures, allInstalledFeatures, feature);
        }
        for (Feature feature : installedFeatures) {
            for (Bundle bundle : feature.getBundle()) {
                if (!ignoreDependencyFlag || !bundle.isDependency()) {
                    installArtifact(downloader, bundle.getLocation().trim());
                }
            }
            for (Conditional cond : feature.getConditional()) {
                for (Bundle bundle : cond.getBundle()) {
                    if (!ignoreDependencyFlag || !bundle.isDependency()) {
                        installArtifact(downloader, bundle.getLocation().trim());
                    }
                }
            }
        }
        for (String location : installedEffective.getBundles()) {
            installArtifact(downloader, location);
        }
        downloader.await();
    }

    private Set<Feature> bootStage(Profile bootProfile, Profile startupEffective) throws Exception {
        //
        // Handle boot profiles
        //
        Profile bootOverlay = Profiles.getOverlay(bootProfile, allProfiles, environment);
        Profile bootEffective = Profiles.getEffective(bootOverlay, false);
        // Load startup repositories
        Map<String, Features> bootRepositories = loadRepositories(manager, bootEffective.getRepositories(), true);
        // Compute startup feature dependencies
        Set<Feature> allBootFeatures = new HashSet<>();
        for (Features repo : bootRepositories.values()) {
            allBootFeatures.addAll(repo.getFeature());
        }
        // Generate a global feature
        Map<String, Dependency> generatedDep = new HashMap<>();
        Feature generated = new Feature();
        generated.setName(UUID.randomUUID().toString());
        // Add feature dependencies
        for (String dependency : bootEffective.getFeatures()) {
            Dependency dep = generatedDep.get(dependency);
            if (dep == null) {
                dep = new Dependency();
                dep.setName(dependency);
                generated.getFeature().add(dep);
                generatedDep.put(dep.getName(), dep);
            }
            dep.setDependency(false);
        }
        // Add bundles
        for (String location : bootEffective.getBundles()) {
            location = location.replace("profile:", "file:etc/");
            Bundle bun = new Bundle();
            bun.setLocation(location);
            generated.getBundle().add(bun);
        }
        Features rep = new Features();
        rep.setName(UUID.randomUUID().toString());
        rep.getRepository().addAll(bootEffective.getRepositories());
        rep.getFeature().add(generated);
        allBootFeatures.add(generated);

        Downloader downloader = manager.createDownloader();

        // Compute startup feature dependencies
        Set<Feature> bootFeatures = new HashSet<>();
        addFeatures(bootFeatures, allBootFeatures, generated.getName());
        for (Feature feature : bootFeatures) {
            // the feature is a startup feature, updating startup.properties file
            LOGGER.info("Feature " + feature.getName() + " is defined as a boot feature");
            // add the feature in the system folder
            Set<String> locations = new HashSet<>();
            for (Bundle bundle : feature.getBundle()) {
                if (!ignoreDependencyFlag || !bundle.isDependency()) {
                    locations.add(bundle.getLocation().trim());
                }
            }
            for (Conditional cond : feature.getConditional()) {
                for (Bundle bundle : cond.getBundle()) {
                    if (!ignoreDependencyFlag || !bundle.isDependency()) {
                        locations.add(bundle.getLocation().trim());
                    }
                }
            }

            // Build optional features and known prerequisites
            Map<String, List<String>> prereqs = new HashMap<>();
            prereqs.put("blueprint:", Arrays.asList("deployer", "aries-blueprint"));
            prereqs.put("spring:", Arrays.asList("deployer", "spring"));
            prereqs.put("wrap:", Arrays.asList("wrap"));
            prereqs.put("war:", Arrays.asList("war"));
            for (String location : locations) {
                installArtifact(downloader, location);
                for (Map.Entry<String, List<String>> entry : prereqs.entrySet()) {
                    if (location.startsWith(entry.getKey())) {
                        for (String prereq : entry.getValue()) {
                            Dependency dep = generatedDep.get(prereq);
                            if (dep == null) {
                                dep = new Dependency();
                                dep.setName(prereq);
                                generated.getFeature().add(dep);
                                generatedDep.put(dep.getName(), dep);
                            }
                            dep.setPrerequisite(true);
                        }
                    }
                }
            }
            // Install config files
            for (ConfigFile configFile : feature.getConfigfile()) {
                installArtifact(downloader, configFile.getLocation().trim());
            }
            for (Conditional cond : feature.getConditional()) {
                for (ConfigFile configFile : cond.getConfigfile()) {
                    installArtifact(downloader, configFile.getLocation().trim());
                }
            }
        }

        // If there are bundles to install, we can't use the boot features only
        // so keep the generated feature
        Path featuresCfgFile = etcDirectory.resolve("org.apache.karaf.features.cfg");
        if (!generated.getBundle().isEmpty()) {
            File output = etcDirectory.resolve(rep.getName() + ".xml").toFile();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JaxbUtil.marshal(rep, baos);
            ByteArrayInputStream bais;
            String repoUrl;
            if (use24SyntaxForStartup) {
                String str = baos.toString();
                str = str.replace("http://karaf.apache.org/xmlns/features/v1.3.0", "http://karaf.apache.org/xmlns/features/v1.2.0");
                str = str.replaceAll(" dependency=\".*?\"", "");
                str = str.replaceAll(" prerequisite=\".*?\"", "");
                for (Feature f : rep.getFeature()) {
                    for (Dependency d : f.getFeature()) {
                        if (d.isPrerequisite()) {
                            if (!startupEffective.getFeatures().contains(d.getName())) {
                                LOGGER.warn("Feature " + d.getName() + " is a prerequisite and should be installed as a startup feature.");                }
                        }
                    }
                }
                bais = new ByteArrayInputStream(str.getBytes());
                repoUrl = "file:etc/" + output.getName();
            } else {
                bais = new ByteArrayInputStream(baos.toByteArray());
                repoUrl = "file:${karaf.home}/etc/" + output.getName();
            }
            Files.copy(bais, output.toPath());
            Properties featuresProperties = new Properties(featuresCfgFile.toFile());
            featuresProperties.put(FEATURES_REPOSITORIES, repoUrl);
            featuresProperties.put(FEATURES_BOOT, generated.getName());
            featuresProperties.save();
        }
        else {
            String boot = "";
            for (Dependency dep : generatedDep.values()) {
                if (dep.isPrerequisite()) {
                    if (boot.isEmpty()) {
                        boot = "(";
                    } else {
                        boot = boot + ",";
                    }
                    boot = boot + dep.getName();
                }
            }
            if (!boot.isEmpty()) {
                boot = boot + ")";
            }
            // TODO: for dependencies, we'd need to resolve the features completely
            for (Dependency dep : generatedDep.values()) {
                if (!dep.isPrerequisite() && !dep.isDependency()) {
                    if (!boot.isEmpty()) {
                        boot = boot + ",";
                    }
                    boot = boot + dep.getName();
                }
            }
            String repos = "";
            for (String repo : new HashSet<>(rep.getRepository())) {
                if (!repos.isEmpty()) {
                    repos = repos + ",";
                }
                repos = repos + repo;
            }

            Properties featuresProperties = new Properties(featuresCfgFile.toFile());
            featuresProperties.put(FEATURES_REPOSITORIES, repos);
            featuresProperties.put(FEATURES_BOOT, boot);
            // TODO: reformat to multiline values
            featuresProperties.save();
        }
        downloader.await();
        return allBootFeatures;
    }

    private Profile startupStage(Profile startupProfile) throws Exception {
        //
        // Compute startup
        //
        Profile startupOverlay = Profiles.getOverlay(startupProfile, allProfiles, environment);
        Profile startupEffective = Profiles.getEffective(startupOverlay, false);
        // Load startup repositories
        LOGGER.info("Loading repositories");
        Map<String, Features> startupRepositories = loadRepositories(manager, startupEffective.getRepositories(), false);

        //
        // Resolve
        //
        LOGGER.info("Resolving features");
        Map<String, Integer> bundles =
                resolve(manager,
                        startupRepositories.values(),
                        startupEffective.getFeatures(),
                        startupEffective.getBundles(),
                        startupEffective.getOverrides(),
                        startupEffective.getOptionals());

        //
        // Generate startup.properties
        //
        Properties startup = new Properties();
        startup.setHeader(Collections.singletonList("# Bundles to be started on startup, with startlevel"));
        Map<Integer, Set<String>> invertedStartupBundles = MapUtils.invert(bundles);
        for (Map.Entry<Integer, Set<String>> entry : invertedStartupBundles.entrySet()) {
            String startLevel = Integer.toString(entry.getKey());
            for (String location : new TreeSet<>(entry.getValue())) {
                if (location.startsWith("file:") && useReferenceUrls) {
                    location = "reference:" + location;
                }
                if (location.startsWith("file:") && use24SyntaxForStartup) {
                    location = location.substring("file:".length());
                }
                startup.put(location, startLevel);
            }
        }
        Path startupProperties = etcDirectory.resolve("startup.properties");
        startup.save(startupProperties.toFile());
        return startupEffective;
    }

    private void installArtifact(Downloader downloader, String location) throws Exception {
        LOGGER.info("== Installing artifact " + location);
        location = DownloadManagerHelper.stripUrl(location);
//        location = DownloadManagerHelper.removeInlinedMavenRepositoryUrl(location);
        if (location.startsWith("mvn:")) {
            if (location.endsWith("/")) {
                // for bad formed URL (like in Camel for mustache-compiler), we remove the trailing /
                location = location.substring(0, location.length() - 1);
            }
            downloader.download(location, new DownloadCallback() {
                @Override
                public void downloaded(final StreamProvider provider) throws Exception {
                    Path path = systemDirectory.resolve(Parser.pathFromMaven(provider.getUrl()));
                    synchronized (provider) {
                        Files.createDirectories(path.getParent());
                        Files.copy(provider.getFile().toPath(), path, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            });
            // add metadata for snapshot
            /*
            Artifact artifact = dependencyHelper.mvnToArtifact(location);
            if (artifact.isSnapshot()) {
                File metadataTarget = new File(targetFile.getParentFile(), "maven-metadata-local.xml");
                try {
                    MavenUtil.generateMavenMetadata(artifact, metadataTarget);
                } catch (Exception e) {
                    getLog().warn("Could not create maven-metadata-local.xml", e);
                    getLog().warn("It means that this SNAPSHOT could be overwritten by an older one present on remote repositories");
                }
            }
            */
        } else {
            LOGGER.warn("Ignoring artifact " + location);
        }
    }

    private void addFeatures(Set<Feature> startupFeatures, Set<Feature> features, String feature) {
        int nbFound = 0;
        for (Feature f : features) {
            String[] split = feature.split("/");
            if (split.length == 2) {
                if (f.getName().equals(split[0]) && f.getVersion().equals(split[1])) {
                    for (Dependency dep : f.getFeature()) {
                        addFeatures(startupFeatures, features, dep.getName());
                    }
                    startupFeatures.add(f);
                    nbFound++;
                }
            } else {
                if (feature.equals(f.getName())) {
                    for (Dependency dep : f.getFeature()) {
                        addFeatures(startupFeatures, features, dep.getName());
                    }
                    startupFeatures.add(f);
                    nbFound++;
                }
            }
        }
        if (nbFound == 0) {
            throw new IllegalStateException("Could not find matching feature for " + feature);
        }
    }

    private List<String> getStaged(Stage stage, Map<String, Stage> data) {
        List<String> staged = new ArrayList<>();
        for (String s : data.keySet()) {
            if (data.get(s) == stage) {
                staged.add(s);
            }
        }
        return staged;
    }

    private List<String> getStagedRepositories(Stage stage, Map<String, RepositoryInfo> data) {
        List<String> staged = new ArrayList<>();
        for (String s : data.keySet()) {
            if (data.get(s).stage == stage) {
                staged.add(s);
            }
        }
        return staged;
    }

    private Map<String, Features> loadRepositories(DownloadManager manager, Collection<String> repositories, final boolean install) throws Exception {
        final Map<String, Features> loaded = new HashMap<>();
        final Downloader downloader = manager.createDownloader();
        for (String repository : repositories) {
            downloader.download(repository, new DownloadCallback() {
                @Override
                public void downloaded(final StreamProvider provider) throws Exception {
                    if (install) {
                        synchronized (provider) {
                            Path path = systemDirectory.resolve(Parser.pathFromMaven(provider.getUrl()));
                            Files.createDirectories(path.getParent());
                            Files.copy(provider.getFile().toPath(), path, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    try (InputStream is = provider.open()) {
                        Features featuresModel = JaxbUtil.unmarshal(provider.getUrl(), is, false);
                        synchronized (loaded) {
                            loaded.put(provider.getUrl(), featuresModel);
                            for (String innerRepository : featuresModel.getRepository()) {
                                downloader.download(innerRepository, this);
                            }
                        }
                    }
                }
            });
        }
        downloader.await();
        return loaded;
    }

    private Profile generateProfile(Stage stage, Map<String, Stage> profiles, Map<String, RepositoryInfo> repositories, Map<String, Stage> features, Map<String, Stage> bundles) {
        Profile profile = ProfileBuilder.Factory.create(UUID.randomUUID().toString())
                .setParents(getStaged(stage, profiles))
                .setRepositories(getStagedRepositories(stage, repositories))
                .setFeatures(getStaged(stage, features))
                .setBundles(getStaged(stage, bundles))
                .getProfile();
        allProfiles.put(profile.getId(), profile);
        return profile;
    }

    private Map<String, Integer> resolve(
                    DownloadManager manager,
                    Collection<Features> repositories,
                    Collection<String> features,
                    Collection<String> bundles,
                    Collection<String> overrides,
                    Collection<String> optionals) throws Exception {
        BundleRevision systemBundle = getSystemBundle();
        AssemblyDeployCallback callback = new AssemblyDeployCallback(manager, homeDirectory, defaultStartLevel, systemBundle, repositories);
        Deployer deployer = new Deployer(manager, callback);

        // Install framework
        Deployer.DeploymentRequest request = createDeploymentRequest();
        // Add overrides
        request.overrides.addAll(overrides);
        // Add optional resources
        final List<Resource> resources = new ArrayList<>();
        Downloader downloader = manager.createDownloader();
        for (String optional : optionals) {
            downloader.download(optional, new DownloadCallback() {
                @Override
                public void downloaded(StreamProvider provider) throws Exception {
                    Resource resource = ResourceBuilder.build(provider.getUrl(), getHeaders(provider));
                    synchronized (resources) {
                        resources.add(resource);
                    }
                }
            });
        }
        downloader.await();
        request.globalRepository = new BaseRepository(resources);
        // Install features
        for (String feature : features) {
            MapUtils.addToMapSet(request.requirements, FeaturesService.ROOT_REGION, feature);
        }
        for (String bundle : bundles) {
            MapUtils.addToMapSet(request.requirements, FeaturesService.ROOT_REGION, "bundle:" + bundle);
        }
        Set<String> prereqs = new HashSet<>();
        while (true) {
            try {
                deployer.deploy(callback.getDeploymentState(), request);
                break;
            } catch (Deployer.PartialDeploymentException e) {
                if (!prereqs.containsAll(e.getMissing())) {
                    prereqs.addAll(e.getMissing());
                } else {
                    throw new Exception("Deployment aborted due to loop in missing prerequisites: " + e.getMissing());
                }
            }
        }

        return callback.getStartupBundles();
    }

    private Deployer.DeploymentRequest createDeploymentRequest() {
        Deployer.DeploymentRequest request = new Deployer.DeploymentRequest();
        request.bundleUpdateRange = FeaturesService.DEFAULT_BUNDLE_UPDATE_RANGE;
        request.featureResolutionRange = FeaturesService.DEFAULT_FEATURE_RESOLUTION_RANGE;
        request.overrides = new HashSet<>();
        request.requirements = new HashMap<>();
        request.stateChanges = new HashMap<>();
        request.options = EnumSet.noneOf(FeaturesService.Option.class);
        return request;
    }

    private BundleRevision getSystemBundle() throws Exception {
        Path configPropPath = etcDirectory.resolve("config.properties");
        Properties configProps = PropertiesLoader.loadPropertiesOrFail(configPropPath.toFile());
        configProps.put("java.specification.version", javase);
        configProps.substitute();

        Attributes attributes = new Attributes();
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "system.bundle");
        attributes.putValue(Constants.BUNDLE_VERSION, "0.0.0");

        String exportPackages = configProps.getProperty("org.osgi.framework.system.packages");
        if (configProps.containsKey("org.osgi.framework.system.packages.extra")) {
            exportPackages += "," + configProps.getProperty("org.osgi.framework.system.packages.extra");
        }
        exportPackages = exportPackages.replaceAll(",\\s*,", ",");
        attributes.putValue(Constants.EXPORT_PACKAGE, exportPackages);

        String systemCaps = configProps.getProperty("org.osgi.framework.system.capabilities");
        attributes.putValue(Constants.PROVIDE_CAPABILITY, systemCaps);

        final Hashtable<String, String> headers = new Hashtable<>();
        for (Map.Entry attr : attributes.entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }

        return new FakeBundleRevision(headers, "system-bundle", 0l);
    }

    Map<String, String> getHeaders(StreamProvider provider) throws IOException {
        try (
                InputStream is = provider.open()
        ) {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST_NAME.equals(entry.getName())) {
                    Attributes attributes = new Manifest(zis).getMainAttributes();
                    Map<String, String> headers = new HashMap<>();
                    for (Map.Entry attr : attributes.entrySet()) {
                        headers.put(attr.getKey().toString(), attr.getValue().toString());
                    }
                    return headers;
                }
            }
        }
        throw new IllegalArgumentException("Resource " + provider.getUrl() + " does not contain a manifest");
    }

}
