package io.sloeber.core.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.envvar.IContributedEnvironment;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.core.envvar.IEnvironmentVariableManager;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.util.CDataUtil;
import org.eclipse.cdt.managedbuilder.core.IConfiguration;
import org.eclipse.cdt.managedbuilder.core.IManagedProject;
import org.eclipse.cdt.managedbuilder.core.IProjectType;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.managedbuilder.core.ManagedBuilderCorePlugin;
import org.eclipse.cdt.managedbuilder.core.ManagedCProjectNature;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;

import io.sloeber.core.Activator;
import io.sloeber.core.Messages;
import io.sloeber.core.common.Common;
import io.sloeber.core.common.ConfigurationPreferences;
import io.sloeber.core.common.Const;
import io.sloeber.core.listeners.IndexerController;
import io.sloeber.core.toolchain.SloeberConfigurationVariableSupplier;
import io.sloeber.core.tools.Helpers;
import io.sloeber.core.tools.Libraries;
import io.sloeber.core.txt.KeyValueTree;
import io.sloeber.core.txt.TxtFile;

public class SloeberProject extends Common {
    private static QualifiedName sloeberQualifiedName = new QualifiedName(Activator.NODE_ARDUINO, "SloeberProject"); //$NON-NLS-1$
    private Map<String, BoardDescription> myBoardDescriptions = new HashMap<>();
    private Map<String, CompileDescription> myCompileDescriptions = new HashMap<>();
    private Map<String, OtherDescription> myOtherDescriptions = new HashMap<>();
    private TxtFile myCfgFile = null;
    private IProject myProject = null;
    private boolean isInMemory = false;
    private boolean isDirty = false; // if anything has changed
    private boolean myNeedToPersist = false; // Do we need to write data to disk
    private boolean myNeedsClean = false; // is there old sloeber data that needs cleaning
    private boolean myNeedsSyncWithCDT = false; // Knows CDT all configs Sloeber Knows

    private static final String ENV_KEY_BUILD_SOURCE_PATH = BUILD + DOT + SOURCE + DOT + PATH;
    private static final String ENV_KEY_BUILD_GENERIC_PATH = BUILD + DOT + "generic" + DOT + PATH; //$NON-NLS-1$
    private static final String ENV_KEY_COMPILER_PATH = COMPILER + DOT + PATH;
    private static final String SLOEBER_MAKE_LOCATION = ENV_KEY_SLOEBER_START + "make_location"; //$NON-NLS-1$
    private static final String CONFIG = "Config";//$NON-NLS-1$
    private static final String CONFIG_DOT = CONFIG + DOT;

    private SloeberProject(IProject project) {
        myProject = project;
        try {
            project.setSessionProperty(sloeberQualifiedName, this);
        } catch (CoreException e) {
            e.printStackTrace();
        }
    }

