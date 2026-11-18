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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Read-only CDT model evidence from the ORIGINAL Eclipse CDT source owner.
 *
 * <p>This observes one exact CDT configuration's native source entries,
 * folder/file language settings, resolved scanner entries and declared build
 * settings. It never compiles, mutates project settings, opens include files,
 * resolves linker artifacts or claims to have captured exact compiler argv.
 * The JDT and Eclipse build configuration names are independent dimensions.</p>
 *
 * <p>The resulting hash is an identity of observed CDT metadata, not a
 * complete filesystem, ABI or compiler success proof. It cannot authorize
 * Maven/Gradle task skipping, cache restoration or source refactoring.</p>
 */
public final class M3CdtCompilerFacts {
    public static final String SCHEMA = "M3-CDT-COMPILER-FACTS-V1";
    public static final int MAX_RESOURCES = 8192;
    public static final int MAX_LANGUAGES_PER_RESOURCE = 64;
    public static final int MAX_ENTRIES = 262_144;
    private static final int MAX_ID = 4096;
    private static final int[] LANGUAGE_KINDS = {
            ICSettingEntry.INCLUDE_PATH, ICSettingEntry.INCLUDE_FILE,
            ICSettingEntry.MACRO, ICSettingEntry.MACRO_FILE,
            ICSettingEntry.LIBRARY_PATH, ICSettingEntry.LIBRARY_FILE};

    private M3CdtCompilerFacts() {}

