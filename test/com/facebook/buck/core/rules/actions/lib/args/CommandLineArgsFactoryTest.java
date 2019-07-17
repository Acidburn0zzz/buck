/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions.lib.args;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.ImmutableSourceArtifactImpl;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandLineArgsFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void createsCommandLineArgsForArgList() throws LabelSyntaxException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path source = Paths.get("src", "main.cpp");
    assertEquals(
        ImmutableList.of("1", "foo", "//foo:bar", filesystem.resolve(source).toString()),
        CommandLineArgsFactory.from(
                ImmutableList.of(
                    1,
                    "foo",
                    Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
                    ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, source))))
            .getStrings(new ArtifactFilesystem(filesystem))
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void rejectsInvalidCommandLineArgsForArgList() throws LabelSyntaxException {
    thrown.expect(CommandLineArgException.class);
    CommandLineArgsFactory.from(ImmutableList.of(ImmutableList.of()));
  }

  @Test
  public void createsCommandLineArgsForListOfOtherArgs() {
    assertEquals(
        ImmutableList.of("1", "foo", "bar"),
        CommandLineArgsFactory.fromArgs(
                ImmutableList.of(
                    CommandLineArgsFactory.from(ImmutableList.of(1)),
                    CommandLineArgsFactory.from(ImmutableList.of("foo", "bar"))))
            .getStrings(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .collect(ImmutableList.toImmutableList()));
  }
}
