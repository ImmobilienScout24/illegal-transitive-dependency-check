package de.is24.maven.enforcer.rules;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.AbstractArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;


/**
 * Rule enforcing directly declared maven dependencies only
 *
 * @author aschubert
 */
public final class IllegalTransitiveDependencyCheck implements EnforcerRule {
  private static final String NO_CACHE_ID_AVAILABLE = null;
  private static final String OUTPUT_FILE_EXTENSION = ".txt";
  private static final String OUTPUT_FILE_PREFIX = "itd-";

  private ArtifactResolver resolver;

  private ArtifactRepository localRepository;

  private List<ArtifactRepository> remoteRepositories;

  private String outputDirectory;

  private MavenProject project;

  private Log logger;

  private boolean reportOnly;

  private String[] regexIgnoredClasses;

  private boolean useClassesFromLastBuild;

  private boolean suppressTypesFromJavaRuntime;

  @Override
  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    logger = helper.getLog();

    if (reportOnly) {
      logger.info("Flag 'reportOnly' is set. Exceptions from rule will only be reported!");
    }

    if (useClassesFromLastBuild) {
      logger.info("Flag 'useClassesFromLastBuild' is set. Try to use existing output folder.");
    }

    if (suppressTypesFromJavaRuntime) {
      logger.info("Flag 'suppressTypesFromJavaRuntime' is set. Classes available in current Java-runtime will be ignored.");
    }

    initializeArtifactResolver(helper);

    initializeProject(helper);

    final Artifact artifact = resolveArtifact();

    final Repository artifactClassesRepository = ArtifactRepositoryAnalyzer.analyzeArtifacts(logger,
        true,
        suppressTypesFromJavaRuntime,
        regexIgnoredClasses)
      .analyzeArtifacts(Collections.singleton(artifact));

    final Set<Artifact> dependencies = resolveDirectDependencies(artifact);

    final Repository dependenciesClassesRepository = ArtifactRepositoryAnalyzer.analyzeArtifacts(logger,
        false,
        suppressTypesFromJavaRuntime,
        regexIgnoredClasses)
      .analyzeArtifacts(dependencies);

    if (logger.isDebugEnabled()) {
      logger.debug("Artifact's type dependencies are: " + artifactClassesRepository.getDependencies());
      logger.debug("Classes defined in direct dependencies are: " + dependenciesClassesRepository.getTypes());
    }

    final List<String> unresolvedTypes = new ArrayList<String>(artifactClassesRepository.getDependencies());
    unresolvedTypes.removeAll(artifactClassesRepository.getTypes());
    unresolvedTypes.removeAll(dependenciesClassesRepository.getTypes());