    /**
     * convenient method to create project
     * 
     * @param proj1Name
     * @param object
     * @param proj1BoardDesc
     * @param codeDesc
     * @param proj1CompileDesc
     * @param otherDesc
     * @param nullProgressMonitor
     * @return
     */
    public static void convertToArduinoProject(IProject project, IProgressMonitor monitor) {
        if (project == null) {
            return;
        }
        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        ICoreRunnable runnable = new ICoreRunnable() {
            @Override
            public void run(IProgressMonitor internalMonitor) throws CoreException {
                IndexerController.doNotIndex(project);

                try {
                    // turn off auto building
                    IWorkspaceDescription workspaceDesc = workspace.getDescription();
                    workspaceDesc.setAutoBuilding(false);
                    workspace.setDescription(workspaceDesc);

                    // create a sloeber project
                    SloeberProject sloeberProject = new SloeberProject(project);
                    if (!sloeberProject.readConfigFromFiles()) {
                        String RELEASE = "Release";
                        sloeberProject.setBoardDescription(RELEASE, new BoardDescription(), false);
                        sloeberProject.setCompileDescription(RELEASE, new CompileDescription());
                        sloeberProject.setOtherDescription(RELEASE, new OtherDescription());
                        // we failed to read from disk so we set opourselfves some values
                        // faking the stuf is in memory
                        sloeberProject.isInMemory = true;
                    }
                    String configName = sloeberProject.myBoardDescriptions.keySet().iterator().next();
                    BoardDescription boardDescriptor = sloeberProject.getBoardDescription(configName, true);
                    CompileDescription compileDescriptor = sloeberProject.getCompileDescription(configName, true);
                    OtherDescription otherDesc = sloeberProject.getOtherDescription(configName, true);

                    // Add the arduino code folders
                    List<IPath> addToIncludePath = Helpers.addArduinoCodeToProject(project, boardDescriptor);

                    // make the eclipse project a cdt project
                    CCorePlugin.getDefault().createCProject(null, project, new NullProgressMonitor(),
                            ManagedBuilderCorePlugin.MANAGED_MAKE_PROJECT_ID);

                    // add the required natures
                    ManagedCProjectNature.addManagedNature(project, internalMonitor);
                    ManagedCProjectNature.addManagedBuilder(project, internalMonitor);
                    ManagedCProjectNature.addNature(project, "org.eclipse.cdt.core.ccnature", internalMonitor); //$NON-NLS-1$
                    ManagedCProjectNature.addNature(project, Const.ARDUINO_NATURE_ID, internalMonitor);

                    // make the cdt project a managed build project
                    IProjectType sloeberProjType = ManagedBuildManager.getProjectType("io.sloeber.core.sketch"); //$NON-NLS-1$
                    ManagedBuildManager.createBuildInfo(project);
                    IManagedProject newProject = ManagedBuildManager.createManagedProject(project, sloeberProjType);
                    ManagedBuildManager.setNewProjectVersion(project);
                    // Copy over the Sloeber configs
                    IConfiguration defaultConfig = null;
                    IConfiguration[] configs = sloeberProjType.getConfigurations();
                    for (int i = 0; i < configs.length; ++i) {
                        IConfiguration curConfig = newProject.createConfiguration(configs[i],
                                sloeberProjType.getId() + "." + i); //$NON-NLS-1$
                        curConfig.setArtifactName(newProject.getDefaultArtifactName());
                        // Make the first configuration the default
                        if (i == 0) {
                            defaultConfig = curConfig;
                        }
                    }

                    ManagedBuildManager.setDefaultConfiguration(project, defaultConfig);

                    Map<String, String> configs2 = new HashMap<>();

                    CCorePlugin cCorePlugin = CCorePlugin.getDefault();
                    ICProjectDescription prjCDesc = cCorePlugin.getProjectDescription(project);
                    ICConfigurationDescription activeConfig = prjCDesc.getActiveConfiguration();

                    for (String curConfigName : sloeberProject.myBoardDescriptions.keySet()) {
                        ICConfigurationDescription curConfigDesc = prjCDesc.getConfigurationByName(curConfigName);
                        if (curConfigDesc == null) {
                            String id = CDataUtil.genId(null);
                            curConfigDesc = prjCDesc.createConfiguration(id, curConfigName, activeConfig);
                        }
                        Helpers.addIncludeFolder(curConfigDesc, addToIncludePath, true);

                        String curConfigKey = getConfigKey(curConfigDesc);
                        sloeberProject.setEnvVars(curConfigKey, sloeberProject.getEnvVars(curConfigKey));
                        configs2.put(curConfigName, curConfigDesc.getId());

                    }

                    sloeberProject.createSloeberConfigFiles(configs2);
                    SubMonitor refreshMonitor = SubMonitor.convert(internalMonitor, 3);
                    project.refreshLocal(IResource.DEPTH_INFINITE, refreshMonitor);
                    cCorePlugin.setProjectDescription(project, prjCDesc, true, null);

                } catch (Exception e) {
                    Common.log(new Status(IStatus.INFO, io.sloeber.core.Activator.getId(),
                            "Project conversion failed: ", e)); //$NON-NLS-1$
                }
                IndexerController.index(project);
            }

        };

        try {
            workspace.run(runnable, root, IWorkspace.AVOID_UPDATE, monitor);
        } catch (Exception e) {
            Common.log(new Status(IStatus.INFO, io.sloeber.core.Activator.getId(), "Project conversion failed: ", e)); //$NON-NLS-1$
        }

    }

    /**
     * convenient method to create project
     * 
     * @param proj1Name
     * @param object
     * @param proj1BoardDesc
     * @param codeDesc
     * @param proj1CompileDesc
     * @param otherDesc
     * @param nullProgressMonitor
     * @return
     */
    public static IProject createArduinoProject(String projectName, URI projectURI, BoardDescription boardDescriptor,
            CodeDescription codeDesc, CompileDescription compileDescriptor, IProgressMonitor monitor) {
        return createArduinoProject(projectName, projectURI, boardDescriptor, codeDesc, compileDescriptor,
                new OtherDescription(), monitor);
    }

