"""Tests of tck_fixture_patch (TCK-BUG-001). Run: python3 -m unittest discover -s tools -v (from mansart-jpa-tck).

Scratch files live under ../target/tool-tests, never in the system temporary directory.
"""
import hashlib
import json
import os
import pathlib
import shutil
import tempfile
import unittest
import warnings
import zipfile

import tck_fixture_patch as patch

RUNNER = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = RUNNER / "target" / "tool-tests"
OFFICIAL_JAR = RUNNER.parent / ".tck-cache" / "persistence-tck-3.2.1" / "artifacts" / "persistence-tck-spec-tests-3.2.1.jar"

NATIVE = "ee/jakarta/tck/persistence/core/annotations/nativequery/orm.xml"
APITESTS = "ee/jakarta/tck/persistence/core/entitytest/apitests/orm.xml"

HEAD = (b'<?xml version="1.0" encoding="UTF-8"?>\n<!-- license -->\n\n'
        b'<entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm"\n'
        b'    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n'
        b'    xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence/orm\n'
        b'    https://jakarta.ee/xml/ns/persistence/orm/orm_3_2.xsd"\n'
        b'    version="3.2">\n')
ENTITY = (b'    <entity name="PurchaseOrder" class="x.PurchaseOrder" cacheable="true">\n'
          b'        <table name="PURCHASE_ORDER"/>\n'
          b'    </entity>\n')
TAIL = b'</entity-mappings>\n'
NATIVE_XML = HEAD + patch.BLOCK + ENTITY + TAIL
APITESTS_XML = HEAD + patch.BLOCK + TAIL


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


