/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: EPL-2.0
 */
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Standalone original CDT JUnit 5/Tycho receipt gate.
 * No Maven test.failure.ignore setting can convert skipped native CDT cases
 * into a successful original-owner acceptance certificate.
 */
public final class M3CdtTestReceiptMain {
    private static final String TEST = "M3CdtCompilerFactsTest";
    private static final Set<String> REQUIRED = Set.of(
            "capturesExactOriginalCdtConfigurationWithoutChangingTheProject",
            "selectedCdtConfigurationRemainsDistinctFromTheActiveConfiguration",
            "producerMatchesTheIndependentMavenCdtPacketFixture",
            "foreignConfigurationAndCancellationFailClosed");

    private M3CdtTestReceiptMain() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("usage: M3CdtTestReceiptMain <CDT reports directory>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("original CDT test report directory is missing");
        }
        Set<String> found = new HashSet<>();
        int count = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path report : stream.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().limit(4097).toList()) {
                if (++count > 4096) {
                    throw new IllegalArgumentException("original CDT report count budget exceeded");
                }
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(report) > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("unsafe CDT test report file");
                }
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var dom = factory.newDocumentBuilder().parse(report.toFile());
                var cases = dom.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element test = (Element) cases.item(i);
                    String owner = test.getAttribute("classname");
                    if (!(TEST.equals(owner) || owner.endsWith("." + TEST))) continue;
                    String name = test.getAttribute("name");
                    if (!REQUIRED.contains(name) || !found.add(name)) {
                        throw new IllegalArgumentException("unknown or duplicate original CDT test: " + name);
                    }
                    for (String status : new String[]{"failure", "error", "skipped"}) {
                        if (test.getElementsByTagName(status).getLength() != 0) {
                            throw new IllegalArgumentException("original CDT test " + name
                                    + " was not accepted: " + status);
                        }
                    }
                }
            }
        }
        if (!found.equals(REQUIRED)) {
            Set<String> missing = new HashSet<>(REQUIRED);
            missing.removeAll(found);
            throw new IllegalArgumentException("original CDT scanner-facts tests missing: " + missing);
        }
        System.out.println("PASS: all four original CDT compiler-facts tests executed without skips");
    }
}
