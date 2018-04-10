package net.uaznia.lukanus.hudson.plugins.gitparameter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.base.Strings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.cli.CLICommand;
import hudson.model.*;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.uaznia.lukanus.hudson.plugins.gitparameter.jobs.JobWrapper;
import net.uaznia.lukanus.hudson.plugins.gitparameter.jobs.JobWrapperFactory;
import net.uaznia.lukanus.hudson.plugins.gitparameter.scms.SCMFactory;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class GitParameterDefinition extends ParameterDefinition implements Comparable<GitParameterDefinition> {
    private static final long serialVersionUID = 9157832967140868122L;

    private static final String DEFAULT_REMOTE = "origin";
    private static final String REFS_TAGS_PATTERN = ".*refs/tags/";

    public static final String PARAMETER_TYPE_TAG = "PT_TAG";
    public static final String PARAMETER_TYPE_BRANCH = "PT_BRANCH";
    public static final String PARAMETER_TYPE_TAG_BRANCH = "PT_BRANCH_TAG";
    public static final String PARAMETER_TYPE_PULL_REQUEST = "PT_PULL_REQUEST";

    public static final Pattern PULL_REQUEST_REFS_PATTERN = Pattern.compile("refs/pull.*/(\\d+)/[from|head]");

    public static final String TEMPORARY_DIRECTORY_PREFIX = "git_parameter_";
    public static final String EMPTY_JOB_NAME = "EMPTY_JOB_NAME";
    private static final Logger LOGGER = Logger.getLogger(GitParameterDefinition.class.getName());

    private final UUID uuid;
    private String type;
    private String tagFilter;
    private String branchFilter;
    private SortMode sortMode;
    private String defaultValue;
    private SelectedValue selectedValue;
    private String useRepository;
    private Boolean quickFilterEnabled;

    @DataBoundConstructor
    public GitParameterDefinition(String name, String type, String defaultValue, String description,
                                  String branchFilter, String tagFilter, SortMode sortMode, SelectedValue selectedValue,
                                  String useRepository, Boolean quickFilterEnabled) {
        super(name, description);
        this.defaultValue = defaultValue;
        this.uuid = UUID.randomUUID();
        this.sortMode = sortMode;
        this.selectedValue = selectedValue;
        this.quickFilterEnabled = quickFilterEnabled;

        setUseRepository(useRepository);
        setType(type);
        setTagFilter(tagFilter);
        setBranchFilter(branchFilter);
    }

    @Override
    public ParameterValue createValue(StaplerRequest request) {
        String value[] = request.getParameterValues(getName());
        if (value == null || value.length == 0 || StringUtils.isBlank(value[0])) {
            return getDefaultParameterValue();
        }
        return new GitParameterValue(getName(), value[0]);
    }

    @Override
    public ParameterValue createValue(StaplerRequest request, JSONObject jO) {
        Object value = jO.get("value");
        StringBuilder strValue = new StringBuilder();
        if (value instanceof String) {
            strValue.append(value);
        } else if (value instanceof JSONArray) {
            JSONArray jsonValues = (JSONArray) value;
            for (int i = 0; i < jsonValues.size(); i++) {
                strValue.append(jsonValues.getString(i));
                if (i < jsonValues.size() - 1) {
                    strValue.append(",");
                }
            }
        }

        if (strValue.length() == 0) {
            strValue.append(defaultValue);
        }

        return new GitParameterValue(jO.getString("name"), strValue.toString());
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
        if (StringUtils.isNotEmpty(value)) {
            return new GitParameterValue(getName(), value);
        }
        return getDefaultParameterValue();
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        //If 'Default Value' is set has high priority!
        String defValue = getDefaultValue();
        if (!StringUtils.isBlank(defValue)) {
            return new GitParameterValue(getName(), defValue);
        }

        switch (getSelectedValue()) {
            case TOP:
                try {
                    ListBoxModel valueItems = getDescriptor().doFillValueItems(getParentJob(), getName());
                    if (valueItems.size() > 0) {
                        return new GitParameterValue(getName(), valueItems.get(0).value);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, getCustomeJobName() + " " + Messages.GitParameterDefinition_unexpectedError(), e);
                }
                break;
            case DEFAULT:
            case NONE:
            default:
                return super.getDefaultParameterValue();
        }
        return super.getDefaultParameterValue();
    }

    @Override
    public String getType() {
        return type;
    }

    public void setType(String type) {
        if (isParameterTypeCorrect(type)) {
            this.type = type;
        } else {
            this.type = PARAMETER_TYPE_BRANCH;
        }
    }

    private boolean isParameterTypeCorrect(String type) {
        return type.equals(PARAMETER_TYPE_TAG) ||
               type.equals(PARAMETER_TYPE_BRANCH) ||
               type.equals(PARAMETER_TYPE_TAG_BRANCH) ||
               type.equals(PARAMETER_TYPE_PULL_REQUEST);
    }

    public SortMode getSortMode() {
        return this.sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public String getTagFilter() {
        return this.tagFilter;
    }

    public void setTagFilter(String tagFilter) {
        if (StringUtils.isEmpty(StringUtils.trim(tagFilter))) {
            tagFilter = "*";
        }
        this.tagFilter = tagFilter;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getBranchFilter() {
        return branchFilter;
    }

    public void setBranchFilter(String branchFilter) {
        if (StringUtils.isEmpty(StringUtils.trim(branchFilter))) {
            branchFilter = ".*";
        }

        this.branchFilter = branchFilter;
    }

    public SelectedValue getSelectedValue() {
        return selectedValue == null ? SelectedValue.TOP : selectedValue;
    }

    public Boolean getQuickFilterEnabled() {
        return quickFilterEnabled;
    }

    public Job getParentJob() {
        Job context = null;
        List<Job> jobs = Jenkins.getInstance().getAllItems(Job.class);

        for (Job job : jobs) {
            if (!(job instanceof TopLevelItem)) continue;

            ParametersDefinitionProperty property = (ParametersDefinitionProperty) job.getProperty(ParametersDefinitionProperty.class);

            if (property != null) {
                List<ParameterDefinition> parameterDefinitions = property.getParameterDefinitions();

                if (parameterDefinitions != null) {
                    for (ParameterDefinition pd : parameterDefinitions) {
                        if (pd instanceof GitParameterDefinition && ((GitParameterDefinition) pd).compareTo(this) == 0) {
                            context = job;
                            break;
                        }
                    }
                }
            }
        }

        return context;
    }

    public int compareTo(GitParameterDefinition pd) {
        return pd.uuid.equals(uuid) ? 0 : -1;
    }

    public Map<String, String> generateContents(JobWrapper jobWrapper, GitSCM git) {
        Map<String, String> paramList = new LinkedHashMap<String, String>();
        try {
            EnvVars environment = getEnvironment(jobWrapper);
            for (RemoteConfig repository : git.getRepositories()) {
                synchronized (GitParameterDefinition.class) {
                    for (URIish remoteURL : repository.getURIs()) {
                        GitClient gitClient = getGitClient(jobWrapper, git, environment);
                        String gitUrl = Util.replaceMacro(remoteURL.toPrivateASCIIString(), environment);

                        if (notMatchUseRepository(gitUrl)) {
                            continue;
                        }

                        if (isTagType()) {
                            Set<String> tagSet = getTag(gitClient, gitUrl);
                            sortAndPutToParam(tagSet, paramList);
                        }

                        if (isBranchType()) {
                            Set<String> branchSet = getBranch(gitClient, gitUrl, repository.getName());
                            sortAndPutToParam(branchSet, paramList);
                        }

                        if(isPullRequestType()){
                            Set<String> pullRequestSet = getPullRequest(gitClient, gitUrl);
                            sortAndPutToParam(pullRequestSet, paramList);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, getCustomeJobName() + " " + Messages.GitParameterDefinition_unexpectedError(), e);
            String message = e.getMessage() + Messages.GitParameterDefinition_lookAtLog();
            paramList.clear();
            paramList.put(message, message);
        }
        return paramList;
    }

    private boolean notMatchUseRepository(String gitUrl) {
        if (Strings.isNullOrEmpty(useRepository)) {
            return false;
        }
        Pattern repositoryNamePattern;
        try {
            repositoryNamePattern = Pattern.compile(useRepository);
        } catch (Exception e) {
            LOGGER.log(Level.INFO, Messages.GitParameterDefinition_invalidUseRepositoryPattern(useRepository), e.getMessage());
            return false;
        }
        return !repositoryNamePattern.matcher(gitUrl).find();
    }

    private boolean isBranchType() {
        return type.equalsIgnoreCase(PARAMETER_TYPE_BRANCH) || type.equalsIgnoreCase(PARAMETER_TYPE_TAG_BRANCH);
    }

    private boolean isTagType() {
        return type.equalsIgnoreCase(PARAMETER_TYPE_TAG) || type.equalsIgnoreCase(PARAMETER_TYPE_TAG_BRANCH);
    }

    private boolean isPullRequestType() {
        return type.equalsIgnoreCase(PARAMETER_TYPE_PULL_REQUEST);
    }

    private Set<String> getTag(GitClient gitClient, String gitUrl) throws InterruptedException {
        Set<String> tagSet = new HashSet<String>();
        try {
            Map<String, ObjectId> tags = gitClient.getRemoteReferences(gitUrl, tagFilter, false, true);
            for (String tagName : tags.keySet()) {
                tagSet.add(tagName.replaceFirst(REFS_TAGS_PATTERN, ""));
            }
        } catch (GitException e) {
            LOGGER.log(Level.WARNING, getCustomeJobName() + " " + Messages.GitParameterDefinition_getTag(), e);
        }
        return tagSet;
    }

    private Set<String> getBranch(GitClient gitClient, String gitUrl, String remoteName) throws InterruptedException {
        Set<String> branchSet = new HashSet<String>();
        Pattern branchFilterPattern = compileBranchFilterPattern();

        Map<String, ObjectId> branches = gitClient.getRemoteReferences(gitUrl, null, true, false);
        Iterator<String> remoteBranchesName = branches.keySet().iterator();
        while (remoteBranchesName.hasNext()) {
            String branchName = strip(remoteBranchesName.next(), remoteName);
            Matcher matcher = branchFilterPattern.matcher(branchName);
            if (matcher.matches()) {
                if (matcher.groupCount() == 1) {
                    branchSet.add(matcher.group(1));
                } else {
                    branchSet.add(branchName);
                }
            }
        }
        return branchSet;
    }

    private Set<String> getPullRequest(GitClient gitClient, String gitUrl) throws InterruptedException {
        Set<String> pullRequestSet = new HashSet<String>();
        Map<String, ObjectId> remoteReferences = gitClient.getRemoteReferences(gitUrl, null, false, false);
        for (String remoteReference : remoteReferences.keySet()) {
            Matcher matcher = PULL_REQUEST_REFS_PATTERN.matcher(remoteReference);
            if (matcher.find()) {
                pullRequestSet.add(matcher.group(1));
            }
        }
        return pullRequestSet;
    }

    private Pattern compileBranchFilterPattern() {
        Pattern branchFilterPattern;
        try {
            branchFilterPattern = Pattern.compile(branchFilter);
        } catch (Exception e) {
            LOGGER.log(Level.INFO, getCustomeJobName() + " " + Messages.GitParameterDefinition_branchFilterNotValid(), e.getMessage());
            branchFilterPattern = Pattern.compile(".*");
        }
        return branchFilterPattern;
    }

    //hudson.plugins.git.Branch.strip
    private String strip(String name, String remote) {
        return remote + "/" + name.substring(name.indexOf('/', 5) + 1);
    }

    private void sortAndPutToParam(Set<String> setElement, Map<String, String> paramList) {
        List<String> sorted = sort(setElement);

        for (String element : sorted) {
            paramList.put(element, element);
        }
    }

    private ArrayList<String> sort(Set<String> toSort) {
        ArrayList<String> sorted;

        if (this.getSortMode().getIsSorting()) {
            sorted = sortByName(toSort);
            if (this.getSortMode().getIsDescending()) {
                Collections.reverse(sorted);
            }
        } else {
            sorted = new ArrayList<String>(toSort);
        }
        return sorted;
    }

    private EnvVars getEnvironment(JobWrapper jobWrapper) throws IOException, InterruptedException {
        EnvVars environment = jobWrapper.getEnvironment(Jenkins.getInstance().toComputer().getNode(), TaskListener.NULL);
        EnvVars buildEnvironments = jobWrapper.getSomeBuildEnvironments();
        if (buildEnvironments != null) {
            environment.putAll(buildEnvironments);
        }

        EnvVars jobDefautEnvironments = getJobDefaultEnvironment(jobWrapper);
        environment.putAll(jobDefautEnvironments);

        EnvVars.resolve(environment);
        return environment;
    }

    private EnvVars getJobDefaultEnvironment(JobWrapper jobWrapper) {
        EnvVars environment = new EnvVars();
        ParametersDefinitionProperty property = (ParametersDefinitionProperty) jobWrapper.getJob().getProperty(ParametersDefinitionProperty.class);
        if (property != null) {
            for (ParameterDefinition parameterDefinition : property.getParameterDefinitions()) {
                if (parameterDefinition != null && isAcceptedParameterClass(parameterDefinition)) {
                    checkAndAddDefaultParameterValue(parameterDefinition, environment);
                }
            }
        }
        return environment;
    }

    private boolean isAcceptedParameterClass(ParameterDefinition parameterDefinition) {
        return parameterDefinition instanceof StringParameterDefinition
                || parameterDefinition instanceof ChoiceParameterDefinition;
    }

    private void checkAndAddDefaultParameterValue(ParameterDefinition parameterDefinition, EnvVars environment) {
        ParameterValue defaultParameterValue = parameterDefinition.getDefaultParameterValue();
        if (defaultParameterValue != null && defaultParameterValue.getValue() != null && defaultParameterValue.getValue() instanceof String) {
            environment.put(parameterDefinition.getName(), (String) defaultParameterValue.getValue());
        }
    }

    private boolean isEmptyWorkspace(FilePath workspaceDir) throws IOException, InterruptedException {
        return workspaceDir.list().size() == 0;
    }

    private GitClient getGitClient(final JobWrapper jobWrapper, GitSCM git, EnvVars environment) throws IOException, InterruptedException {
        int nextBuildNumber = jobWrapper.getNextBuildNumber();

        GitClient gitClient = git.createClient(TaskListener.NULL, environment, new Run(jobWrapper.getJob()) {
        }, null);

        jobWrapper.updateNextBuildNumber(nextBuildNumber);
        return gitClient;
    }

    public ArrayList<String> sortByName(Set<String> set) {

        ArrayList<String> tags = new ArrayList<String>(set);

        if (sortMode.getIsUsingSmartSort()) {
            Collections.sort(tags, new SmartNumberStringComparer());
        } else {
            Collections.sort(tags);
        }

        return tags;
    }

    public String getDivUUID() {
        StringBuilder randomSelectName = new StringBuilder();
        randomSelectName.append(getName()).append("-").append(uuid);
        return randomSelectName.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getUseRepository() {
        return useRepository;
    }

    public void setUseRepository(String useRepository) {
        this.useRepository = StringUtils.isEmpty(StringUtils.trim(useRepository)) ? null : useRepository;
    }

    public String getCustomeJobName() {
        Job job = getParentJob();
        String fullName = job != null ? job.getFullName() : EMPTY_JOB_NAME;
        return "[ " + fullName + " ] ";
    }

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        private List<GitSCM> scms;

        @Override
        public String getDisplayName() {
            return Messages.GitParameterDefinition_DisplayName();
        }

        public ListBoxModel doFillValueItems(@AncestorInPath Job job, @QueryParameter String param)
                throws IOException, InterruptedException {
            ListBoxModel items = new ListBoxModel();
            JobWrapper jobWrapper = JobWrapperFactory.createJobWrapper(job);

            ParametersDefinitionProperty prop = jobWrapper.getProperty(ParametersDefinitionProperty.class);
            if (prop != null) {
                ParameterDefinition def = prop.getParameterDefinition(param);
                if (def instanceof GitParameterDefinition) {
                    GitParameterDefinition paramDef = (GitParameterDefinition) def;

                    String repositoryName = paramDef.getUseRepository();
                    scms = getProjectSCMs(jobWrapper, repositoryName);
                    if (scms == null || scms.isEmpty()) {
                        items.add(Messages.GitParameterDefinition_noRepositoryConfigured());
                        return items;
                    }

                    for (GitSCM scm : scms) {
                        Map<String, String> paramList = paramDef.generateContents(jobWrapper, scm);

                        for (Map.Entry<String, String> entry : paramList.entrySet()) {
                            items.add(entry.getValue(), entry.getKey());
                        }
                    }
                }
            }
            return items;
        }

        public List<GitSCM> getProjectSCMs(JobWrapper jobWrapper, String repositoryName) {
            return SCMFactory.getGitSCMs(jobWrapper, repositoryName);
        }

        public FormValidation doCheckBranchFilter(@QueryParameter String value) {
            String errorMessage = Messages.GitParameterDefinition_invalidBranchPattern(value);
            return validationRegularExpression(value, errorMessage);
        }

        public FormValidation doCheckUseRepository(@QueryParameter String value) {
            String errorMessage = Messages.GitParameterDefinition_invalidUseRepositoryPattern(value);
            return validationRegularExpression(value, errorMessage);
        }

        private FormValidation validationRegularExpression(String value, String errorMessage) {
            try {
                Pattern.compile(value); // Validate we've got a valid regex.
            } catch (PatternSyntaxException e) {
                LOGGER.log(Level.WARNING, errorMessage, e);
                return FormValidation.error(errorMessage);
            }
            return FormValidation.ok();
        }
    }
}
