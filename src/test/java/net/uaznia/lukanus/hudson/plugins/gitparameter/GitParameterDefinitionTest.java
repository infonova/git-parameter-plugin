package net.uaznia.lukanus.hudson.plugins.gitparameter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import hudson.EnvVars;
import hudson.cli.CLICommand;
import hudson.cli.ConsoleCommand;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.impl.RelativeTargetDirectory;
import hudson.plugins.templateproject.ProxySCM;
import hudson.scm.SCM;
import hudson.tasks.Shell;
import hudson.util.FormValidation;
import hudson.util.FormValidation.Kind;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition.DescriptorImpl;
import net.uaznia.lukanus.hudson.plugins.gitparameter.jobs.JobWrapper;
import net.uaznia.lukanus.hudson.plugins.gitparameter.jobs.JobWrapperFactory;
import org.jenkinsci.plugins.multiplescms.MultiSCM;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.kohsuke.stapler.StaplerRequest;

/**
 * @author lukanus
 */
public class GitParameterDefinitionTest {
    private static final String GIT_PARAMETER_REPOSITORY_URL = "https://github.com/jenkinsci/git-parameter-plugin.git";
    private static final String GIT_CLIENT_REPOSITORY_URL = "https://github.com/jenkinsci/git-client-plugin.git";
    public static final String NAME = "name";
    public static final String PT_BRANCH_TAG = "PT_BRANCH_TAG";
    public static final String DEFAULT_VALUE = "defaultValue";
    private FreeStyleProject project;

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    /**
     * Test of createValue method, of class GitParameterDefinition.
     */
    @Test
    public void testCreateValue_StaplerRequest() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        StaplerRequest request = mock(StaplerRequest.class);
        ParameterValue result = instance.createValue(request);

