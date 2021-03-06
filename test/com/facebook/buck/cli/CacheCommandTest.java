/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import org.easymock.EasyMockSupport;
import org.junit.Test;

public class CacheCommandTest extends EasyMockSupport {

  @Test
  public void testRunCommandWithNoArguments() throws IOException, InterruptedException {
    TestConsole console = new TestConsole();
    console.printErrorText("No cache keys specified.");
    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).build();
    CacheCommand cacheCommand = new CacheCommand();
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(1, exitCode);
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(cache.fetchAsync(eq(new RuleKey(ruleKeyHash)), isA(LazyPath.class)))
        .andReturn(Futures.immediateFuture(CacheResult.hit("http", ArtifactCacheMode.http)));
    cache.close();
    expectLastCall();

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    replayAll();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(0, exitCode);
    assertThat(
        console.getTextWrittenToStdErr(),
        startsWith("Successfully downloaded artifact with id " + ruleKeyHash + " at "));
  }

  @Test
  public void testRunCommandAndFetchArtifactsUnsuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(cache.fetchAsync(eq(new RuleKey(ruleKeyHash)), isA(LazyPath.class)))
        .andReturn(Futures.immediateFuture(CacheResult.miss()));
    cache.close();
    expectLastCall();

    TestConsole console = new TestConsole();
    console.printErrorText("Failed to retrieve an artifact with id " + ruleKeyHash + ".");

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    replayAll();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(1, exitCode);
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfullyAndSuperConsole()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(cache.fetchAsync(eq(new RuleKey(ruleKeyHash)), isA(LazyPath.class)))
        .andReturn(Futures.immediateFuture(CacheResult.hit("http", ArtifactCacheMode.http)));
    cache.close();
    expectLastCall();

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();
    SuperConsoleEventBusListener listener =
        createSuperConsole(
            console, commandRunnerParams.getClock(), commandRunnerParams.getBuckEventBus());

    replayAll();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(0, exitCode);
    ImmutableList<String> lines =
        listener.createRenderLinesAtTime(commandRunnerParams.getClock().currentTimeMillis());
    StringBuilder strBuilder = new StringBuilder();
    for (String line : lines) {
      strBuilder.append(line);
      strBuilder.append("\n");
    }
    assertThat(strBuilder.toString(), containsString("Downloaded"));
  }

  private SuperConsoleEventBusListener createSuperConsole(
      Console console, Clock clock, BuckEventBus eventBus) {
    final TimeZone timeZone = TimeZone.getTimeZone("UTC");
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    final Path logPath = vfs.getPath("log.txt");
    final SuperConsoleConfig emptySuperConsoleConfig =
        new SuperConsoleConfig(FakeBuckConfig.builder().build());
    final TestResultSummaryVerbosity silentSummaryVerbosity =
        TestResultSummaryVerbosity.of(false, false);
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            clock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                ImmutableMap.copyOf(System.getenv()), System.getProperties()),
            Optional.empty(),
            Locale.US,
            logPath,
            timeZone,
            0L,
            0L,
            1000L,
            false);
    eventBus.register(listener);
    return listener;
  }
}
