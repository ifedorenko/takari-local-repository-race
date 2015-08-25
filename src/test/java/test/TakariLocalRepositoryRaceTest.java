package test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.maven.cli.MavenCli;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.logging.console.ConsoleLoggerManager;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class TakariLocalRepositoryRaceTest {

  private DefaultPlexusContainer container;
  private RepositorySystem repositorySystem;
  private MavenExecutionRequestPopulator populator;
  private SettingsBuilder settingsBuilder;
  private DefaultRepositorySystemSessionFactory repositorySessionFactory;

  @Before
  public void setupMavenRuntime() throws Exception {
    final ContainerConfiguration mavenCoreCC = new DefaultContainerConfiguration();
    mavenCoreCC.setClassWorld(new ClassWorld("plexus.core", ClassWorld.class.getClassLoader()));
    mavenCoreCC.setClassPathScanning(PlexusConstants.SCANNING_INDEX).setAutoWiring(true);
    mavenCoreCC.setName("mavenCore");

    this.container = new DefaultPlexusContainer(mavenCoreCC);
    this.container.setLoggerManager(new ConsoleLoggerManager());

    this.repositorySystem = container.lookup(RepositorySystem.class);
    this.populator = container.lookup(MavenExecutionRequestPopulator.class);
    this.settingsBuilder = container.lookup(SettingsBuilder.class);

    this.repositorySessionFactory = container.lookup(DefaultRepositorySystemSessionFactory.class);
  }

  @After
  public void shutdownMavenRuntime() {
    this.repositorySessionFactory = null;
    this.settingsBuilder = null;
    this.populator = null;
    this.repositorySystem = null;

    this.container.dispose();
    this.container = null;
  }

  @Test
  public void testConcurrentDependencyResolution() throws Exception {

    // artifact repository urls
    final List<String> urls = ImmutableList.of( //
        "https://repository.takari.io/content/groups/public" //
        , "file://" + new File("src/repository").getCanonicalPath() //
    );

    // artifacts to resolve
    final List<String> artifacts = ImmutableList.of( //
        // this artifact comes from src/repository
        "test-groupId:test-artifactId:jar:1" //

    // the following artifacts come from remote repository
    , "junit:junit:jar:3.8.1" //
        , "junit:junit:jar:3.8.2" //
        , "junit:junit:jar:4.11" //
        , "junit:junit:jar:4.12");

    final File localRepositoryPath = new File("target/localrepo").getCanonicalFile();

    final List<RemoteRepository> repositories = toRemoteRepositories(urls);

    final int iterationCount = 50;
    final int threadCount = 10;

    // each test iteration resolves the artifacts from the repositories on multiple threads
    // concurrently.

    // first artifact is supposed to resolve from second file:// repository, while the rest
    // artifacts are resolved from the first slower http:// repository.

    // resolution of each artifact is first attempted from http:// repository, if this fails, the
    // resolution is the attempted from file:// repository.

    // to avoid unnecessary duplicate remote requests, per-repository not-found cache is maintained
    // by an implementation of o.e.a.i.UpdateCheckManager. the cache is maintained and used by
    // o.e.a.i.i.DefaultArtifactResolver, see #evaluateDownloads and #gatherDownloads.

    // there appears to be a race condition in TakariUpdateCheckManager implementations, when an
    // artifact is not found in one repository, the cache sometimes misreports the artifact as
    // not-found in other repository.

    // in this test the race happens for test-groupId:test-artifactId:jar:1 artifact, which is first
    // attempted to be resolved from http:// repository, not-found there, then attempted to be
    // resolved from file:// repository. the attempt to resolve the artifact from file:// is
    // sometimes incorrectly skipped because the cache reports the artifact as not-found there.

    // the problem does not affect aether-default o..a.i.i.DefaultUpdateCheckManager implementation

    for (int i = 0; i < iterationCount; i++) {
      FileUtils.deleteDirectory(localRepositoryPath);

      final DefaultMavenExecutionRequest executionRequest = createExecutionRequest();
      executionRequest.setLocalRepositoryPath(localRepositoryPath);
      populator.populateDefaults(executionRequest);
      final RepositorySystemSession repositorySession =
          repositorySessionFactory.newRepositorySession(executionRequest);

      List<Thread> threads = new ArrayList<>();

      final CountDownLatch latch = new CountDownLatch(threadCount);
      final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<Exception>());

      for (int t = 0; t < threadCount; t++) {
        Thread thread = new Thread(
            () -> resolve(latch, repositorySession, artifacts, repositories, exceptions));
        thread.start();
        threads.add(thread);
      }

      for (Thread thread : threads) {
        thread.join();
      }

      Assert.assertTrue(exceptions.toString(), exceptions.isEmpty());

      System.out.printf("Iteration %d\n", i);
    }
  }

  protected void resolve(CountDownLatch latch, RepositorySystemSession repositorySession,
      List<String> artifacts, List<RemoteRepository> repositories, List<Exception> exceptions) {

    try {
      latch.countDown();
      latch.await();

      List<ArtifactRequest> requests = new ArrayList<>();
      for (String artifact : artifacts) {
        requests.add(new ArtifactRequest(new DefaultArtifact(artifact), repositories, null));
      }
      repositorySystem.resolveArtifacts(repositorySession, requests);
    } catch (Exception e) {
      exceptions.add(e);
    }
  }

  protected List<RemoteRepository> toRemoteRepositories(List<String> urls) throws IOException {
    RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY,
        RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
    List<RemoteRepository> repositories = new ArrayList<>();
    for (String url : urls) {
      repositories.add(
          new RemoteRepository.Builder("default", "default", url).setReleasePolicy(policy).build());
    }
    return repositories;
  }

  private DefaultMavenExecutionRequest createExecutionRequest() throws Exception {
    SettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
    settingsRequest.setUserSettingsFile(getUserSettingsFile());
    settingsRequest.setGlobalSettingsFile(MavenCli.DEFAULT_GLOBAL_SETTINGS_FILE);

    DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    request.setUserSettingsFile(settingsRequest.getUserSettingsFile());
    request.setGlobalSettingsFile(settingsRequest.getGlobalSettingsFile());
    request.setSystemProperties(System.getProperties());
    populator.populateFromSettings(request,
        settingsBuilder.build(settingsRequest).getEffectiveSettings());
    request.setCacheNotFound(true); // see MavenCli#populateRequest
    return request;
  }

  private File getUserSettingsFile() throws IOException {
    String userHome = System.getProperty("user.home");
    if (userHome == null) {
      throw new IllegalStateException();
    }
    return new File(userHome, ".m2/settings.xml").getCanonicalFile();
  }
}
