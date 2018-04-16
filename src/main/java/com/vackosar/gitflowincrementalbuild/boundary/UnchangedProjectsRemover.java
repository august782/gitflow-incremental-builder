package com.vackosar.gitflowincrementalbuild.boundary;

import com.google.inject.Singleton;
import com.vackosar.gitflowincrementalbuild.control.ChangedProjects;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
class UnchangedProjectsRemover {

    private static final String MAVEN_TEST_SKIP = "maven.test.skip";
    private static final String MAVEN_TEST_SKIP_EXEC = "skipTests";
    static final String TEST_JAR_DETECTED = "Dependency with test-jar goal detected. Will compile test sources.";
    private static final String GOAL_TEST_COMPILE = "test-compile";
    private static final String GOAL_TEST_JAR = "test-jar";

    @Inject private Configuration configuration;
    @Inject private Logger logger;
    @Inject private ChangedProjects changedProjects;
    @Inject private MavenSession mavenSession;

    void act() throws GitAPIException, IOException {
        // Update the filter based on any dependencies changed, and update the dependencies
        checkAndUpdateDependencies();
        Set<String> skippedModules = getSkippedModules();
        Set<MavenProject> changed = changedProjects.get();
        printDelimiter();
        logProjects(changed, "Changed Artifacts:");
        Set<MavenProject> impacted = mavenSession.getAllProjects().stream()
                .filter(changed::contains)
                .flatMap(p -> getAllDependents(mavenSession.getAllProjects(), p).stream())
                .collect(Collectors.toSet());
        if (!configuration.buildAll) {
            Set<MavenProject> rebuild = getRebuildProjects(impacted);
            if (rebuild.isEmpty()) {
                validateCurrentProject();
            } else {
                // Create list of projects to rebuild while maintaining same order
                // as default list from session
                List<MavenProject> rebuildList = new ArrayList<>();
                for (MavenProject proj : mavenSession.getProjects()) {
                    if (rebuild.contains(proj) || (!skippedModules.isEmpty() && skippedModules.contains(proj.toString()))) {
                        rebuildList.add(proj);
                    }
                }
                // Potentially can lead to empty projects due to manually setting -pl in build command,
                // so need to have same logic as if rebuild was empty
                if (rebuildList.isEmpty()) {
                    validateCurrentProject();
                } else {
                    mavenSession.setProjects(rebuildList);
                }
            }
        } else {
            mavenSession.getProjects().stream()
                    .filter(p -> !impacted.contains(p))
                    .forEach(this::ifSkipDependenciesTest);
        }
        // If useEkstazi option is true, add Ekstazi plugin to each MavenProject in the session
        if (configuration.useEkstazi) {
            // Set the forceall property for Ekstazi if not all changes are Java
            boolean forceall = !changedProjects.isJavaChangesOnly();
            if (forceall) {
                logger.info("EKSTAZI TURNED OFF, NOT ALL JAVA CHANGES");
            }
            for (MavenProject proj : mavenSession.getProjects()) {
                Build build = proj.getBuild();
                addEkstaziPlugin(build, forceall);
                proj.setBuild(build);
            }
        }
    }

