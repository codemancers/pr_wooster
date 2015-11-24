package org.jenkinsci.plugins.pr_wooster;

import hudson.Launcher;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepMonitor;
import hudson.model.AbstractProject;
import hudson.tasks.Publisher;
import hudson.tasks.Notifier;
import hudson.tasks.BuildStepDescriptor;
import hudson.model.Result;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import hudson.EnvVars;
import hudson.plugins.git.GitSCM;
import org.kohsuke.github.GHAuthorization;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHPerson;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import java.util.Arrays;
import java.util.List;

import java.io.IOException;

import java.lang.reflect.Field;


/**
 * @author Yuva Kumar
 */
public class GithubCiNotifier extends Notifier {
    @DataBoundConstructor
    public GithubCiNotifier() {
        super();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean prebuild(AbstractBuild build, BuildListener listener) {
        return sendStatus(build, listener, GHCommitState.PENDING, "Build started");
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        GHCommitState status = GHCommitState.PENDING;
        String desc = "";

        Result result = build.getResult();
        if (result.isBetterOrEqualTo(Result.SUCCESS)) {
            status = GHCommitState.SUCCESS;
            desc = "Build successful";
        } else if (result.isBetterOrEqualTo(Result.UNSTABLE)) {
            status = GHCommitState.FAILURE;
            desc = "Build failed";
        } else {
            status = GHCommitState.ERROR;
            desc = "Build error'ed";
        }

        return sendStatus(build, listener, status, desc);
    }

    private boolean sendStatus(AbstractBuild build, BuildListener listener,
                               GHCommitState status, String desc) {
        GitSCM scm = (GitSCM)build.getProject().getScm();
        String url = scm.getUserRemoteConfigs().get(0).getUrl().replace(".git", "");

        String[] fields;
        if (url.indexOf("@") != -1) {
            String[] parts = url.split(":");
            fields = parts[parts.length - 1].split("/");
        } else {
            fields = url.split("/");
        }

        String owner = fields[fields.length - 2];
        String repo  = fields[fields.length - 1];
        String buildUrl = build.getAbsoluteUrl();

        listener.getLogger().println("owner: " + owner);
        listener.getLogger().println("repo: "  + repo);
        listener.getLogger().println("url: "   + buildUrl);

        try {
            EnvVars environment = build.getEnvironment(listener);
            String sha1 = environment.get(GitSCM.GIT_COMMIT);
            listener.getLogger().println("sha1: " + sha1);

            GitHub gh = GitHub.connectUsingOAuth("https://api.github.com", getDescriptor().getOAuthToken());

            GHUser user = new GHUser();
            Field f1 = GHPerson.class.getDeclaredField("login");
            f1.setAccessible(true);
            f1.set(user, owner);

            GHRepository repository = new GHRepository();
            Field f2 = GHRepository.class.getDeclaredField("root");
            f2.setAccessible(true);
            f2.set(repository, gh);
            Field f3 = GHRepository.class.getDeclaredField("owner");
            f3.setAccessible(true);
            f3.set(repository, user);
            Field f4 = GHRepository.class.getDeclaredField("name");
            f4.setAccessible(true);
            f4.set(repository, repo);

            listener.getLogger().println("sending commit status: ");
            repository.createCommitStatus(sha1, status, buildUrl, desc, "Jenkins");
        } catch (IOException ex) {
            listener.getLogger().println("io exception: " + ex.getMessage());
            return false;
        } catch (InterruptedException ex) {
            listener.getLogger().println("interrupted exception: " + ex.getMessage());
            return false;
        } catch (NoSuchFieldException ex) {
            listener.getLogger().println("no such field");
            return false;
        } catch (IllegalAccessException ex) {
            listener.getLogger().println("illegal exception");
            return false;
        }

        return true;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link GithubCiNotifier}. Used as a singleton.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String oauthToken;

        public DescriptorImpl() {
            super(GithubCiNotifier.class);
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Github CI Notifier";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            String username = formData.getString("githubciUsername");
            String password = formData.getString("githubciPassword");

            try {
                GitHub gh = GitHub.connectUsingPassword(username, password);

                List<String> scopes = Arrays.asList(GHAuthorization.REPO_STATUS);
                GHAuthorization token = gh.createToken(scopes, "Github CI - Jenkins", null);
                oauthToken = token.getToken();

                save();
                return super.configure(req, formData);
            } catch(IOException ex) {
                return false;
            }
        }

        public String getOAuthToken() {
            return oauthToken;
        }
    }
}