    /**
     * Snapshot of original CDT model declarations and resolved scanner facts
     * bound to the calling Eclipse/m2e invocation. A resolved scanner fact is
     * NOT proof that an external build command used those exact options.
     */
    public record Snapshot(String project, String invocation, String eclipseSelected,
            String cdtConfigId, String cdtConfigName, String cdtActiveConfigId,
            String buildSystemId, String scannerSha256, String sourcesSha256,
            String buildSettingSha256, int resourceCount, int languageCount,
            int entryCount) {
        public Snapshot {
            project = valid(project, false);
            invocation = valid(invocation, false);
            if (!UUID.fromString(invocation).toString().equals(invocation)) {
                throw new IllegalArgumentException("noncanonical Eclipse build invocation");
            }
            eclipseSelected = valid(eclipseSelected, true);
            cdtConfigId = valid(cdtConfigId, false);
            cdtConfigName = valid(cdtConfigName, false);
            cdtActiveConfigId = valid(cdtActiveConfigId, false);
            buildSystemId = valid(buildSystemId, false);
            scannerSha256 = sha(scannerSha256);
            sourcesSha256 = sha(sourcesSha256);
            buildSettingSha256 = sha(buildSettingSha256);
            if (resourceCount < 0 || resourceCount > MAX_RESOURCES
                    || languageCount < 0 || languageCount > MAX_RESOURCES * MAX_LANGUAGES_PER_RESOURCE
                    || entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new IllegalArgumentException("invalid CDT scanner metadata counts");
            }
        }

        public byte[] toExchangeBytes() {
            StringBuilder out = new StringBuilder(SCHEMA).append('\n')
                    .append("provider\teclipse.cdt\n")
                    .append("project64\t").append(b64(project)).append('\n')
                    .append("invocation\t").append(invocation).append('\n')
                    .append("eclipseSelected64\t").append(b64(eclipseSelected)).append('\n')
                    .append("cdtConfigId64\t").append(b64(cdtConfigId)).append('\n')
                    .append("cdtConfigName64\t").append(b64(cdtConfigName)).append('\n')
                    .append("cdtActiveId64\t").append(b64(cdtActiveConfigId)).append('\n')
                    .append("buildSystem64\t").append(b64(buildSystemId)).append('\n')
                    .append("scannerSha256\t").append(scannerSha256).append('\n')
                    .append("sourcesSha256\t").append(sourcesSha256).append('\n')
                    .append("buildSettingSha256\t").append(buildSettingSha256).append('\n')
                    .append("resourceCount\t").append(resourceCount).append('\n')
                    .append("languageCount\t").append(languageCount).append('\n')
                    .append("entryCount\t").append(entryCount).append('\n');
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    /**
     * Capture CDT's cached, read-only original project settings, selecting the
     * EXACT CDT configuration ID supplied by the native CDT build owner.
     * Never equate an Eclipse IBuildConfiguration name with a CDT ID.
     */
    public static Snapshot capture(IProject project, String selectedCdtId,
            String eclipseSelected, String invocation, IProgressMonitor monitor) {
        Objects.requireNonNull(project, "project");
        canceled(monitor);
        ICProjectDescription original = CCorePlugin.getDefault().getProjectDescription(project, false);
        if (original == null) {
            throw new IllegalArgumentException("original CDT project description is unavailable");
        }
        if (original.getProject() == null || !project.getName().equals(original.getProject().getName())) {
            throw new IllegalArgumentException("foreign CDT project description");
        }
        return capture(original, project.getName(), selectedCdtId,
                eclipseSelected, invocation, monitor);
    }

    /**
     * Package-visible original-model entry for deterministic CDT core tests.
     * No mutable project setting is returned or modified.
     */
    static Snapshot capture(ICProjectDescription description, String project,
            String selectedCdtId, String eclipseSelected, String invocation,
            IProgressMonitor monitor) {
        Objects.requireNonNull(description, "description");
        project = valid(project, false);
        selectedCdtId = valid(selectedCdtId, false);
        canceled(monitor);
        ICConfigurationDescription selected = description.getConfigurationById(selectedCdtId);
        ICConfigurationDescription active = description.getActiveConfiguration();
        if (selected == null || active == null || !selectedCdtId.equals(selected.getId())) {
            throw new IllegalArgumentException("selected/active original CDT configurations unavailable");
        }
        MessageDigest scanner = digest();
        MessageDigest sources = digest();
        MessageDigest build = digest();
        frame(scanner, "M3-CDT-SCANNER-V1");
        frame(sources, "M3-CDT-SOURCES-V1");
        frame(build, "M3-CDT-BUILD-SETTING-V1");
        frame(scanner, selected.getId());
        frame(scanner, selected.getBuildSystemId());

        ICSourceEntry[] inputRoots = Objects.requireNonNull(selected.getResolvedSourceEntries(),
                "resolved CDT source entries");
        if (inputRoots.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many CDT source roots");
        }
        entries(sources, inputRoots);
        ICBuildSetting setting = Objects.requireNonNull(selected.getBuildSetting(),
                "original CDT build setting");
        frame(build, portable(setting.getBuilderCWD()));
        entries(build, Objects.requireNonNull(setting.getResolvedOutputDirectories(),
                "CDT output directories"));
        strings(build, setting.getErrorParserIDs());
        ICTargetPlatformSetting target = selected.getTargetPlatformSetting();
        strings(build, target == null ? null : target.getBinaryParserIds());
        Map<String, String> references = new TreeMap<>(Objects.requireNonNull(
                selected.getReferenceInfo(), "CDT reference graph"));
        number(build, references.size());
        for (var ref : references.entrySet()) {
            frame(build, ref.getKey());
            frame(build, ref.getValue());
        }

        ICResourceDescription[] descriptions = Objects.requireNonNull(
                selected.getResourceDescriptions(), "CDT source-resource descriptions");
        if (descriptions.length > MAX_RESOURCES) {
            throw new IllegalArgumentException("too many CDT resource descriptions");
        }
        // CDT's resource order is not semantically significant; the order of
        // entries INSIDE each language setting IS preserved.
        Map<String, ICResourceDescription> byPath = new TreeMap<>();
        for (ICResourceDescription resource : descriptions) {
            Objects.requireNonNull(resource, "CDT resource description");
            String key = portable(resource.getPath());
            if (byPath.putIfAbsent(key, resource) != null) {
                throw new IllegalArgumentException("ambiguous CDT resource settings: " + key);
            }
        }
        int languageCount = 0;
        long entryCount = 0;
        number(scanner, byPath.size());
        for (var item : byPath.entrySet()) {
            canceled(monitor);
            frame(scanner, item.getKey());
            ICResourceDescription resource = item.getValue();
            number(scanner, resource.isExcluded() ? 1 : 0);
            ICLanguageSetting[] languages;
            if (resource instanceof ICFolderDescription folder) {
                languages = Objects.requireNonNull(folder.getLanguageSettings(), "CDT language list");
            } else if (resource instanceof ICFileDescription file) {
                ICLanguageSetting one = file.getLanguageSetting();
                languages = one == null ? new ICLanguageSetting[0] : new ICLanguageSetting[]{one};
            } else {
                throw new IllegalArgumentException("unknown original CDT resource kind");
            }
            if (languages.length > MAX_LANGUAGES_PER_RESOURCE) {
                throw new IllegalArgumentException("too many languages in CDT resource description");
            }
            number(scanner, languages.length);
            for (ICLanguageSetting language : languages) {
                Objects.requireNonNull(language, "CDT language setting");
                if (++languageCount > MAX_RESOURCES * MAX_LANGUAGES_PER_RESOURCE) {
                    throw new IllegalArgumentException("CDT language metadata budget exceeded");
                }
                frame(scanner, language.getLanguageId());
                strings(scanner, language.getSourceContentTypeIds());
                strings(scanner, language.getSourceExtensions());
                for (int kind : LANGUAGE_KINDS) {
                    number(scanner, kind);
                    boolean supported = language.supportsEntryKind(kind);
                    number(scanner, supported ? 1 : 0);
                    if (!supported) continue;
                    ICLanguageSettingEntry[] declared = language.getSettingEntries(kind);
                    ICLanguageSettingEntry[] resolved = language.getResolvedSettingEntries(kind);
                    entryCount += entries(scanner, declared);
                    entryCount += entries(scanner, resolved);
                    if (entryCount > MAX_ENTRIES) {
                        throw new IllegalArgumentException("CDT scanner metadata entry budget exceeded");
                    }
                }
            }
        }
        canceled(monitor);
        return new Snapshot(project, invocation, eclipseSelected,
                selected.getId(), selected.getName(), active.getId(),
                selected.getBuildSystemId(), HexFormat.of().formatHex(scanner.digest()),
                HexFormat.of().formatHex(sources.digest()), HexFormat.of().formatHex(build.digest()),
                byPath.size(), languageCount, (int) entryCount);
    }

    /**
     * Explicit export only, never a CDI/CDT build side effect. An original
     * workspace owner must provide a unique generation-local destination.
     * Repeated equal bytes are idempotent; conflicting bytes and symlinks fail.
     */
    public static Path writeSnapshot(Snapshot snapshot, Path projectRoot, Path packetFile)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        Path requested = Objects.requireNonNull(packetFile, "packetFile");
        Path file = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IOException("CDT facts packet must stay within the original project");
        }
        Path path = root;
        for (Path part : root.relativize(file)) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) {
                throw new IOException("CDT facts packet must not traverse a symbolic link");
            }
        }
        if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CDT facts packet parent directory must already exist");
        }
        byte[] expected = snapshot.toExchangeBytes();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != expected.length
                    || !Arrays.equals(Files.readAllBytes(file), expected)) {
                throw new IOException("refusing conflicting original CDT facts packet");
            }
            return file;
        }
        Files.write(file, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        return file;
    }

    /** Null and empty CDT arrays are distinct original-owner observations. */
    private static int entries(MessageDigest md, ICSettingEntry[] entries) {
        if (entries == null) { number(md, -1); return 0; }
        if (entries.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("CDT setting entry count exceeded");
        }
        number(md, entries.length);
        for (ICSettingEntry entry : entries) {
            Objects.requireNonNull(entry, "CDT setting entry");
            number(md, entry.getKind());
            number(md, entry.getFlags());
            frame(md, entry.getName());
            frame(md, entry.getValue());
            // Paths and exclusions are part of the original effective source
            // and scanner settings; names alone cannot identify a source root.
            if (entry instanceof ICPathEntry path) {
                number(md, 1);
                frame(md, portable(path.getFullPath()));
                frame(md, portable(path.getLocation()));
                number(md, path.isValueWorkspacePath() ? 1 : 0);
            } else {
                number(md, 0);
            }
            if (entry instanceof ICExclusionPatternPathEntry excluded) {
                IPath[] patterns = excluded.getExclusionPatterns();
                if (patterns == null) {
                    number(md, -1);
                } else {
                    if (patterns.length > MAX_ENTRIES) {
                        throw new IllegalArgumentException("CDT exclusion pattern budget exceeded");
                    }
                    number(md, patterns.length);
                    for (IPath pattern : patterns) frame(md, portable(pattern));
                }
            } else {
                number(md, -1);
            }
        }
        return entries.length;
    }

    private static void strings(MessageDigest md, String[] values) {
        if (values == null) { number(md, -1); return; }
        if (values.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("CDT setting string budget exceeded");
        }
        number(md, values.length);
        for (String value : values) frame(md, value);
    }

    private static String portable(IPath path) {
        return path == null ? "" : valid(path.toPortableString(), true);
    }

    private static String valid(String value, boolean allowEmpty) {
        Objects.requireNonNull(value, "CDT identity");
        if ((!allowEmpty && value.isBlank()) || value.length() > MAX_ID
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid CDT metadata");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid CDT Unicode metadata");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid CDT Unicode metadata");
            }
        }
        return value;
    }

    private static String sha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid original CDT digest");
        }
        return value;
    }

    private static String b64(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void frame(MessageDigest md, String text) {
        // The CDT API explicitly allows a null language ID and, for certain
        // setting kinds, absent entry values. Keep null distinct from "".
        if (text == null) {
            number(md, -1);
            return;
        }
        byte[] bytes = valid(text, true).getBytes(StandardCharsets.UTF_8);
        number(md, bytes.length);
        md.update(bytes);
    }

    private static void number(MessageDigest md, int value) {
        md.update((byte) (value >>> 24));
        md.update((byte) (value >>> 16));
        md.update((byte) (value >>> 8));
        md.update((byte) value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void canceled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) throw new OperationCanceledException();
    }
}