    if (unresolvedTypes.isEmpty()) {
      logger.info("No illegal transitive dependencies found in '" + artifact.getId() + "'.");
    } else {
      final String message = buildOutput(artifact, unresolvedTypes);

      writeOutputFile(artifact, message);

      if (reportOnly) {
        logger.error(message);
      } else {
        throw new EnforcerRuleException(message);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Set<Artifact> resolveDirectDependencies(Artifact artifact) {
    final Set<Artifact> dependencies = new HashSet<Artifact>(project.getDependencyArtifacts());
    dependencies.remove(artifact);
    if (logger.isDebugEnabled()){
      logger.debug("Direct dependencies are '" + dependencies + "'.");
    }
    return dependencies;
  }

  @SuppressWarnings("unchecked")
  private void initializeProject(ExpressionEvaluator helper) throws EnforcerRuleException {
    try {
      project = (MavenProject) helper.evaluate("${project}");

      localRepository = (ArtifactRepository) helper.evaluate("${localRepository}");
      remoteRepositories = (List<ArtifactRepository>) helper.evaluate("${project.remoteArtifactRepositories}");

      outputDirectory = (String) helper.evaluate("${project.build.directory}");

    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to locate Maven project and/or repositories!", e);
    }
    logger.debug("Analyze project '" + project + "'.");
  }

  private void initializeArtifactResolver(EnforcerRuleHelper helper) throws EnforcerRuleException {
    try {
      resolver = (ArtifactResolver) helper.getComponent(ArtifactResolver.class);
    } catch (ComponentLookupException e) {
      throw new EnforcerRuleException("Unable to lookup artifact resolver!", e);
    }
  }

  private Artifact resolveArtifact() throws EnforcerRuleException {
    final Artifact artifact = project.getArtifact();
    logger.info("Analyze dependencies of artifact '" + artifact.getId() + "'.");

    // use the current project's target/classes directory as fake artifact..
    if (useClassesFromLastBuild) {
      final File targetClassesDirectory = getTargetClassesDirectory();
      if (targetClassesDirectory != null) {
        artifact.setFile(targetClassesDirectory);
      }
      return artifact;
    }

    return enforceArtifactResolution(artifact);
  }

  private File getTargetClassesDirectory() {
    final Build build = project.getBuild();
    if (build != null) {
      final String classesOutputDirectory = build.getOutputDirectory();
      if (StringUtils.isNotEmpty(classesOutputDirectory)) {
        final File targetClasses = new File(classesOutputDirectory);
        if ( !targetClasses.exists())
        {
            targetClasses.mkdirs();
        }
        logger.debug("Found valid classes directory '" + targetClasses.getAbsolutePath() + "'.");
        return targetClasses;
      }
    }
    return null;
  }

  private Artifact enforceArtifactResolution(Artifact artifact) throws EnforcerRuleException {
    logger.debug("Enforce artifact resolution for project '" + project + "'.");
    try {
      resolver.resolve(artifact, remoteRepositories, localRepository);
      return artifact;
    } catch (AbstractArtifactResolutionException e) {
      final String error = "Unable to resolve artifact '" + artifact.getId() + "'!";
      logger.error(error, e);
      throw new EnforcerRuleException(error, e);
    }
  }

  private static String buildOutput(Artifact artifact, List<String> unresolvedTypes) {
    Collections.sort(unresolvedTypes);

    final StringBuilder illegalDependencies = new StringBuilder();
    illegalDependencies.append("Found ")
                       .append(unresolvedTypes.size())
                       .append(" illegal transitive type dependencies in artifact '")
                       .append(artifact.getId())
                       .append("':\n");

    int k = 1;
    for (String illegalDependency : unresolvedTypes) {
      illegalDependencies.append(k).append(".) ").append(illegalDependency).append("\n");
      k++;
    }
    return illegalDependencies.toString();
  }

  private void writeOutputFile(Artifact artifact, String output) throws EnforcerRuleException {
    if (outputDirectory == null) {
      logger.warn("Project's output directory has not been set, skip writing!");
      return;
    }

    final String outputFilePath = determineOutputFilePath(artifact);
    final File outputFile = new File(outputFilePath);
    final File targetFolder = outputFile.getParentFile();
    if (!targetFolder.exists() && !targetFolder.mkdirs()) {
      final String error = "Unable to create directory '" + targetFolder + "'!";
      logger.error(error);
      throw new EnforcerRuleException(error);
    }

    FileWriter resultFileWriter = null;
    try {
      resultFileWriter = new FileWriter(outputFile);
      resultFileWriter.write(output);
    } catch (IOException e) {
      throw logAndWrapIOException(e, outputFilePath);
    } finally {
      if (resultFileWriter != null) {
        try {
          resultFileWriter.close();
        } catch (IOException e) {
          throw logAndWrapIOException(e, outputFilePath);
        }
      }
    }
  }

  private EnforcerRuleException logAndWrapIOException(IOException e, String outputFilePath) throws EnforcerRuleException {
    final String error = "Unable to write output file '" + outputFilePath + "'!";
    logger.error(error, e);
    return new EnforcerRuleException(error, e);
  }

  private String determineOutputFilePath(Artifact artifact) {
    final String separator = outputDirectory.endsWith("/") ? "" : "/";
    final String formattedArtifactId = artifact.getId().replace(':', '-');
    return outputDirectory + separator + OUTPUT_FILE_PREFIX + formattedArtifactId + OUTPUT_FILE_EXTENSION;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  @Override
  public boolean isResultValid(EnforcerRule enforcerRule) {
    return false;
  }

  @Override
  public String getCacheId() {
    return NO_CACHE_ID_AVAILABLE;
  }

  public void setReportOnly(boolean reportOnly) {
    this.reportOnly = reportOnly;
  }

  public void setRegexIgnoredClasses(String[] regexIgnoredClasses) {
    this.regexIgnoredClasses = regexIgnoredClasses;
  }

  public void setUseClassesFromLastBuild(boolean useClassesFromLastBuild) {
    this.useClassesFromLastBuild = useClassesFromLastBuild;
  }

  public void setSuppressTypesFromJavaRuntime(boolean suppressTypesFromJavaRuntime) {
    this.suppressTypesFromJavaRuntime = suppressTypesFromJavaRuntime;
  }
}