    /*
     * Method to create a project based on the board
     */
    public static IProject createArduinoProject(String projectName, URI projectURI, BoardDescription boardDescriptor,
            CodeDescription codeDesc, CompileDescription compileDescriptor, OtherDescription otherDesc,
            IProgressMonitor monitor) {

        String realProjectName = Common.MakeNameCompileSafe(projectName);

        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        ICoreRunnable runnable = new ICoreRunnable() {
            @Override
            public void run(IProgressMonitor internalMonitor) throws CoreException {
                IProject newProjectHandle = root.getProject(realProjectName);
                IndexerController.doNotIndex(newProjectHandle);

                try {
                    IWorkspaceDescription workspaceDesc = workspace.getDescription();
                    workspaceDesc.setAutoBuilding(false);
                    workspace.setDescription(workspaceDesc);

                    // create a eclipse project
                    IProjectDescription description = workspace.newProjectDescription(newProjectHandle.getName());
                    if (projectURI != null) {
                        description.setLocationURI(projectURI);
                    }
                    newProjectHandle.create(description, internalMonitor);
                    newProjectHandle.open(internalMonitor);

                    // Add the sketch code
                    Map<String, IPath> librariesToAdd = codeDesc.createFiles(newProjectHandle, internalMonitor);

                    // Add the arduino code folders
                    List<IPath> addToIncludePath = Helpers.addArduinoCodeToProject(newProjectHandle, boardDescriptor);

                    Map<String, List<IPath>> pathMods = Libraries.addLibrariesToProject(newProjectHandle,
                            librariesToAdd);

                    // make the eclipse project a cdt project
                    CCorePlugin.getDefault().createCProject(description, newProjectHandle, new NullProgressMonitor(),
                            ManagedBuilderCorePlugin.MANAGED_MAKE_PROJECT_ID);

                    // add the required natures
                    ManagedCProjectNature.addManagedNature(newProjectHandle, internalMonitor);
                    ManagedCProjectNature.addManagedBuilder(newProjectHandle, internalMonitor);
                    ManagedCProjectNature.addNature(newProjectHandle, "org.eclipse.cdt.core.ccnature", internalMonitor); //$NON-NLS-1$
                    ManagedCProjectNature.addNature(newProjectHandle, Const.ARDUINO_NATURE_ID, internalMonitor);

                    // make the cdt project a managed build project
                    IProjectType sloeberProjType = ManagedBuildManager.getProjectType("io.sloeber.core.sketch"); //$NON-NLS-1$
                    ManagedBuildManager.createBuildInfo(newProjectHandle);
                    IManagedProject newProject = ManagedBuildManager.createManagedProject(newProjectHandle,
                            sloeberProjType);
                    ManagedBuildManager.setNewProjectVersion(newProjectHandle);
                    // Copy over the Sloeber configs
                    IConfiguration defaultConfig = null;
                    IConfiguration[] configs = sloeberProjType.getConfigurations();
                    for (int i = 0; i < configs.length; ++i) {
                        IConfiguration curConfig = newProject.createConfiguration(configs[i],
                                sloeberProjType.getId() + "." + i); //$NON-NLS-1$
                        curConfig.setArtifactName(newProject.getDefaultArtifactName());
                        curConfig.getEditableBuilder().setParallelBuildOn(compileDescriptor.isParallelBuildEnabled());
                        // Make the first configuration the default
                        if (i == 0) {
                            defaultConfig = curConfig;
                        }
                    }

                    ManagedBuildManager.setDefaultConfiguration(newProjectHandle, defaultConfig);
                    // create a sloeber project
                    SloeberProject arduinoProjDesc = new SloeberProject(newProjectHandle);
                    Map<String, String> configs2 = new HashMap<>();

                    CCorePlugin cCorePlugin = CCorePlugin.getDefault();
                    ICProjectDescription prjCDesc = cCorePlugin.getProjectDescription(newProjectHandle);

                    for (ICConfigurationDescription curConfigDesc : prjCDesc.getConfigurations()) {

                        arduinoProjDesc.myCompileDescriptions.put(getConfigKey(curConfigDesc), compileDescriptor);
                        arduinoProjDesc.myBoardDescriptions.put(getConfigKey(curConfigDesc), boardDescriptor);
                        arduinoProjDesc.myOtherDescriptions.put(getConfigKey(curConfigDesc), otherDesc);
                        Libraries.adjustProjectDescription(curConfigDesc, pathMods);
                        Helpers.addIncludeFolder(curConfigDesc, addToIncludePath, true);

                        arduinoProjDesc.setEnvVars(getConfigKey(curConfigDesc),
                                arduinoProjDesc.getEnvVars(getConfigKey(curConfigDesc)));
                        configs2.put(curConfigDesc.getName(), curConfigDesc.getId());

                    }

                    arduinoProjDesc.createSloeberConfigFiles(configs2);
                    SubMonitor refreshMonitor = SubMonitor.convert(internalMonitor, 3);
                    newProjectHandle.open(refreshMonitor);
                    newProjectHandle.refreshLocal(IResource.DEPTH_INFINITE, refreshMonitor);
                    cCorePlugin.setProjectDescription(newProjectHandle, prjCDesc, true, null);

                } catch (Exception e) {
                    Common.log(new Status(IStatus.INFO, io.sloeber.core.Activator.getId(),
                            "Project creation failed: " + realProjectName, e)); //$NON-NLS-1$
                }
                Common.log(new Status(Const.SLOEBER_STATUS_DEBUG, Activator.getId(),
                        "internal creation of project is done: " + realProjectName)); //$NON-NLS-1$
                IndexerController.index(newProjectHandle);
            }
        };

        try {
            workspace.run(runnable, root, IWorkspace.AVOID_UPDATE, monitor);
        } catch (Exception e) {
            Common.log(new Status(IStatus.INFO, io.sloeber.core.Activator.getId(),
                    "Project creation failed: " + realProjectName, e)); //$NON-NLS-1$
        }
        monitor.done();
        return root.getProject(realProjectName);
    }

