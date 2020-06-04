/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.query.ConfiguredQueryBuildTarget;
import com.facebook.buck.query.ConfiguredQueryTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class TargetPatternEvaluator {
  private static final Logger LOG = Logger.get(TargetPatternEvaluator.class);

  private final TargetUniverse targetUniverse;
  private final ParsingContext parsingContext;
  private final AbsPath projectRoot;
  private final CommandLineTargetNodeSpecParser targetNodeSpecParser;
  private final BuckConfig buckConfig;
  private final Cell rootCell;
  private final Optional<TargetConfiguration> targetConfiguration;

  private Map<String, ImmutableSet<ConfiguredQueryTarget>> resolvedTargets = new HashMap<>();

  public TargetPatternEvaluator(
      TargetUniverse targetUniverse,
      Cell rootCell,
      Path absoluteClientWorkingDir,
      BuckConfig buckConfig,
      ParsingContext parsingContext,
      Optional<TargetConfiguration> targetConfiguration) {
    this.targetUniverse = targetUniverse;
    this.rootCell = rootCell;
    this.parsingContext = parsingContext;
    this.buckConfig = buckConfig;
    this.projectRoot = rootCell.getFilesystem().getRootPath();
    this.targetNodeSpecParser =
        new CommandLineTargetNodeSpecParser(
            rootCell,
            absoluteClientWorkingDir,
            buckConfig,
            new BuildTargetMatcherTargetNodeParser());
    this.targetConfiguration = targetConfiguration;
  }

  /** Attempts to parse and load the given collection of patterns. */
  void preloadTargetPatterns(Iterable<String> patterns)
      throws InterruptedException, BuildFileParseException, IOException {
    resolveTargetPatterns(patterns);
  }

  ImmutableMap<String, ImmutableSet<ConfiguredQueryTarget>> resolveTargetPatterns(
      Iterable<String> patterns) throws InterruptedException, BuildFileParseException, IOException {
    ImmutableMap.Builder<String, ImmutableSet<ConfiguredQueryTarget>> resolved =
        ImmutableMap.builder();

    Map<String, String> unresolved = new HashMap<>();
    for (String pattern : patterns) {

      // First check if this pattern was resolved before.
      ImmutableSet<ConfiguredQueryTarget> targets = resolvedTargets.get(pattern);
      if (targets != null) {
        resolved.put(pattern, targets);
        continue;
      }

      // Check if this is an alias.
      ImmutableSet<UnconfiguredBuildTarget> aliasTargets =
          AliasConfig.from(buckConfig).getBuildTargetsForAlias(pattern);
      if (!aliasTargets.isEmpty()) {
        for (UnconfiguredBuildTarget alias : aliasTargets) {
          unresolved.put(alias.getFullyQualifiedName(), pattern);
        }
      } else {
        // Check if the pattern corresponds to a build target or a path.
        // Note: If trying to get a path with a single ':' in it, this /will/ choose to assume a
        // build target, not a file. In general, this is okay as:
        //  1) Most of our functions that take paths are going to be build files and the like, not
        //     something with a ':' in it
        //  2) By putting a ':' in the filename, you're already dooming yourself to never work on
        //     windows. Don't do that.
        if (pattern.contains("//")
            || pattern.contains(":")
            || pattern.endsWith("/...")
            || pattern.equals("...")) {
          unresolved.put(pattern, pattern);
        } else {
          ImmutableSet<ConfiguredQueryTarget> fileTargets = resolveFilePattern(pattern);
          resolved.put(pattern, fileTargets);
          resolvedTargets.put(pattern, fileTargets);
        }
      }
    }

    // Resolve any remaining target patterns using the parser.
    ImmutableMap<String, ImmutableSet<ConfiguredQueryTarget>> results =
        MoreMaps.transformKeys(
            resolveBuildTargetPatterns(ImmutableList.copyOf(unresolved.keySet())),
            Functions.forMap(unresolved));
    resolved.putAll(results);
    resolvedTargets.putAll(results);

    return resolved.build();
  }

  private ImmutableSet<ConfiguredQueryTarget> resolveFilePattern(String pattern)
      throws IOException {
    PathArguments.ReferencedFiles referencedFiles =
        PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, ImmutableList.of(pattern));

    if (!referencedFiles.absoluteNonExistingPaths.isEmpty()) {
      throw new HumanReadableException("%s references non-existing file", pattern);
    }

    return referencedFiles.relativePathsUnderProjectRoot.stream()
        .map(path -> PathSourcePath.of(rootCell.getFilesystem(), path))
        .map(QueryFileTarget::of)
        .collect(ImmutableSortedSet.toImmutableSortedSet(ConfiguredQueryTarget::compare));
  }

  private ImmutableMap<String, ImmutableSet<ConfiguredQueryTarget>> resolveBuildTargetPatterns(
      List<String> patterns) throws InterruptedException, BuildFileParseException {

    // Build up an ordered list of patterns and pass them to the parse to get resolved in one go.
    // The returned list of nodes maintains the spec list ordering.
    List<TargetNodeSpec> specs = new ArrayList<>();
    for (String pattern : patterns) {
      specs.addAll(targetNodeSpecParser.parse(rootCell, pattern));
    }
    ImmutableList<ImmutableSet<BuildTarget>> buildTargets =
        targetUniverse.resolveTargetSpecs(specs, targetConfiguration, parsingContext);
    LOG.verbose("Resolved target patterns %s -> targets %s", patterns, buildTargets);

    // Convert the ordered result into a result map of pattern to set of resolved targets.
    ImmutableMap.Builder<String, ImmutableSet<ConfiguredQueryTarget>> queryTargets =
        ImmutableMap.builder();
    for (int index = 0; index < buildTargets.size(); index++) {
      ImmutableSet<BuildTarget> targets = buildTargets.get(index);
      // Sorting to have predictable results across different java libraries implementations.
      ImmutableSet.Builder<ConfiguredQueryTarget> builder = ImmutableSet.builder();
      for (BuildTarget target : targets) {
        builder.add(ConfiguredQueryBuildTarget.of(target));
      }
      queryTargets.put(patterns.get(index), builder.build());
    }
    return queryTargets.build();
  }
}
