/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Synexia Project.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.cdt.core.settings.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

import org.eclipse.cdt.core.dom.IPDOMManager;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.testplugin.CProjectHelper;
import org.eclipse.cdt.core.testplugin.util.BaseTestCase5;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Original CDT configuration and scanner evidence: no native compiler invoked. */
public class M3CdtCompilerFactsTest extends BaseTestCase5 {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private IProject project;

    @AfterEach
    public void cleanup() throws Exception {
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    @Test
    public void capturesExactOriginalCdtConfigurationWithoutChangingTheProject() throws Exception {
        ICProject original = CProjectHelper.createNewStyleCProject(
                "M3CdtCompilerFactsTest", IPDOMManager.ID_NO_INDEXER);
        project = original.getProject();
        ICProjectDescription description = CoreModel.getDefault().getProjectDescription(project, false);
        ICConfigurationDescription selected = description.getActiveConfiguration();
        boolean originallyModified = description.isModified();
        var before = M3CdtCompilerFacts.capture(
                project, selected.getId(), "Debug", INVOCATION, null);
        assertEquals(project.getName(), before.project());
        assertEquals(selected.getId(), before.cdtConfigId());
        assertEquals(selected.getName(), before.cdtConfigName());
        assertEquals(description.getActiveConfiguration().getId(), before.cdtActiveConfigId());
        assertEquals("Debug", before.eclipseSelected());
        assertEquals(INVOCATION, before.invocation());
        assertTrue(before.scannerSha256().matches("[a-f0-9]{64}"));
        assertTrue(before.sourcesSha256().matches("[a-f0-9]{64}"));
        assertTrue(before.buildSettingSha256().matches("[a-f0-9]{64}"));
        assertFalse(before.authorizesBuildSkipping());
        assertEquals(new String(before.toExchangeBytes(), StandardCharsets.UTF_8),
                new String(M3CdtCompilerFacts.capture(
                        project, selected.getId(), "Debug", INVOCATION, null).toExchangeBytes(),
                        StandardCharsets.UTF_8));
        assertTrue(new String(before.toExchangeBytes(), StandardCharsets.UTF_8)
                .startsWith("M3-CDT-COMPILER-FACTS-V1\nprovider\teclipse.cdt\n"));
        var nextEclipseConfig = M3CdtCompilerFacts.capture(
                project, selected.getId(), "Release", INVOCATION, null);
        assertNotEquals(before.eclipseSelected(), nextEclipseConfig.eclipseSelected());
        assertEquals(before.scannerSha256(), nextEclipseConfig.scannerSha256(),
                "Eclipse IBuildConfiguration is distinct from CDT scanner identity");
        assertEquals(originallyModified, description.isModified(),
                "read-only capture must preserve the original CDT model modification state");
    }

    @Test
    public void selectedCdtConfigurationRemainsDistinctFromTheActiveConfiguration() throws Exception {
        ICProject original = CProjectHelper.createNewStyleCProject(
                "M3CdtCompilerFactsMulti", IPDOMManager.ID_NO_INDEXER);
        project = original.getProject();
        ICProjectDescription writable = CoreModel.getDefault().getProjectDescription(project);
        ICConfigurationDescription initial = writable.getActiveConfiguration();
        String activeId = initial.getId();
        ICConfigurationDescription alternate = writable.createConfiguration(
                "m3.test.cdt.alternate", "Release", initial);
        CoreModel.getDefault().setProjectDescription(project, writable);
        var selected = M3CdtCompilerFacts.capture(project,
                alternate.getId(), "Eclipse-Debug", INVOCATION, null);
        assertEquals(alternate.getId(), selected.cdtConfigId());
        assertEquals("Release", selected.cdtConfigName());
        assertEquals(activeId, selected.cdtActiveConfigId(),
                "the active CDT configuration must not be guessed from the selected one");
        assertEquals("Eclipse-Debug", selected.eclipseSelected());
        assertNotEquals(selected.cdtConfigId(), selected.cdtActiveConfigId());
        assertFalse(selected.authorizesBuildSkipping());
    }

    @Test
    public void producerMatchesTheIndependentMavenCdtPacketFixture() throws Exception {
        var model = new M3CdtCompilerFacts.Snapshot(
                "Mixed", INVOCATION, "Debug", "cdt.native.debug", "Debug (native)",
                "cdt.native.release", "org.eclipse.cdt.managedbuilder",
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 2, 3, 17);
        try (var source = M3CdtCompilerFactsTest.class.getResourceAsStream(
                "/resources/m3-cdt-compiler-facts-v1.fixture")) {
            if (source == null) throw new IOException("missing original CDT golden fixture");
            org.junit.jupiter.api.Assertions.assertArrayEquals(source.readAllBytes(),
                    model.toExchangeBytes(), "CDT and Maven original packet schema must match");
        }
    }

    @Test
    public void foreignConfigurationAndCancellationFailClosed() throws Exception {
        ICProject original = CProjectHelper.createNewStyleCProject(
                "M3CdtCompilerFactsRefusal", IPDOMManager.ID_NO_INDEXER);
        project = original.getProject();
        rejects(IllegalArgumentException.class,
                () -> M3CdtCompilerFacts.capture(
                        project, "vendor.unknown.cdt.config", "Debug", INVOCATION, null));
        NullProgressMonitor canceled = new NullProgressMonitor();
        canceled.setCanceled(true);
        rejects(OperationCanceledException.class,
                () -> M3CdtCompilerFacts.capture(
                        project, "vendor.unknown.cdt.config", "Debug", INVOCATION, canceled));
        rejects(IllegalArgumentException.class,
                () -> new M3CdtCompilerFacts.Snapshot(
                        "Project", INVOCATION, "Debug", "cdt.id", "Debug", "cdt.active",
                        "CDT", "A".repeat(64), "b".repeat(64), "c".repeat(64), 0, 0, 0));
    }
    @FunctionalInterface
    private interface CheckedOperation { void perform() throws Exception; }

    /** Avoid Tycho bundle restrictions on the JUnit Executable package. */
    private static void rejects(Class<? extends Exception> expected, CheckedOperation action)
            throws Exception {
        try {
            action.perform();
        } catch (Exception failure) {
            if (expected.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected original CDT exception: " + expected.getName());
    }
}