    private HashMap<String, String> getEnvVars(String configKey) {
        BoardDescription boardDescription = myBoardDescriptions.get(configKey);
        CompileDescription compileOptions = myCompileDescriptions.get(configKey);
        OtherDescription otherOptions = myOtherDescriptions.get(configKey);

        HashMap<String, String> allVars = new HashMap<>();

        allVars.put(ENV_KEY_BUILD_SOURCE_PATH, myProject.getLocation().toOSString());

        if (boardDescription != null) {
            allVars.putAll(boardDescription.getEnvVars());
        }
        if (compileOptions != null) {
            allVars.putAll(compileOptions.getEnvVars());
        }
        if (otherOptions != null) {
            allVars.putAll(otherOptions.getEnvVars());
        }
        // set the paths
        String pathDelimiter = makeEnvironmentVar("PathDelimiter"); //$NON-NLS-1$
        if (Common.isWindows) {
            allVars.put(SLOEBER_MAKE_LOCATION,
                    ConfigurationPreferences.getMakePath().addTrailingSeparator().toOSString());
            String systemroot = makeEnvironmentVar("SystemRoot"); //$NON-NLS-1$
            allVars.put("PATH", //$NON-NLS-1$
                    makeEnvironmentVar(ENV_KEY_COMPILER_PATH) + pathDelimiter
                            + makeEnvironmentVar(ENV_KEY_BUILD_GENERIC_PATH) + pathDelimiter + systemroot + "\\system32" //$NON-NLS-1$
                            + pathDelimiter + systemroot + pathDelimiter + systemroot + "\\system32\\Wbem" //$NON-NLS-1$
                            + pathDelimiter + makeEnvironmentVar("sloeber_path_extension")); //$NON-NLS-1$
        } else {
            allVars.put("PATH", makeEnvironmentVar(ENV_KEY_COMPILER_PATH) + pathDelimiter //$NON-NLS-1$
                    + makeEnvironmentVar(ENV_KEY_BUILD_GENERIC_PATH) + pathDelimiter + makeEnvironmentVar("PATH")); //$NON-NLS-1$
        }

        // Set the codeAnalyzer compile commands
        allVars.put(CODAN_C_to_O,
                "${recipe.c.o.pattern.1} -D__IN_ECLIPSE__=1 ${recipe.c.o.pattern.2} ${recipe.c.o.pattern.3} ${sloeber.extra.compile} ${sloeber.extra.c.compile} ${sloeber.extra.all}"); //$NON-NLS-1$
        allVars.put(CODAN_CPP_to_O,
                "${recipe.cpp.o.pattern.1} -D__IN_ECLIPSE__=1 -x c++  ${recipe.cpp.o.pattern.2} ${recipe.cpp.o.pattern.3} ${sloeber.extra.compile} ${sloeber.extra.cpp.compile} ${sloeber.extra.all}"); //$NON-NLS-1$

        return allVars;
    }

    public void configure() {

        CCorePlugin cCorePlugin = CCorePlugin.getDefault();
        ICProjectDescription prjCDesc = cCorePlugin.getProjectDescription(myProject);
        configure(prjCDesc, false);
    }

    /**
     * 
     * @param prjCDesc
     * @param prjDescWritable
     * @return true if the projectDesc needs to be saved
     */

    public boolean configure(ICProjectDescription prjCDesc, boolean prjDescWritable) {
        Map<String, String> configs = getConfigs(prjCDesc);
        boolean saveProjDesc = false;
        if (isInMemory) {
            if (isDirty) {
                createSloeberConfigFiles(configs);
                setEnvironmentVariables(getCfgKeys(configs));
                isDirty = false;
            }
            if (myNeedToPersist) {
                createSloeberConfigFiles(configs);
            }
            if (prjDescWritable) {
                if (myNeedsSyncWithCDT) {
                    saveProjDesc = syncWithCDT(prjCDesc, prjDescWritable);
                }
                if (myNeedsClean) {
                    cleanOldData(prjCDesc);
                }
            }
            return saveProjDesc;
        }

        // first read the sloeber files in memory
        saveProjDesc = readConfig(prjCDesc, prjDescWritable);
        if (myNeedToPersist || isDirty) {
            createSloeberConfigFiles(configs);
            isDirty = false;
        }
        if (prjDescWritable) {
            if (myNeedsClean) {
                // we migrated from a previous sloeber configuration
                // and we can safely delete the old data
                cleanOldData(prjCDesc);
            }
            if (myNeedsSyncWithCDT) {
                saveProjDesc = saveProjDesc || syncWithCDT(prjCDesc, prjDescWritable);
            }
        }
        setEnvironmentVariables(getCfgKeys(configs));
        return saveProjDesc;
    }

    /**
     * sync the Sloeber configuration info with CDT Currently only Sloeber known
     * configurations will be created by Sloeber inside CDT
     */
    private boolean syncWithCDT(ICProjectDescription prjCDesc, boolean prjDescWritable) {
        boolean ret = readConfig(prjCDesc, prjDescWritable);
        myNeedsSyncWithCDT = false;
        return ret;
    }

    /**
     * remove environment variables from the old sloeber way
     * 
     * @param prjCDesc
     * @return
     */
    private void cleanOldData(ICProjectDescription prjCDesc) {
        IEnvironmentVariableManager envManager = CCorePlugin.getDefault().getBuildEnvironmentManager();
        IContributedEnvironment contribEnv = envManager.getContributedEnvironment();
        for (ICConfigurationDescription confDesc : prjCDesc.getConfigurations()) {
            IEnvironmentVariable[] CurVariables = contribEnv.getVariables(confDesc);
            for (int i = (CurVariables.length - 1); i > 0; i--) {
                if (CurVariables[i].getName().startsWith("A.")) { //$NON-NLS-1$
                    contribEnv.removeVariable(CurVariables[i].getName(), confDesc);
                }
                if (CurVariables[i].getName().startsWith("JANTJE.")) { //$NON-NLS-1$
                    contribEnv.removeVariable(CurVariables[i].getName(), confDesc);
                }
            }
        }
        myNeedsClean = false;
    }