        assertEquals(result, new GitParameterValue(NAME, DEFAULT_VALUE));
    }

    @Test
    public void matchesWithBitbucketPullRequestRefs(){
        Matcher matcher = GitParameterDefinition.PULL_REQUEST_REFS_PATTERN.matcher("refs/pull-requests/186/from");
        matcher.find();
        assertEquals(matcher.group(1),"186");
    }

    @Test
    public void matchesWithGithubPullRequestRefs(){
        Matcher matcher = GitParameterDefinition.PULL_REQUEST_REFS_PATTERN.matcher("refs/pull/45/head");
        matcher.find();
        assertEquals(matcher.group(1),"45");
    }

    @Test
    public void testCreateValue_StaplerRequest_ValueInRequest() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        StaplerRequest request = mock(StaplerRequest.class);
        when(request.getParameterValues(instance.getName())).thenReturn(new String[]{"master"});
        ParameterValue result = instance.createValue(request);

        assertEquals(result, new GitParameterValue(NAME, "master"));
    }

    @Test
    public void testConstructorInitializesTagFilterToAsteriskWhenNull() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  null, null, SortMode.NONE, SelectedValue.NONE, null, false);
        assertEquals("*", instance.getTagFilter());
        assertEquals(".*", instance.getBranchFilter());
    }

    @Test
    public void testConstructorInitializesTagFilterToAsteriskWhenWhitespace() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  "  ", "  ", SortMode.NONE, SelectedValue.NONE, null, false);
        assertEquals("*", instance.getTagFilter());
        assertEquals(".*", instance.getBranchFilter());
    }

    @Test
    public void testConstructorInitializesTagFilterToAsteriskWhenEmpty() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  "", "", SortMode.NONE, SelectedValue.NONE, null, false);
        assertEquals("*", instance.getTagFilter());
        assertEquals(".*", instance.getBranchFilter());
    }

    @Test
    public void testConstructorInitializesTagToGivenValueWhenNotNullOrWhitespace() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  "foobar", "foobar", SortMode.NONE, SelectedValue.NONE, null, false);
        assertEquals("foobar", instance.getTagFilter());
        assertEquals("foobar", instance.getBranchFilter());
    }


    @Test
    public void testSmartNumberStringComparerWorksWithSameNumberComponents() {
        Comparator<String> comparer = new SmartNumberStringComparer();
        assertTrue(comparer.compare("v_1.1.0.2", "v_1.1.1.1") < 0);
        assertTrue(comparer.compare("v_1.1.1.1", "v_1.1.1.1") == 0);
        assertTrue(comparer.compare("v_1.1.1.1", "v_2.0.0.0") < 0);
        assertTrue(comparer.compare("v_1.1.1.1", "v_1.1.1.0") > 0);
    }

    @Test
    public void testSmartNumberStringComparerVeryLongReleaseNumber() {
        Comparator<String> comparer = new SmartNumberStringComparer();
        assertTrue(comparer.compare("v_1.1.20150122112449123456789.1", "v_1.1.20150122112449123456788.1") > 0);
        assertTrue(comparer.compare("v_1.1.20150122112449123456789.1", "v_1.1.20150122112449123456789.1") == 0);
        assertTrue(comparer.compare("v_1.1.20150122112449123456789.1", "v_1.1.20150122112449223456789.1") < 0);
    }

    @Test
    public void testSmartNumberStringComparerWorksWithDifferentNumberComponents() {
        Comparator<String> comparer = new SmartNumberStringComparer();
        assertTrue(comparer.compare("v_1.1.1.1", "v_1.1.0") > 0);
        assertTrue(comparer.compare("v_1.1.1.1", "v_1.1.2") < 0);
        assertTrue(comparer.compare("v_1", "v_2.0.0.0") < 0);
    }

    @Test
    public void testSortTagsYieldsCorrectOrderWithSmartSortEnabled() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  null, null, SortMode.ASCENDING_SMART, SelectedValue.NONE, null, false);
        Set<String> tags = new HashSet<String>();
        tags.add("v_1.0.0.2");
        tags.add("v_1.0.0.5");
        tags.add("v_1.0.1.1");
        tags.add("v_1.0.0.0");
        tags.add("v_1.0.0.10");

        ArrayList<String> orderedTags = instance.sortByName(tags);

        assertEquals("v_1.0.0.0", orderedTags.get(0));
        assertEquals("v_1.0.0.2", orderedTags.get(1));
        assertEquals("v_1.0.0.5", orderedTags.get(2));
        assertEquals("v_1.0.0.10", orderedTags.get(3));
        assertEquals("v_1.0.1.1", orderedTags.get(4));
    }

    @Test
    public void testSortTagsYieldsCorrectOrderWithSmartSortDisabled() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description",  null, null, SortMode.ASCENDING, SelectedValue.NONE, null, false);
        Set<String> tags = new HashSet<String>();
        tags.add("v_1.0.0.2");
        tags.add("v_1.0.0.5");
        tags.add("v_1.0.1.1");
        tags.add("v_1.0.0.0");
        tags.add("v_1.0.0.10");

        ArrayList<String> orderedTags = instance.sortByName(tags);

        assertEquals("v_1.0.0.0", orderedTags.get(0));
        assertEquals("v_1.0.0.10", orderedTags.get(1));
        assertEquals("v_1.0.0.2", orderedTags.get(2));
        assertEquals("v_1.0.0.5", orderedTags.get(3));
        assertEquals("v_1.0.1.1", orderedTags.get(4));
    }

    @Test
    public void testSortMode_getIsUsingSmartSort() {
        assertFalse(SortMode.NONE.getIsUsingSmartSort());
        assertFalse(SortMode.ASCENDING.getIsUsingSmartSort());
        assertTrue(SortMode.ASCENDING_SMART.getIsUsingSmartSort());
        assertFalse(SortMode.DESCENDING.getIsUsingSmartSort());
        assertTrue(SortMode.DESCENDING_SMART.getIsUsingSmartSort());
    }

    @Test
    public void testSortMode_getIsDescending() {
        assertFalse(SortMode.NONE.getIsDescending());
        assertFalse(SortMode.ASCENDING.getIsDescending());
        assertFalse(SortMode.ASCENDING_SMART.getIsDescending());
        assertTrue(SortMode.DESCENDING.getIsDescending());
        assertTrue(SortMode.DESCENDING_SMART.getIsDescending());
    }

    @Test
    public void testSortMode_getIsSorting() {
        assertFalse(SortMode.NONE.getIsSorting());
        assertTrue(SortMode.ASCENDING.getIsSorting());
        assertTrue(SortMode.ASCENDING_SMART.getIsSorting());
        assertTrue(SortMode.DESCENDING.getIsSorting());
        assertTrue(SortMode.DESCENDING_SMART.getIsSorting());
    }

    /**
     * Test of createValue method, of class GitParameterDefinition.
     */
    @Test
    public void testCreateValue_StaplerRequest_JSONObject() {
        System.out.println("createValue");
        StaplerRequest request = mock(StaplerRequest.class);

        Map<String, String> jsonR = new HashMap<String, String>();
        jsonR.put("value", "Git_param_value");
        jsonR.put(NAME, "Git_param_name");

        JSONObject jO = JSONObject.fromObject(jsonR);


        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description", ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        ParameterValue result = instance.createValue(request, jO);

        assertEquals(result, new GitParameterValue("Git_param_name", "Git_param_value"));
    }

    @Test
    public void testCreateValue_CLICommand() throws IOException, InterruptedException {
        CLICommand cliCommand = new ConsoleCommand();
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description", ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        String value = "test";
        ParameterValue result = instance.createValue(cliCommand, value);
        assertEquals(result, new GitParameterValue(NAME, value));
    }

    @Test
    public void testCreateValue_CLICommand_EmptyValue() throws IOException, InterruptedException {
        CLICommand cliCommand = new ConsoleCommand();
        GitParameterDefinition instance = new GitParameterDefinition(NAME, PT_BRANCH_TAG, DEFAULT_VALUE, "description", ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        ParameterValue result = instance.createValue(cliCommand, null);
        assertEquals(result, new GitParameterValue(NAME, DEFAULT_VALUE));
    }

    /**
     * Test of getDefaultParameterValue method, of class GitParameterDefinition.
     */
    @Test
    public void testGetDefaultParameterValue() {

        // TODO review the generated test code and remove the default call to fail.
        //fail("The test case is a prototype.");
    }

    /**
     * Test of getType method, of class GitParameterDefinition.
     */
    @Test
    public void testGetType() {
        System.out.println("Test of getType method.");
        String expResult = PT_BRANCH_TAG;
        GitParameterDefinition instance = new GitParameterDefinition(NAME, expResult, DEFAULT_VALUE, "description", ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);
        String result = instance.getType();
        assertEquals(expResult, result);


        instance.setType(expResult);
        result = instance.getType();
        assertEquals(expResult, result);

    }

    /**
     * Test of setType method, of class GitParameterDefinition.
     */
    @Test
    public void testSetType() {
        System.out.println("Test of setType method.");
        String expResult = PT_BRANCH_TAG;
        GitParameterDefinition instance = new GitParameterDefinition(NAME, "asdf", DEFAULT_VALUE, "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        instance.setType(expResult);
        String result = instance.getType();
        assertEquals(expResult, result);
    }

    @Test
    public void testWrongType() {
        GitParameterDefinition instance = new GitParameterDefinition(NAME, "asdf", DEFAULT_VALUE, "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);

        String result = instance.getType();
        assertEquals("PT_BRANCH", result);
    }

    /**
     * Test of getDefaultValue method, of class GitParameterDefinition.
     */
    @Test
    public void testGetDefaultValue() {
        System.out.println("getDefaultValue");
        String expResult = DEFAULT_VALUE;

        GitParameterDefinition instance = new GitParameterDefinition(NAME, "asdf", expResult, "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);
        String result = instance.getDefaultValue();
        assertEquals(expResult, result);
    }

    /**
     * Test of setDefaultValue method, of class GitParameterDefinition.
     */
    @Test
    public void testSetDefaultValue() {
        System.out.println("getDefaultValue");
        String expResult = DEFAULT_VALUE;

        GitParameterDefinition instance = new GitParameterDefinition(NAME, "asdf", "other", "description",  ".*", "*", SortMode.NONE, SelectedValue.NONE, null, false);
        instance.setDefaultValue(expResult);

        String result = instance.getDefaultValue();
        assertEquals(expResult, result);
    }

    /**
     * Test of generateContents method, of class GitParameterDefinition.
     */
    @Test
    public void testGenerateContents() {
    }

    // Test Descriptor.getProjectSCM()
    @Test
    public void testGetProjectSCM() throws Exception {
        FreeStyleProject testJob = jenkins.createFreeStyleProject("testGetProjectSCM");
        GitSCM git = new GitSCM(GIT_PARAMETER_REPOSITORY_URL);
        GitParameterDefinition def = new GitParameterDefinition("testName",
                PT_BRANCH_TAG,
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        testJob.addProperty(new ParametersDefinitionProperty(def));
        JobWrapper IJobWrapper = JobWrapperFactory.createJobWrapper(testJob);
        assertTrue(def.getDescriptor().getProjectSCMs(IJobWrapper, null).isEmpty());

        testJob.setScm(git);
        assertTrue(git.equals(def.getDescriptor().getProjectSCMs(IJobWrapper, null).get(0)));
    }

    @Test
    public void testDoFillValueItems_withoutSCM() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("testListTags");
        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "No Git repository configured in SCM configuration"));
    }

    @Test
    public void testDoFillValueItems_listTags() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        // Run the build once to get the workspace
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "git-parameter-0.2"));
    }

    @Test
    public void testGetListBranchNoBuildProject() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
    }

    @Test
    public void testGetListBranchAfterWipeOutWorkspace() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH",
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        // build.run();
        project.doDoWipeOutWorkspace();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
    }

    @Test
    public void testDoFillValueItems_listBranches() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        // Run the build once to get the workspace
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
    }

    @Test
    public void tesListBranchesWrongBranchFilter() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH",
                "testDefaultValue",
                "testDescription",
                "[*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
    }

    @Test
    public void testDoFillValueItems_listBranches_withRegexGroup() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH",
                "testDefaultValue",
                "testDescription",
                "origin/(.*)",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        // Run the build once to get the workspace
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
        assertFalse(isListBoxItem(items, "origin/master"));
    }

    @Test
    public void testSortAscendingTag() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertEquals("0.1", items.get(0).value);
    }

    @Test
    public void testWrongTagFilter() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "wrongTagFilter",
                SortMode.ASCENDING, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(items.isEmpty());
    }

    @Test
    public void testSortDescendingTag() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.DESCENDING, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertEquals("0.1", items.get(items.size() - 1).value);
    }

    @Test
    public void testDoFillValueItems_listTagsAndBranches() throws Exception {
        project = jenkins.createFreeStyleProject("testListTags");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();

        GitParameterDefinition def = new GitParameterDefinition("testName",
                "PT_BRANCH_TAG",
                "testDefaultValue",
                "testDescription",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        project.addProperty(new ParametersDefinitionProperty(def));

        // Run the build once to get the workspace
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "master"));
        assertTrue(isListBoxItem(items, "git-parameter-0.2"));
    }

    @Test
    public void testDoFillValueItems_listPullRequests() throws Exception {
        project = jenkins.createFreeStyleProject("testListPullRequests");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();
        GitParameterDefinition def = new GitParameterDefinition("testName",
            GitParameterDefinition.PARAMETER_TYPE_PULL_REQUEST,
            "master",
            "testDescription",
            ".*",
            "*",
            SortMode.NONE, SelectedValue.NONE, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "41"));
        assertTrue(isListBoxItem(items, "44"));
    }

    @Test
    public void testSearchInFolders() throws Exception {
        MockFolder folder = jenkins.createFolder("folder");
        FreeStyleProject job1 = folder.createProject(FreeStyleProject.class, "job1");

        GitParameterDefinition gitParameterDefinition = new GitParameterDefinition(NAME,
                "asdf",
                "other",
                "description",
                ".*",
                "*",
                SortMode.NONE, SelectedValue.NONE, null, false);
        job1.addProperty(new ParametersDefinitionProperty(gitParameterDefinition));
        assertEquals("folder/job1", gitParameterDefinition.getParentJob().getFullName());
    }

    @Test
    public void testBranchFilterValidation() {
        final DescriptorImpl descriptor = new DescriptorImpl();
        final FormValidation okWildcard = descriptor.doCheckBranchFilter(".*");
        final FormValidation badWildcard = descriptor.doCheckBranchFilter(".**");

        assertTrue(okWildcard.kind == Kind.OK);
        assertTrue(badWildcard.kind == Kind.ERROR);
    }

    @Test
    public void testUseRepositoryValidation() {
        final DescriptorImpl descriptor = new DescriptorImpl();
        final FormValidation okWildcard = descriptor.doCheckUseRepository(".*");
        final FormValidation badWildcard = descriptor.doCheckUseRepository(".**");

        assertTrue(okWildcard.kind == Kind.OK);
        assertTrue(badWildcard.kind == Kind.ERROR);
    }

    @Test
    public void testGetDefaultValueWhenDefaultValueIsSet() throws Exception {
        project = jenkins.createFreeStyleProject("testDefaultValue");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();
        String testDefaultValue = "testDefaultValue";
        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_TAG,
                testDefaultValue,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));

        assertTrue(testDefaultValue.equals(def.getDefaultParameterValue().getValue()));
    }

    @Test
    public void testGetDefaultValueAsTop() throws Exception {
        project = jenkins.createFreeStyleProject("testDefaultValueAsTOP");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit();
        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_TAG,
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));
        assertEquals("0.1", def.getDefaultParameterValue().getValue());
    }

    @Test
    public void testGlobalVariableRepositoryUrl() throws Exception {
        EnvVars.masterEnvVars.put("GIT_REPO_URL", GIT_PARAMETER_REPOSITORY_URL);
        project = jenkins.createFreeStyleProject("testGlobalValue");
        project.getBuildersList().add(new Shell("echo test"));
        setupGit("$GIT_REPO_URL");
        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_BRANCH,
                null,
                "testDescription",
                ".*master.*",
                "*",
                SortMode.ASCENDING, SelectedValue.NONE, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        assertEquals(build.getResult(), Result.SUCCESS);
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "origin/master"));
    }

    @Test
    public void testWorkflowJob() throws IOException, InterruptedException {
        WorkflowJob p = jenkins.jenkins.createProject(WorkflowJob.class, "wfj");
        p.setDefinition(new CpsScmFlowDefinition(getGitSCM(), "jenkinsfile"));

        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_TAG,
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        p.addProperty(new ParametersDefinitionProperty(def));
        ListBoxModel items = def.getDescriptor().doFillValueItems(p, def.getName());
        assertTrue(isListBoxItem(items, "git-parameter-0.2"));
    }

    @Test
    public void testProxySCM() throws IOException, InterruptedException {
        FreeStyleProject anotherProject = jenkins.createFreeStyleProject("AnotherProject");
        anotherProject.getBuildersList().add(new Shell("echo test"));
        anotherProject.setScm(getGitSCM());

        project = jenkins.createFreeStyleProject("projectHaveProxySCM");
        project.setScm(new ProxySCM("AnotherProject"));

        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_TAG,
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "git-parameter-0.2"));
    }

    @Test
    public void testMultiSCM() throws IOException, InterruptedException {
        project = jenkins.createFreeStyleProject("projectHaveMultiSCM");
        project.getBuildersList().add(new Shell("echo test"));
        MultiSCM multiSCM = new MultiSCM(Arrays.asList(getGitSCM(), getGitSCM(GIT_CLIENT_REPOSITORY_URL)));
        project.setScm(multiSCM);

        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_BRANCH,
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "origin/master"));

        def.setUseRepository(".*git-client-plugin.git");
        items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "origin/master"));
    }

    @Test
    public void testMultiSCM_forSubdirectoryForRepo() throws IOException, InterruptedException {
        project = jenkins.createFreeStyleProject("projectHaveMultiSCM");
        project.getBuildersList().add(new Shell("echo test"));
        GitSCM gitSCM = (GitSCM) getGitSCM(GIT_CLIENT_REPOSITORY_URL);
        gitSCM.getExtensions().add(new RelativeTargetDirectory("subDirectory"));
        MultiSCM multiSCM = new MultiSCM(Arrays.asList(getGitSCM(), gitSCM));
        project.setScm(multiSCM);

        GitParameterDefinition def = new GitParameterDefinition("testName",
                GitParameterDefinition.PARAMETER_TYPE_BRANCH,
                null,
                "testDescription",
                ".*",
                "*",
                SortMode.ASCENDING, SelectedValue.TOP, null, false);

        project.addProperty(new ParametersDefinitionProperty(def));
        ListBoxModel items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "origin/master"));

        def.setUseRepository(".*git-client-plugin.git");
        items = def.getDescriptor().doFillValueItems(project, def.getName());
        assertTrue(isListBoxItem(items, "origin/master"));
    }


    private void setupGit() throws IOException {
        setupGit(GIT_PARAMETER_REPOSITORY_URL);
    }

    private void setupGit(String url) throws IOException {
        SCM git = getGitSCM(url);
        project.setScm(git);
    }

    private SCM getGitSCM() {
        return getGitSCM(GIT_PARAMETER_REPOSITORY_URL);
    }

    private SCM getGitSCM(String url) {
        UserRemoteConfig config = new UserRemoteConfig(url, null, null, null);
        List<UserRemoteConfig> configs = new ArrayList<UserRemoteConfig>();
        configs.add(config);
        return new GitSCM(configs, null, false, null, null, null, null);
    }

    private boolean isListBoxItem(ListBoxModel items, String item) {
        boolean itemExists = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).value.contains(item)) {
                itemExists = true;
            }
        }
        return itemExists;
    }
}