    private Set<String> getSkippedModules() {
        Set<String> result = new HashSet<>();
        if (configuration.skippedModulesFile.equals("")) {
            return result;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(configuration.skippedModulesFile));
            String line;
            while ((line = br.readLine()) != null) {
                result.add(line);
            }
            br.close();
        } catch (FileNotFoundException ex) {
            logger.info("The file set for skippedModulesFile is not found.");
        } catch (IOException ex) {
            logger.info("The file set for skippedModulesFile is empty.");
        }
        return result;
    }

    void checkSkippedModules() {
        if (configuration.skippedModulesFile.equals("")) {
            return;
        }
        StringBuilder skippedModulesSB = new StringBuilder();
        MavenExecutionResult result = mavenSession.getResult();
        for (MavenProject project : mavenSession.getProjects()) {
            if (result.getBuildSummary(project) == null) {
                skippedModulesSB.append(project.toString());
                skippedModulesSB.append("\n");
            }
        }
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(configuration.skippedModulesFile));
            bw.write(skippedModulesSB.toString());
            bw.newLine();
            bw.close();
        } catch (IOException ex) {
            logger.info("Exception when writing to the file.");
        }
    }

    private void validateCurrentProject() {
        logger.info("No changed artifacts to build. Executing validate goal on current project only.");
        mavenSession.setProjects(Collections.singletonList(mavenSession.getCurrentProject()));
        mavenSession.getGoals().clear();
        mavenSession.getGoals().add("validate");
    }

    private void checkAndUpdateDependencies() {
        // If the classpathFile is not set, then don't bother
        if (configuration.classpathFile.equals("")) {
            return;
        }

        // Try to read the stored classpathFile; if cannot, then assume new one
        StringBuilder oldClasspathSB = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(configuration.classpathFile));
            String line;
            while ((line = br.readLine()) != null) {
                oldClasspathSB.append(line);
                oldClasspathSB.append("\n");
            }

            br.close();
        } catch (FileNotFoundException ex) {
            logger.info("The file set for classpathFile is not found, will make new file in its place.");
        } catch (IOException ex) {
            // An IOException occurs if file is empty, so can safely continue (will write the new classpath in)
            logger.info("The file set for classpathFile is empty, will overwrite it.");
        }
        String oldClasspath = oldClasspathSB.toString();

        // For each MavenProject, map to each one's dependencies
        Map<String, Set<Dependency>> proj2Deps = new HashMap<>();
        Set<String> internalDeps = new HashSet<>();
        for (MavenProject proj : mavenSession.getAllProjects()) {
            String projId = proj.getGroupId() + ":" + proj.getArtifactId(); // Do not serialize the version, that changes often
            internalDeps.add(projId + ":" + proj.getVersion());             // However, do keep version in set of internal deps for later comparison
            Set<Dependency> dependencies = new HashSet<>();
            dependencies.addAll(proj.getDependencies());
            proj2Deps.put(projId, dependencies);
        }

        // Sort and serialize the mapping
        StringBuilder newClasspathSB = new StringBuilder();
        List<String> projIds = new ArrayList<>(proj2Deps.keySet());
        Collections.sort(projIds);
        for (String projId : projIds) {
            newClasspathSB.append(projId);
            newClasspathSB.append("=");
            List<String> depNames = new ArrayList<>();
            for (Dependency dep : proj2Deps.get(projId)) {
                String depName = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
                // If the dependency is an internal one, do not store the version, since that can change often
                if (!internalDeps.contains(depName)) {
                    depNames.add(depName);
                }
                else {
                    depNames.add(dep.getGroupId() + ":" + dep.getArtifactId());
                }
            }
            Collections.sort(depNames);
            for (String dep : depNames) {
                newClasspathSB.append(dep);
                newClasspathSB.append(",");
            }
            newClasspathSB.append("\n");
        }

        // Compare the serialized dependencies with that on disk
        String newClasspath = newClasspathSB.toString();
        if (newClasspath.trim().equals(oldClasspath.trim())) {
            // If same, can safely ignore pom.xml if changed
            configuration.excludePathRegex = configuration.excludePathRegex.or(Pattern.compile("(pom.xml$)").asPredicate());
            logger.info("EXCLUDING pom.xml, DEPENDENCIES DID NOT CHANGE");
        }

        // Write the classpath into the file
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(configuration.classpathFile));
            bw.write(newClasspath);
            bw.newLine();

            bw.close();
        } catch (IOException ex) {
            logger.info("Exception when writing to the file.");
        }
    }

    private void addEkstaziPlugin(Build build, boolean forceall) {
        // Create the Ekstazi plugin
        Plugin ekstazi = new Plugin();
        ekstazi.setGroupId("org.ekstazi");
        ekstazi.setArtifactId("ekstazi-maven-plugin");
        ekstazi.setVersion("5.2.0");

        // Add the execution to Ekstazi
        PluginExecution execution = new PluginExecution();
        execution.setId("ekstazi");
        execution.addGoal("select");
        // Based on forceall, add new configuration for forceall property
        if (forceall) {
            Xpp3Dom configurationNode = new Xpp3Dom("configuration");
            Xpp3Dom forceallNode = new Xpp3Dom("forceall");
            forceallNode.setValue("true");
            configurationNode.addChild(forceallNode);
            execution.setConfiguration(configurationNode);
        }
        ekstazi.addExecution(execution);

        build.addPlugin(ekstazi);
    }

    private Set<MavenProject> getRebuildProjects(Set<MavenProject> changedProjects) {
        if (configuration.makeUpstream) {
            return Stream.concat(changedProjects.stream(), collectDependencies(changedProjects)).collect(Collectors.toSet());
        } else {
            return changedProjects;
        }
    }

    private Stream<MavenProject> collectDependencies(Set<MavenProject> changedProjects) {
        return changedProjects.stream()
                .flatMap(this::ifMakeUpstreamGetDependencies)
                .filter(p -> ! changedProjects.contains(p))
                .map(this::ifSkipDependenciesTest);
    }

    private MavenProject ifSkipDependenciesTest(MavenProject mavenProject) {
        if (configuration.skipTestsForNotImpactedModules) {
            if (projectDeclaresTestJarGoal(mavenProject)) {
                logger.debug(mavenProject.getArtifactId() + ": " + TEST_JAR_DETECTED);
                mavenProject.getProperties().setProperty(MAVEN_TEST_SKIP_EXEC, Boolean.TRUE.toString());
            } else {
                mavenProject.getProperties().setProperty(MAVEN_TEST_SKIP, Boolean.TRUE.toString());
            }
        }
        return mavenProject;
    }

    private boolean projectDeclaresTestJarGoal(MavenProject mavenProject) {
        return mavenProject.getBuildPlugins().stream()
                .flatMap(p -> p.getExecutions().stream())
                .flatMap(e -> e.getGoals().stream())
                .anyMatch(GOAL_TEST_JAR::equals);
    }

    private void logProjects(Set<MavenProject> projects, String title) {
        logger.info(title);
        logger.info("");
        projects.stream().map(MavenProject::getArtifactId).forEach(logger::info);
        logger.info("");
    }

    private void printDelimiter() {
        logger.info("------------------------------------------------------------------------");
    }

    private Set<MavenProject> getAllDependents(List<MavenProject> projects, MavenProject project) {
        Set<MavenProject> result = new HashSet<>();
        result.add(project);
        for (MavenProject possibleDependent: projects) {
            if (isDependentOf(possibleDependent, project)) {
                result.addAll(getAllDependents(projects, possibleDependent));
            }
            if (project.equals(possibleDependent.getParent())) {
                result.addAll(getAllDependents(projects, possibleDependent));
            }
        }
        return result;
    }

    private Stream<MavenProject> ifMakeUpstreamGetDependencies(MavenProject mavenProject) {
        return getAllDependencies(mavenSession.getProjects(), mavenProject).stream();
    }

    private Set<MavenProject> getAllDependencies(List<MavenProject> projects, MavenProject project) {
        Set<MavenProject> dependencies = project.getDependencies().stream()
                .map(d -> convert(projects, d)).filter(Optional::isPresent).map(Optional::get)
                .flatMap(p -> getAllDependencies(projects, p).stream())
                .collect(Collectors.toSet());
        dependencies.add(project);
        return dependencies;
    }

    private boolean equals(MavenProject project, Dependency dependency) {
        return dependency.getArtifactId().equals(project.getArtifactId())
                && dependency.getGroupId().equals(project.getGroupId())
                && dependency.getVersion().equals(project.getVersion());
    }

    private Optional<MavenProject> convert(List<MavenProject> projects, Dependency dependency) {
        return projects.stream().filter(p -> equals(p, dependency)).findFirst();
    }

    private boolean isDependentOf(MavenProject possibleDependent, MavenProject project) {
        return possibleDependent.getDependencies().stream().anyMatch(d -> equals(project, d));
    }
}