    /**
     * Read the configuration needed to setup the project First try the
     * configuration files If they do not exist try the old Sloeber CDT environment
     * variable way
     * 
     * @param confDesc
     *            returns true if the files exist
     */
    private boolean readConfigFromFiles() {
        IFile file = getConfigLocalFile();
        IFile versionFile = getConfigVersionFile();
        if (!(file.exists() || versionFile.exists())) {
            // no sloeber files found
            return false;
        }
        if (file.exists()) {
            myCfgFile = new TxtFile(file.getLocation().toFile());
            if (versionFile.exists()) {
                myCfgFile.mergeFile(versionFile.getLocation().toFile());
            }
        } else {
            if (versionFile.exists()) {
                myCfgFile = new TxtFile(versionFile.getLocation().toFile());
            }
        }

        KeyValueTree allFileConfigs = myCfgFile.getData().getChild(CONFIG);
        for (Entry<String, KeyValueTree> curChild : allFileConfigs.getChildren().entrySet()) {
            String curConfName = curChild.getKey();
            BoardDescription boardDesc = new BoardDescription(myCfgFile, getBoardPrefix(curConfName));
            CompileDescription compileDescription = new CompileDescription(myCfgFile, getCompilePrefix(curConfName));
            OtherDescription otherDesc = new OtherDescription(myCfgFile, getOtherPrefix(curConfName));
            String curConfKey = curConfName;
            myBoardDescriptions.put(curConfKey, boardDesc);
            myCompileDescriptions.put(curConfKey, compileDescription);
            myOtherDescriptions.put(curConfKey, otherDesc);
        }
        return true;
    }

    /**
     * Read the configuration needed to setup the project First try the
     * configuration files If they do not exist try the old Sloeber CDT environment
     * variable way
     * 
     * @param confDesc
     *            returns true if the config was found
     */
    private boolean readConfigFromCDT(ICProjectDescription prjCDesc, boolean prjDescWritable) {
        boolean foundAValidConfig = false;
        // Check if this is a old Sloeber project with the data in the eclipse build
        // environment variables
        for (ICConfigurationDescription confDesc : prjCDesc.getConfigurations()) {

            BoardDescription boardDesc = BoardDescription.getFromCDT(confDesc);
            CompileDescription compileDescription = CompileDescription.getFromCDT(confDesc);
            OtherDescription otherDesc = OtherDescription.getFromCDT(confDesc);
            if (boardDesc.getReferencingBoardsFile() != null) {
                if (!boardDesc.getReferencingBoardsFile().toString().isBlank()) {
                    foundAValidConfig = true;
                    myBoardDescriptions.put(getConfigKey(confDesc), boardDesc);
                    myCompileDescriptions.put(getConfigKey(confDesc), compileDescription);
                    myOtherDescriptions.put(getConfigKey(confDesc), otherDesc);
                }
            }
        }
        return foundAValidConfig;
    }

    /**
     * Read the configuration needed to setup the project First try the
     * configuration files If they do not exist try the old Sloeber CDT environment
     * variable way
     * 
     * @param confDesc
     *            returns true if the config needs saving otherwise false
     */
    private boolean readConfig(ICProjectDescription prjCDesc, boolean prjDescWritable) {
        boolean projDescNeedsWriting = false;
        if (readConfigFromFiles()) {
            KeyValueTree allFileConfigs = myCfgFile.getData().getChild(CONFIG);
            for (Entry<String, KeyValueTree> curChild : allFileConfigs.getChildren().entrySet()) {
                String curConfName = curChild.getKey();
                ICConfigurationDescription curConfDesc = prjCDesc.getConfigurationByName(curConfName);
                if (curConfDesc == null) {
                    myNeedsSyncWithCDT = true;
                    // I set persist because most likely this new config comes from sloeber.cfg
                    // and it must be copied to .sproject
                    myNeedToPersist = true;
                    if (prjDescWritable) {
                        try {
                            String id = CDataUtil.genId(null);
                            projDescNeedsWriting = true;
                            curConfDesc = prjCDesc.createConfiguration(id, curConfName,
                                    prjCDesc.getActiveConfiguration());
                        } catch (Exception e) {
                            // ignore as we will try again later
                        }
                    }
                }
            }

        } else {

            // Maybe this is a old Sloeber project with the data in the eclipse build
            // environment variables
            if (readConfigFromCDT(prjCDesc, prjDescWritable)) {
                myNeedToPersist = true;
                myNeedsClean = true;
                projDescNeedsWriting = true;
            }
        }
        isInMemory = true;
        return projDescNeedsWriting;
    }

    /**
     * This method set the active configuration This means the core and library
     * folders of the project are updated. To avoid many update notifications this
     * is done in a runnable with AVOID_UPDATE
     * 
     * @param confDesc
     *            a writable configuration setting to be made active
     * 
     * @return true if the configuration setting has been changed and needs to be
     *         saved
     */
    private boolean setActiveConfig(ICConfigurationDescription confDesc) {

        BoardDescription boardDescription = myBoardDescriptions.get(getConfigKey(confDesc));
        List<IPath> pathsToInclude = Helpers.addArduinoCodeToProject(myProject, boardDescription);
        boolean projConfMustBeSaved = Helpers.addIncludeFolder(confDesc, pathsToInclude, true);
        boolean includeFoldersRemoved = Helpers.removeInvalidIncludeFolders(confDesc);
        if (includeFoldersRemoved) {
            Helpers.deleteBuildFolder(myProject, confDesc.getName());
        }

        return projConfMustBeSaved || includeFoldersRemoved;
    }

