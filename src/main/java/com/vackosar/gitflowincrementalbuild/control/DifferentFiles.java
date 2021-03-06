package com.vackosar.gitflowincrementalbuild.control;

import com.vackosar.gitflowincrementalbuild.boundary.Configuration;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class DifferentFiles {

    private static final String HEAD = "HEAD";
    private static final String REFS_REMOTES = "refs/remotes/";
    private static final String REFS_HEADS = "refs/heads/";
    @Inject private Git git;
    @Inject private Configuration configuration;
    @Inject private Logger logger;
    @Inject private MavenSession mavenSession;

    public Set<Path> get() throws GitAPIException, IOException {
        fetch();
        checkout();

        String baseCommit;
        String referenceCommit;

        // Based on commitRange being set as DEFAULT or not, use different baseCommit and referenceCommit
        if (configuration.commitRange.equals("DEFAULT")) {
            baseCommit = configuration.baseCommit;
            referenceCommit = configuration.referenceCommit;
        }
        else {
            // If the value is empty, that means no comparison (from Travis), suggesting first build
            // As such, simulate building everything by returning that the root path changed
            if (configuration.commitRange.equals("")) {
                Set<Path> rootPaths = new HashSet<Path>();
                rootPaths.add(mavenSession.getTopLevelProject().getBasedir().toPath());
                return rootPaths;
            }
            else {
                String[] parts = configuration.commitRange.split("\\.\\.\\.");
                baseCommit = parts[1];
                referenceCommit = parts[0];
            }
        }

        return get(baseCommit, referenceCommit);
    }

    private Set<Path> get(String baseCommit, String referenceCommit) throws GitAPIException, IOException {
        // Use a commit SHA to be base if it is set; otherwise do default branch base
        RevCommit base;
        if (!baseCommit.equals("")) {
            RevWalk walk = new RevWalk(git.getRepository());
            base = walk.parseCommit(git.getRepository().resolve(baseCommit));
            walk.close();
            logger.info("Base commit is: " + base.getId());
        }
        else {
            base = getBranchCommit(configuration.baseBranch);
        }
        final TreeWalk treeWalk = new TreeWalk(git.getRepository());
        treeWalk.addTree(base.getTree());
        // Use a commit SHA to be reference if it is set; otherwise do default branch reference
        if (!referenceCommit.equals("")) {
            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit refCommit = walk.parseCommit(git.getRepository().resolve(referenceCommit));
            walk.close();
            logger.info("Reference commit is: " + refCommit.getId());
            treeWalk.addTree(refCommit.getTree());
        }
        else {
            treeWalk.addTree(resolveReference(base).getTree());
        }
        treeWalk.setFilter(TreeFilter.ANY_DIFF);
        treeWalk.setRecursive(true);
        final Path workTree = git.getRepository().getWorkTree().toPath().normalize().toAbsolutePath();
        final Set<Path> paths = getDiff(treeWalk, workTree);
        if (configuration.uncommited) {
            paths.addAll(getUncommitedChanges(workTree));
        }
        treeWalk.close();
        git.getRepository().close();
        git.close();
        return paths;
    }

    private void checkout() throws IOException, GitAPIException {
        if (! (HEAD.equals(configuration.baseBranch) || configuration.baseBranch.startsWith("worktrees/")) && ! git.getRepository().getFullBranch().equals(configuration.baseBranch)) {
            logger.info("Checking out base branch " + configuration.baseBranch + "...");
            git.checkout().setName(configuration.baseBranch).call();
        }
    }

    private void fetch() throws GitAPIException {
        if (configuration.fetchReferenceBranch) {
            fetch(configuration.referenceBranch);
        }
        if (configuration.fetchBaseBranch) {
            fetch(configuration.baseBranch);
        }
    }

    private void fetch(String branchName) throws GitAPIException {
        logger.info("Fetching branch " + branchName);
        if (!branchName.startsWith(REFS_REMOTES)) {
            throw new IllegalArgumentException("Branch name '" + branchName + "' is not tracking branch name since it does not start " + REFS_REMOTES);
        }
        String remoteName = extractRemoteName(branchName);
        String shortName = extractShortName(remoteName, branchName);
        git.fetch().setRemote(remoteName).setRefSpecs(new RefSpec(REFS_HEADS + shortName + ":" + branchName)).call();
    }

    private String extractRemoteName(String branchName) {
        return branchName.split("/")[2];
    }

    private String extractShortName(String remoteName, String branchName) {
        return branchName.replaceFirst(REFS_REMOTES + remoteName + "/", "");
    }

    private RevCommit getMergeBase(RevCommit baseCommit, RevCommit referenceHeadCommit) throws IOException {
        RevWalk walk = new RevWalk(git.getRepository());
        walk.setRevFilter(RevFilter.MERGE_BASE);
        walk.markStart(walk.lookupCommit(baseCommit));
        walk.markStart(walk.lookupCommit(referenceHeadCommit));
        RevCommit commit = walk.next();
        walk.close();
        logger.info("Using merge base of id: " + commit.getId());
        return commit;
    }

    private Set<Path> getDiff(TreeWalk treeWalk, Path gitDir) throws IOException {
        final Set<Path> paths = new HashSet<>();
        while (treeWalk.next()) {
            Path path = gitDir.resolve(treeWalk.getPathString()).normalize();
            if (! configuration.excludePathRegex.test(path.toString())) {
                paths.add(path);
            }
        }
        return paths;
    }

    private RevCommit getBranchCommit(String branchName) throws IOException {
        ObjectId objectId = git.getRepository().resolve(branchName);

        if (objectId == null) {
            throw new IllegalArgumentException("Git branch of name '" + branchName + "' not found.");
        }
        final RevWalk walk = new RevWalk(git.getRepository());
        RevCommit commit = walk.parseCommit(objectId);
        walk.close();
        logger.info("Reference commit of branch " + branchName + " is commit of id: " + commit.getId());
        return commit;
    }

    private Set<Path> getUncommitedChanges(Path gitDir) throws GitAPIException {
        return git.status().call().getUncommittedChanges().stream()
                .map(gitDir::resolve).map(Path::normalize).collect(Collectors.toSet());
    }

    private RevCommit resolveReference(RevCommit base) throws IOException {
        RevCommit refHead = getBranchCommit(configuration.referenceBranch);
        if (configuration.compareToMergeBase) {
            return getMergeBase(base, refHead);
        } else {
            return refHead;
        }
    }

}
