/**
 * Copyright 2018 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.versiontracker.common;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistTest
{
    private Blacklist bl;

    @BeforeEach
    void setup() {
        bl = new Blacklist();
    }

    @Test
    void testSerialization() throws IOException
    {
        bl.addIgnoredVersion("1.2.0", Blacklist.VersionMatcher.EXACT);
        bl.addIgnoredVersion("2\\..*", Blacklist.VersionMatcher.REGEX);

        bl.addIgnoredVersion("group1","artifact1","3.0.0", Blacklist.VersionMatcher.EXACT);
        bl.addIgnoredVersion("group1","artifact1","4\\.0.*", Blacklist.VersionMatcher.REGEX);
        bl.addIgnoredVersion("group1","artifact2",".*", Blacklist.VersionMatcher.REGEX); // all versions of this artifact blacklisted

        bl.addIgnoredVersion("group2", "5.0", Blacklist.VersionMatcher.EXACT);
        bl.addIgnoredVersion("group2", "6\\.0.*", Blacklist.VersionMatcher.REGEX);
        bl.addIgnoredVersion("group1","artifact3",".*", Blacklist.VersionMatcher.REGEX); // all versions of this artifact blacklisted

        Runnable check = () ->
        {
            assertThat(isBlacklisted("test", "blubb", "1.2.0")).isTrue();
            assertThat(isBlacklisted("test", "blubb", "1.3.0")).isFalse();
            assertThat(isBlacklisted("test", "blubb", "2.0")).isTrue();
            assertThat(isBlacklisted("test2", "blubb", "2.0")).isTrue();
            assertThat(isBlacklisted("test2", "blubb2", "2.0")).isTrue();
            assertThat(isBlacklisted("test2", "blubb2", "2-1")).isFalse();

            assertThat(isBlacklisted("group1", "artifact1", "3.0.0")).isTrue();
            assertThat(isBlacklisted("group1", "artifact1", "3.1.0")).isFalse();

            assertThat(isBlacklisted("group1", "artifact1", "4.0")).isTrue();
            assertThat(isBlacklisted("group1", "artifact1", "4.01")).isTrue();
            assertThat(isBlacklisted("group1", "artifact1", "5.01")).isFalse();

            assertThat(isBlacklisted("group2", "artifact1", "5.0")).isTrue();
            assertThat(isBlacklisted("group2", "artifact2", "5.0")).isTrue();
            assertThat(isBlacklisted("group2", "artifact2", "6-0")).isFalse();
            assertThat(isBlacklisted("group2", "artifact1", "6.0")).isTrue();

            assertThat(isBlacklisted("group1", "artifact3", "7.0")).isTrue();
            assertThat(isBlacklisted("group1", "artifact3", "8.0")).isTrue();
        };
        check.run();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (BinarySerializer ser = new BinarySerializer(BinarySerializer.IBuffer.wrap(bout)))
        {
            bl.serialize(ser);
        }
        try (BinarySerializer ser = new BinarySerializer(BinarySerializer.IBuffer.wrap(bout.toByteArray())))
        {
            bl = Blacklist.deserialize(ser);
        }
        check.run();
    }

    private boolean isBlacklisted(String groupId, String artifactId, String version ) {
        final Artifact a = new Artifact();
        a.artifactId = artifactId;
        a.groupId = groupId;
        a.version = version;
        return bl.isArtifactBlacklisted(a);
    }

    @Test
    void testExactVersionOnly()
    {
        bl.addIgnoredVersion("1.0.0", Blacklist.VersionMatcher.EXACT);
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group2", "artifact", "1.0.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group", "artifact2", "1.0.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0.1") ).isFalse();
    }

    @Test
    void testGroupAndVersion()
    {
        bl.addIgnoredVersion("group","1.0.0", Blacklist.VersionMatcher.EXACT);
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group", "artifact2", "1.0.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group2", "artifact", "1.0.0") ).isFalse();
    }

    @Test
    void testExactMatching()
    {
        bl.addIgnoredVersion("group", "artifact", "1.0", Blacklist.VersionMatcher.EXACT);
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group2", "artifact", "1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group", "artifact2", "1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.1") ).isFalse();
    }

    @Test
    void testGroupIdWithWildcardMatching() {
        bl.addIgnoredVersion( "com.voipfuture.*", ".*", Blacklist.VersionMatcher.REGEX );
        assertThat( bl.isVersionBlacklisted("com.voipfuture", "voipmngr-core", "1.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("com.voipfuture.test", "voipmngr-core", "1.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("com.something", "voipmngr-core", "1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("com", "voipmngr-core", "1.0") ).isFalse();
    }

    @Test
    void testRegExPatternMatching()
    {
        bl.addIgnoredVersion("group", "artifact", "1\\.0.*", Blacklist.VersionMatcher.REGEX);
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "1.0.1") ).isTrue();
        assertThat( bl.isVersionBlacklisted("group2", "artifact", "1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group", "artifact2", "1.0") ).isFalse();
        assertThat( bl.isVersionBlacklisted("group", "artifact", "2.0") ).isFalse();
    }
}