    /**
     * This method set the active configuration This means the core and library
     * folders of the project are updated. To avoid many update notifications this
     * is done in a runnable with AVOID_UPDATE
     * 
     * @param confDesc
     *            a writable configuration setting to be made active
     * 
     * @return true if the configuration setting has been changed and needs tioo be
     *         saved
     */
    private boolean setActiveConfigInRunnable(ICConfigurationDescription confDesc) {

        class MyRunnable implements ICoreRunnable {
            public boolean projConfMustBeSaved = false;

            @Override
            public void run(IProgressMonitor internalMonitor) throws CoreException {
                projConfMustBeSaved = setActiveConfig(confDesc);
            }
        }

        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        MyRunnable runnable = new MyRunnable();
        try {
            workspace.run(runnable, root, IWorkspace.AVOID_UPDATE, null);
        } catch (Exception e) {
            ICProjectDescription projDesc = confDesc.getProjectDescription();
            String confDescName = confDesc.getName();
            String projName = projDesc.getProject().getName();
            Common.log(new Status(IStatus.INFO, io.sloeber.core.Activator.getId(),
                    "Setting config " + confDescName + " for project " + projName + " failed", e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return runnable.projConfMustBeSaved;
    }

    private void setEnvironmentVariables(Collection<String> configKeys) {
        for (String curConfigKey : configKeys) {
            setEnvVars(curConfigKey, getEnvVars(curConfigKey));
        }
    }

    /**
     * This methods creates/updates 2 files in the workspace. Together these files
     * contain the Sloeber project configuration info The info is split into 2 files
     * because you probably do not want to add all the info to a version control
     * tool.
     * 
     * .sproject is the file you can add to a version control sloeber.cfg is the
     * file with settings you do not want to add to version control
     * 
     * @param project
     *            the project to store the data for
     */
    private synchronized void createSloeberConfigFiles(Map<String, String> configs) {

        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace.isTreeLocked()) {
            // we cant save now do it later
            myNeedToPersist = true;
            return;
        }

        Map<String, String> configVars = new TreeMap<>();
        Map<String, String> versionVars = new TreeMap<>();

        for (Entry<String, String> confDesc : configs.entrySet()) {
            String confName = confDesc.getKey();
            BoardDescription boardDescription = myBoardDescriptions.get(confName);
            CompileDescription compileDescription = myCompileDescriptions.get(confName);
            OtherDescription otherDescription = myOtherDescriptions.get(confName);
            // when a new configuration has been created not in project properties
            // the descriptions can be null
            // first get a config to copy from
            Iterator<Entry<String, BoardDescription>> iterator = myBoardDescriptions.entrySet().iterator();
            Entry<String, BoardDescription> actualValue = iterator.next();
            String copyConfKey = actualValue.getKey();
            if (null == boardDescription) {
                boardDescription = new BoardDescription(myBoardDescriptions.get(copyConfKey));
                myBoardDescriptions.put(confName, boardDescription);
            }
            if (null == compileDescription) {
                compileDescription = new CompileDescription(myCompileDescriptions.get(copyConfKey));
                myCompileDescriptions.put(confName, compileDescription);
            }
            if (null == otherDescription) {
                otherDescription = new OtherDescription(myOtherDescriptions.get(copyConfKey));
                myOtherDescriptions.put(confName, otherDescription);
            }
            String boardPrefix = getBoardPrefix(confName);
            String compPrefix = getCompilePrefix(confName);
            String otherPrefix = getOtherPrefix(confName);

            configVars.putAll(boardDescription.getEnvVarsConfig(boardPrefix));
            configVars.putAll(compileDescription.getEnvVarsConfig(compPrefix));
            configVars.putAll(otherDescription.getEnvVarsConfig(otherPrefix));

            if (otherDescription.IsVersionControlled()) {
                versionVars.putAll(boardDescription.getEnvVarsVersion(boardPrefix));
                versionVars.putAll(compileDescription.getEnvVarsVersion(compPrefix));
                versionVars.putAll(otherDescription.getEnvVarsVersion(otherPrefix));
            }
        }

        try {
            storeConfigurationFile(getConfigVersionFile(), versionVars);
            storeConfigurationFile(getConfigLocalFile(), configVars);
            myNeedToPersist = false;
        } catch (Exception e) {
            Common.log(new Status(IStatus.ERROR, io.sloeber.core.Activator.getId(),
                    "failed to save the sloeber config files", e)); //$NON-NLS-1$
            myNeedToPersist = true;
        }

    }

    private static void storeConfigurationFile(IFile file, Map<String, String> vars) throws Exception {
        String content = EMPTY;
        for (Entry<String, String> curLine : vars.entrySet()) {
            content += curLine.getKey() + '=' + curLine.getValue() + '\n';
        }

        if (file.exists()) {
            // if the filecontent hasn't changed=>do nothing
            try {
                Path filePath = Path.of(file.getLocation().toOSString());
                String fileContent = Files.readString(filePath);
                if (content.equals(fileContent)) {
                    return;
                }
            } catch (IOException e) {
                // Don't care as a optimization didn't work
                e.printStackTrace();
            }
            file.delete(true, null);
        }

        if (!file.exists() && (!content.isBlank())) {
            ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes());
            file.create(stream, true, null);
        }

    }

    /**
     * method to switch the board in a given configuration This method assumes the
     * configuration description is a valid arduino confiuguration description and
     * only the board descriptor changed
     * 
     * @param confDesc
     * @param boardDescription
     */
    public void setBoardDescription(String confDescName, BoardDescription boardDescription, boolean force) {
        BoardDescription oldBoardDescription = myBoardDescriptions.get(confDescName);
        if (!force) {
            if (boardDescription.equals(oldBoardDescription)) {
                return;
            }
        }
        if (boardDescription.needsRebuild(oldBoardDescription)) {
            Helpers.deleteBuildFolder(myProject, confDescName);
        }
        myBoardDescriptions.put(confDescName, boardDescription);
        isDirty = true;
    }

    private void setEnvVars(String configName, Map<String, String> envVars) {
        CCorePlugin cCorePlugin = CCorePlugin.getDefault();
        ICProjectDescription prjCDesc = cCorePlugin.getProjectDescription(myProject);
        setEnvVars(prjCDesc, configName, envVars);
    }

    private static void setEnvVars(ICProjectDescription prjCDesc, String configName, Map<String, String> envVars) {
        ICConfigurationDescription confDesc = prjCDesc.getConfigurationByName(configName);
        IConfiguration configuration = ManagedBuildManager.getConfigurationForDescription(confDesc);
        if (configuration != null) {
            SloeberConfigurationVariableSupplier varSup = (SloeberConfigurationVariableSupplier) configuration
                    .getEnvironmentVariableSupplier();
            varSup.setEnvVars(configuration, envVars);
        }
    }

    /**
     * get the Arduino project description based on a project description
     * 
     * @param project
     * @param allowNull
     *            set true if a null response is ok
     * @return
     */
    public static synchronized SloeberProject getSloeberProject(IProject project, boolean allowNull) {

        if (project.isOpen() && project.getLocation().toFile().exists()) {
            if (Sketch.isSketch(project)) {
                Object sessionProperty = null;
                try {
                    sessionProperty = project.getSessionProperty(sloeberQualifiedName);
                    if (null != sessionProperty) {
                        SloeberProject ret = (SloeberProject) sessionProperty;
                        return ret;
                    }
                } catch (CoreException e) {
                    e.printStackTrace();
                }
                if (!allowNull) {
                    SloeberProject ret = new SloeberProject(project);
                    return ret;
                }
            }
        }
        return null;
    }

    public void setCompileDescription(String confDescName, CompileDescription compileDescription) {

        CompileDescription oldCompileDescription = myCompileDescriptions.get(confDescName);
        if (compileDescription.needsRebuild(oldCompileDescription)) {
            Helpers.deleteBuildFolder(myProject, confDescName);
        }
        myCompileDescriptions.put(confDescName, compileDescription);
        isDirty = true;
    }

    public void setOtherDescription(String confDescName, OtherDescription otherDesc) {
        try {
            myOtherDescriptions.put(confDescName, otherDesc);
            isDirty = true;
        } catch (Exception e) {
            e.printStackTrace();
            Common.log(new Status(IStatus.ERROR, io.sloeber.core.Activator.getId(), "failed to save the board settings", //$NON-NLS-1$
                    e));
        }

    }

    /**
     * Method that tries to give you the boardDescription settings for this
     * project/configuration This method tries folowing things 1)memory (after 2 or
     * 3) 2)configuration files in the project (at project creation) 3)CDT
     * environment variables (to update projects created by previous versions of
     * Sloeber)
     * 
     * @param confDesc
     * @return
     */
    public BoardDescription getBoardDescription(String confDescName, boolean allowNull) {
        if (!allowNull) {
            configure();
        }
        return myBoardDescriptions.get(confDescName);
    }

    public CompileDescription getCompileDescription(String confDescName, boolean allowNull) {
        if (!allowNull) {
            configure();
        }
        return myCompileDescriptions.get(confDescName);
    }

    public OtherDescription getOtherDescription(String confDescName, boolean allowNull) {
        if (!allowNull) {
            configure();
        }
        return myOtherDescriptions.get(confDescName);
    }

    /**
     * get the text for the decorator
     * 
     * @param text
     * @return
     */
    public String getDecoratedText(String text) {
        ICProjectDescription prjDesc = CoreModel.getDefault().getProjectDescription(myProject);
        if (prjDesc != null) {
            ICConfigurationDescription confDesc = prjDesc.getActiveConfiguration();
            if (confDesc != null) {
                // do not use getBoardDescriptor below as this will cause a infinite loop at
                // project creation
                BoardDescription boardDescriptor = myBoardDescriptions.get(getConfigKey(confDesc));
                if (boardDescriptor == null) {
                    return text + " Project not configured"; //$NON-NLS-1$
                }
                String boardName = boardDescriptor.getBoardName();
                String portName = boardDescriptor.getActualUploadPort();
                if (portName.isEmpty()) {
                    portName = Messages.decorator_no_port;
                }
                if (boardName.isEmpty()) {
                    boardName = Messages.decorator_no_platform;
                }

                return text + ' ' + boardName + ' ' + ':' + portName;
            }
        }
        return text;
    }

    private static String getBoardPrefix(String confDescName) {
        return CONFIG_DOT + confDescName + DOT + "board."; //$NON-NLS-1$
    }

    private static String getCompilePrefix(String confDescName) {
        return CONFIG_DOT + confDescName + DOT + "compile."; //$NON-NLS-1$
    }

    private static String getOtherPrefix(String confDescName) {
        return CONFIG_DOT + confDescName + DOT + "other."; //$NON-NLS-1$
    }

    private IFile getConfigVersionFile() {
        return myProject.getFile(SLOEBER_CFG);
    }

    private IFile getConfigLocalFile() {
        return myProject.getFile(".sproject"); //$NON-NLS-1$
    }

    public void configChangeAboutToApply(ICProjectDescription newProjDesc, ICProjectDescription oldProjDesc) {
        ICConfigurationDescription newActiveConfig = newProjDesc.getActiveConfiguration();
        ICConfigurationDescription oldActiveConfig = oldProjDesc.getActiveConfiguration();

        List<ICConfigurationDescription> renamedConfigs = new LinkedList<>();

        // make sure the dirty flag is set when needed
        if (!isDirty) {
            // set dirty when the number of configurations changed
            int newNumberOfConfigs = newProjDesc.getConfigurations().length;
            int oldNumberOfConfigs = oldProjDesc.getConfigurations().length;
            if (newNumberOfConfigs != oldNumberOfConfigs) {
                isDirty = true;
            } else {
                // set dirty if a configname changed
                for (ICConfigurationDescription curConfig : newProjDesc.getConfigurations()) {
                    if (oldProjDesc.getConfigurationByName(curConfig.getName()) == null) {
                        ICConfigurationDescription renamedConfig = oldProjDesc.getConfigurationById(curConfig.getId());
                        if (renamedConfig != null) {
                            renamedConfigs.add(curConfig);
                            String oldKey = getConfigKey(renamedConfig);
                            String newKey = getConfigKey(curConfig);
                            Helpers.deleteBuildFolder(myProject, renamedConfig.getName());
                            BoardDescription boardDesc = myBoardDescriptions.get(oldKey);
                            myBoardDescriptions.put(newKey, boardDesc);
                            myBoardDescriptions.remove(oldKey);

                            CompileDescription compDesc = myCompileDescriptions.get(oldKey);
                            myCompileDescriptions.put(newKey, compDesc);
                            myCompileDescriptions.remove(oldKey);

                            OtherDescription otherDesc = myOtherDescriptions.get(oldKey);
                            myOtherDescriptions.put(newKey, otherDesc);
                            myOtherDescriptions.remove(oldKey);
                        }

                        isDirty = true;
                    }
                }
            }
        }
        // in many cases we also need to set the active config
        boolean needsConfigSet = myNeedsClean || isDirty
                || !newActiveConfig.getName().equals(oldActiveConfig.getName());

        configure(newProjDesc, true);
        if (!renamedConfigs.isEmpty()) {
            // a extra setting of the environment vars is neede because the config was not
            // found
            // due to the rename
            for (ICConfigurationDescription curConfig : renamedConfigs) {
                String curConfigKey = getConfigKey(curConfig);
                setEnvVars(newProjDesc, curConfigKey, getEnvVars(curConfigKey));
            }
        }
        if (needsConfigSet) {
            setActiveConfigInRunnable(newActiveConfig);
        }

    }

    /**
     * Call this method when the sloeber.cfg file changed
     * 
     */
    public void sloeberCfgChanged() {
        CCorePlugin cCorePlugin = CCorePlugin.getDefault();
        ICProjectDescription projDesc = cCorePlugin.getProjectDescription(myProject);
        ICConfigurationDescription activeConfig = projDesc.getActiveConfiguration();
        isDirty = true;
        boolean projDescNeedsSaving = configure(projDesc, true);
        Helpers.deleteBuildFolder(myProject, activeConfig.getName());
        projDescNeedsSaving = projDescNeedsSaving || setActiveConfig(activeConfig);
        if (projDescNeedsSaving) {
            try {
                cCorePlugin.setProjectDescription(myProject, projDesc);
            } catch (CoreException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * When a board has been installed it may be that a boardDescription needs to
     * reload the txt file
     */
    public static void reloadTxtFile() {
        final IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject curProject : workspaceRoot.getProjects()) {
            if (curProject.isOpen()) {
                SloeberProject sloeberProject = getSloeberProject(curProject, true);
                if (sloeberProject != null) {
                    sloeberProject.internalReloadTxtFile();
                }
            }
        }
    }

    /**
     * When a board has been installed it may be that a boardDescription needs to
     * reload the txt file
     */
    private void internalReloadTxtFile() {
        for (BoardDescription curBoardDescription : myBoardDescriptions.values()) {
            curBoardDescription.reloadTxtFile();
        }

    }

    private static Map<String, String> getConfigs(ICProjectDescription prjCDesc) {
        Map<String, String> ret = new HashMap<>();
        for (ICConfigurationDescription curconfig : prjCDesc.getConfigurations()) {
            ret.put(curconfig.getName(), curconfig.getId());
        }
        return ret;
    }

    private Collection<String> getCfgKeys(Map<String, String> configs) {
        return configs.keySet();
    }

    private static String getConfigKey(ICConfigurationDescription configDesc) {
        return configDesc.getName();
    }
}
