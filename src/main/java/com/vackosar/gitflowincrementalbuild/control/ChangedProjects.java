package com.vackosar.gitflowincrementalbuild.control;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class ChangedProjects {

    @Inject private Logger logger;
    @Inject private DifferentFiles differentFiles;
    @Inject private MavenSession mavenSession;
    @Inject private Modules modules;

    public Set<MavenProject> get() throws GitAPIException, IOException {
        return differentFiles.get().stream()
                .map(path -> findProject(path, mavenSession))
                .filter(project -> project != null)
                .collect(Collectors.toSet());
    }

    public boolean isJavaChangesOnly() throws GitAPIException, IOException {
        return differentFiles.get().stream()
                .filter(path -> !path.toString().endsWith(".java"))
                .collect(Collectors.toSet())
                .isEmpty();
    }

    private MavenProject findProject(Path diffPath, MavenSession mavenSession) {
        Map<Path, MavenProject> map = modules.createPathMap(mavenSession);
        Path path = diffPath;
        while (path != null && ! map.containsKey(path)) {
            path = path.getParent();
        }
        if (path != null) {
            logger.debug("Changed file: " + diffPath);
            return map.get(path);
        } else {
            logger.warn("Changed file outside build project: " + diffPath);
            return null;
        }
    }
}
