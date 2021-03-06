/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.Buildable;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BuildDependenciesVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.util.Collection;

public class TestArtifactSet implements ResolvedArtifactSet {
    private final AttributeContainer variant;
    private final ImmutableSet<ResolvedArtifact> artifacts;

    private TestArtifactSet(AttributeContainer variant, Collection<? extends ResolvedArtifact> artifacts) {
        this.variant = variant;
        this.artifacts = ImmutableSet.copyOf(artifacts);
    }

    public static ResolvedArtifactSet create(AttributeContainer variantAttributes, Collection<? extends ResolvedArtifact> artifacts) {
        return new TestArtifactSet(variantAttributes, artifacts);
    }

    @Override
    public Completion startVisit(BuildOperationQueue<RunnableBuildOperation> actions, AsyncArtifactListener listener) {
        return new Completion() {
            @Override
            public void visit(ArtifactVisitor visitor) {
                for (ResolvedArtifact artifact : artifacts) {
                    visitor.visitArtifact(variant, artifact);
                }
            }
        };
    }

    @Override
    public void collectBuildDependencies(BuildDependenciesVisitor visitor) {
        for (ResolvedArtifact artifact : artifacts) {
            visitor.visitDependency(((Buildable) artifact).getBuildDependencies());
        }
    }
}