class Scratch(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.dir = pathlib.Path(tempfile.mkdtemp(dir=SCRATCH))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def jar(self, entries, name="spec.jar"):
        """Writes a jar from (name, data, compress_type) entries, with fixed metadata."""
        path = self.dir / name
        with zipfile.ZipFile(path, "w") as out:
            out.comment = b"jar comment"
            for entry, data, method in entries:
                info = zipfile.ZipInfo(entry, (2020, 12, 19, 17, 24, 0))
                info.compress_type = method
                info.external_attr = 0o644 << 16
                out.writestr(info, data)
        return path

    def synthetic(self, native=NATIVE_XML, apitests=APITESTS_XML, extra=()):
        entries = [("META-INF/", b"", zipfile.ZIP_STORED),
                   ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n", zipfile.ZIP_DEFLATED),
                   (NATIVE, native, zipfile.ZIP_DEFLATED),
                   ("ee/Other.class", b"\xca\xfe\xba\xbe" + bytes(range(256)), zipfile.ZIP_DEFLATED),
                   ("ee/jakarta/tck/persistence/core/callback/listener/orm.xml", HEAD + ENTITY + TAIL,
                    zipfile.ZIP_STORED),
                   (APITESTS, apitests, zipfile.ZIP_DEFLATED)]
        entries.extend(extra)
        return self.jar(entries)

    def resources(self, native=NATIVE_XML, apitests=APITESTS_XML):
        return {NATIVE: patch.Resource(sha(native), sha(native.replace(patch.BLOCK, b""))),
                APITESTS: patch.Resource(sha(apitests), sha(apitests.replace(patch.BLOCK, b"")))}

    def derive(self, source, **kwargs):
        kwargs.setdefault("source_sha256", file_sha(source))
        kwargs.setdefault("resources", self.resources())
        return patch.derive_jar(source, self.dir / "derived.jar", **kwargs)


class PatchResourceTest(unittest.TestCase):
    def test_removes_exactly_the_upstream_block_and_keeps_everything_else(self):
        patched = patch.patch_resource(NATIVE, NATIVE_XML)
        self.assertEqual(HEAD + ENTITY + TAIL, patched)
        self.assertIn(b'version="3.2"', patched)
        self.assertIn(b"orm_3_2.xsd", patched)
        self.assertNotIn(b"delimited-identifiers", patched)

    def test_metadata_only_file_keeps_an_empty_valid_document(self):
        self.assertEqual(HEAD + TAIL, patch.patch_resource(APITESTS, APITESTS_XML))

    def test_already_removed_block_is_reported_not_ignored(self):
        with self.assertRaisesRegex(patch.PatchError, "already removed"):
            patch.patch_resource(NATIVE, HEAD + ENTITY + TAIL)

    def test_duplicate_block_is_refused(self):
        with self.assertRaisesRegex(patch.PatchError, "2 occurrences"):
            patch.patch_resource(NATIVE, HEAD + patch.BLOCK + patch.BLOCK + TAIL)

    def test_other_unit_defaults_are_never_removed(self):
        richer = patch.BLOCK.replace(b"            <delimited-identifiers/>\n",
                                     b"            <delimited-identifiers/>\n            <schema>S</schema>\n")
        with self.assertRaisesRegex(patch.PatchError, "block not found"):
            patch.patch_resource(NATIVE, HEAD + richer + TAIL)

    def test_differently_formatted_block_is_unknown_input(self):
        crlf = (HEAD + patch.BLOCK + TAIL).replace(b"\n", b"\r\n")
        with self.assertRaisesRegex(patch.PatchError, "block not found"):
            patch.patch_resource(NATIVE, crlf)

    def test_other_schema_version_is_refused(self):
        with self.assertRaisesRegex(patch.PatchError, "version 3.2"):
            patch.patch_resource(NATIVE, NATIVE_XML.replace(b'version="3.2"', b'version="3.1"'))

    def test_other_namespace_is_refused(self):
        with self.assertRaisesRegex(patch.PatchError, "namespace"):
            patch.patch_resource(NATIVE, NATIVE_XML.replace(b'xmlns="https://jakarta.ee/', b'xmlns="https://example.org/'))

    def test_block_outside_the_root_metadata_is_refused(self):
        nested = HEAD + b"    <entity class=\"x.A\">\n" + patch.BLOCK + b"    </entity>\n" + TAIL
        with self.assertRaisesRegex(patch.PatchError, "root"):
            patch.patch_resource(NATIVE, nested)

    def test_doctype_is_refused(self):
        with self.assertRaisesRegex(patch.PatchError, "DOCTYPE"):
            patch.patch_resource(NATIVE, NATIVE_XML.replace(b"<!-- license -->", b'<!DOCTYPE x SYSTEM "y">'))

    def test_checksums_are_enforced(self):
        with self.assertRaisesRegex(patch.PatchError, "original SHA-256"):
            patch.patch_resource(NATIVE, NATIVE_XML, patch.Resource("0" * 64, "1" * 64))
        with self.assertRaisesRegex(patch.PatchError, "patched SHA-256"):
            patch.patch_resource(NATIVE, NATIVE_XML, patch.Resource(sha(NATIVE_XML), "1" * 64))


class DeriveJarTest(Scratch):
    def test_derived_jar_changes_only_the_two_resources(self):
        source = self.synthetic()
        provenance = self.derive(source)
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(self.dir / "derived.jar") as derived:
            self.assertIsNone(derived.testzip())
            self.assertEqual(original.namelist(), derived.namelist())
            self.assertEqual(original.comment, derived.comment)
            for before, after in zip(original.infolist(), derived.infolist()):
                self.assertEqual(before.date_time, after.date_time, before.filename)
                self.assertEqual(before.compress_type, after.compress_type, before.filename)
                self.assertEqual(before.external_attr, after.external_attr, before.filename)
                if before.filename in (NATIVE, APITESTS):
                    self.assertEqual(original.read(before).replace(patch.BLOCK, b""), derived.read(after))
                else:
                    self.assertEqual(original.read(before), derived.read(after), before.filename)
        self.assertEqual("patched", provenance["mode"])
        self.assertTrue(provenance["modified"])
        self.assertEqual(file_sha(source), provenance["source"]["sha256"])
        self.assertEqual(file_sha(self.dir / "derived.jar"), provenance["jar"]["sha256"])
        self.assertEqual(patch.UPSTREAM_COMMIT, provenance["upstream"]["commit"])
        self.assertEqual(sorted([NATIVE, APITESTS]), sorted(r["name"] for r in provenance["resources"]))
        self.assertEqual(6, provenance["entries"])

    def test_derivation_is_idempotent_and_deterministic(self):
        source = self.synthetic()
        first = self.derive(source)
        first_bytes = (self.dir / "derived.jar").read_bytes()
        second = self.derive(source)
        self.assertEqual(first_bytes, (self.dir / "derived.jar").read_bytes())
        self.assertEqual(first, second)

    def test_source_jar_is_never_modified(self):
        source = self.synthetic()
        before = file_sha(source)
        self.derive(source)
        self.assertEqual(before, file_sha(source))

    def test_unknown_source_checksum_is_refused_before_writing(self):
        source = self.synthetic()
        with self.assertRaisesRegex(patch.PatchError, "SHA-256"):
            self.derive(source, source_sha256="0" * 64)
        self.assertFalse((self.dir / "derived.jar").exists())

    def test_deriving_from_an_already_patched_jar_fails_clearly(self):
        source = self.synthetic()
        self.derive(source)
        again = self.dir / "again.jar"
        shutil.copy(self.dir / "derived.jar", again)
        with self.assertRaisesRegex(patch.PatchError, "already removed"):
            patch.derive_jar(again, self.dir / "other.jar", source_sha256=file_sha(again),
                             resources={NATIVE: patch.Resource(None, None), APITESTS: patch.Resource(None, None)})

    def test_missing_resource_is_refused(self):
        source = self.jar([(NATIVE, NATIVE_XML, zipfile.ZIP_DEFLATED)])
        with self.assertRaisesRegex(patch.PatchError, "missing"):
            self.derive(source)
        self.assertFalse((self.dir / "derived.jar").exists())

    def test_duplicate_entries_are_refused(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            source = self.synthetic(extra=[("ee/Other.class", b"x", zipfile.ZIP_STORED)])
        with self.assertRaisesRegex(patch.PatchError, "duplicate"):
            self.derive(source)

    def test_signed_jar_is_refused(self):
        source = self.synthetic(extra=[("META-INF/SIGNER.SF", b"x", zipfile.ZIP_DEFLATED)])
        with self.assertRaisesRegex(patch.PatchError, "signed"):
            self.derive(source)

    def test_failed_validation_leaves_a_previous_derived_jar_and_no_partial_file(self):
        source = self.synthetic()
        self.derive(source)
        previous = (self.dir / "derived.jar").read_bytes()
        broken = self.synthetic(native=HEAD + ENTITY + TAIL)
        with self.assertRaises(patch.PatchError):
            self.derive(broken, resources=self.resources(native=HEAD + ENTITY + TAIL))
        self.assertEqual(previous, (self.dir / "derived.jar").read_bytes())
        self.assertEqual([], list(self.dir.glob("*.partial")))


class PrepareTest(Scratch):
    def test_patched_mode_is_the_default_and_writes_provenance(self):
        source = self.synthetic()
        jar, provenance = patch.prepare("patched", source, self.dir / "work",
                                        source_sha256=file_sha(source), resources=self.resources())
        self.assertNotEqual(source.resolve(), jar.resolve())
        self.assertEqual(provenance, json.loads((self.dir / "work" / patch.PROVENANCE).read_text()))
        self.assertEqual("patched", patch.DEFAULT_MODE)

    def test_official_mode_uses_the_untouched_source_and_writes_no_jar(self):
        source = self.synthetic()
        jar, provenance = patch.prepare("official", source, self.dir / "work",
                                        source_sha256=file_sha(source), resources=self.resources())
        self.assertEqual(source.resolve(), jar.resolve())
        self.assertFalse(provenance["modified"])
        self.assertEqual("official", provenance["mode"])
        self.assertEqual([patch.PROVENANCE], [p.name for p in (self.dir / "work").iterdir()])

    def test_official_mode_still_verifies_the_source(self):
        source = self.synthetic()
        with self.assertRaisesRegex(patch.PatchError, "SHA-256"):
            patch.prepare("official", source, self.dir / "work", source_sha256="0" * 64)

    def test_switching_to_official_mode_removes_a_stale_derived_jar(self):
        source = self.synthetic()
        args = dict(source_sha256=file_sha(source), resources=self.resources())
        patch.prepare("patched", source, self.dir / "work", **args)
        patch.prepare("official", source, self.dir / "work", **args)
        self.assertFalse((self.dir / "work" / patch.DERIVED_NAME).exists())

    def test_unknown_mode_is_refused(self):
        with self.assertRaisesRegex(patch.PatchError, "mode"):
            patch.prepare("raw", self.synthetic(), self.dir / "work")


def report(directory, execution, classname, cases, classpath):
    folder = directory / execution
    folder.mkdir(parents=True, exist_ok=True)
    body = "".join(
        f'<testcase classname="{classname}" name="{name}">'
        + {"pass": "", "error": "<error message=\"boom\"/>", "skipped": "<skipped/>"}[status] + "</testcase>"
        for name, status in cases)
    (folder / f"TEST-{classname}.xml").write_text(
        f'<testsuite><properties><property name="java.class.path" value="{classpath}"/></properties>'
        f"{body}</testsuite>")


class ReportsTest(Scratch):
    def test_label_states_patch_provenance_and_verifies_the_class_path(self):
        source = self.synthetic()
        jar, provenance = patch.prepare("patched", source, self.dir / "work",
                                        source_sha256=file_sha(source), resources=self.resources())
        report(self.dir / "reports", "execution-1", "ee.jakarta.tck.A", [("t", "pass")],
               f"/x/common.jar:{jar}:/x/other.jar")
        lines, ok = patch.label(provenance, self.dir / "reports")
        text = "\n".join(lines)
        self.assertTrue(ok)
        self.assertIn("PATCHED", text)
        self.assertIn(patch.UPSTREAM_COMMIT, text)
        self.assertIn("not an official", text)
        self.assertIn(provenance["source"]["sha256"], text)
        self.assertIn(provenance["jar"]["sha256"], text)

    def test_label_fails_when_another_spec_tests_jar_was_used(self):
        source = self.synthetic()
        jar, provenance = patch.prepare("patched", source, self.dir / "work",
                                        source_sha256=file_sha(source), resources=self.resources())
        report(self.dir / "reports", "execution-1", "ee.jakarta.tck.A", [("t", "pass")],
               f"{jar}:/m2/persistence-tck-spec-tests-3.2.1.jar")
        lines, ok = patch.label(provenance, self.dir / "reports")
        self.assertFalse(ok)
        self.assertIn("ERROR", "\n".join(lines))

    def test_official_label_says_unmodified(self):
        source = self.synthetic()
        jar, provenance = patch.prepare("official", source, self.dir / "work",
                                        source_sha256=file_sha(source), resources=self.resources())
        report(self.dir / "reports", "execution-1", "ee.jakarta.tck.A", [("t", "pass")], str(jar))
        lines, ok = patch.label(provenance, self.dir / "reports")
        self.assertTrue(ok)
        self.assertIn("UNMODIFIED", "\n".join(lines))

    def test_compare_reports_identities_and_transitions(self):
        report(self.dir / "base", "execution-1", "ee.jakarta.tck.A",
               [("kept", "pass"), ("fixed", "error"), ("gone", "pass"), ("skip", "skipped")], "")
        report(self.dir / "new", "execution-1", "ee.jakarta.tck.A",
               [("kept", "pass"), ("fixed", "pass"), ("added", "pass"), ("skip", "skipped")], "")
        result = patch.compare(self.dir / "base", self.dir / "new")
        self.assertEqual([("execution-1", "ee.jakarta.tck.A", "added")], result["added"])
        self.assertEqual([("execution-1", "ee.jakarta.tck.A", "gone")], result["missing"])
        self.assertEqual([("execution-1", "ee.jakarta.tck.A", "fixed")], result["newly_passing"])
        self.assertEqual([], result["regressions"])
        self.assertEqual({"pass": 3, "fail": 0, "skipped": 1}, result["new_counts"])


@unittest.skipUnless(OFFICIAL_JAR.is_file(), "official 3.2.1 bundle not unpacked (run ../install-tck.sh)")
class OfficialArtifactTest(Scratch):
    def test_pinned_official_jar_derives_the_upstream_fixture_change(self):
        before = file_sha(OFFICIAL_JAR)
        self.assertEqual(patch.OFFICIAL_SPEC_TESTS_SHA256, before)
        provenance = patch.derive_jar(OFFICIAL_JAR, self.dir / "derived.jar")
        self.assertEqual(before, file_sha(OFFICIAL_JAR))
        self.assertEqual(1126, provenance["entries"])
        with zipfile.ZipFile(OFFICIAL_JAR) as original, zipfile.ZipFile(self.dir / "derived.jar") as derived:
            changed = [i.filename for i in original.infolist()
                       if original.read(i) != derived.read(i.filename)]
            self.assertEqual([NATIVE, APITESTS], changed)
            for name in changed:
                text = derived.read(name)
                self.assertIn(b'version="3.2"', text)
                self.assertNotIn(b"persistence-unit-metadata", text)
            self.assertIn(b'<table name="PURCHASE_ORDER"/>', derived.read(NATIVE))


if __name__ == "__main__":
    unittest.main()
