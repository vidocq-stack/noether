"""TCK-BUG-001: local backport of the upstream Jakarta Persistence TCK fixture fix to the 3.2.1 spec-tests jar.

Upstream issue https://github.com/jakartaee/persistence/issues/1175, fixed on the 4.0 line by commit
1fea05e58151f10954206a15d70b18008043d3d9: the unit-wide ``<delimited-identifiers/>`` default of two mapping files
contradicts the unquoted official DDL and native SQL of the same tests. The commit removes the
``persistence-unit-metadata`` block from both files and nothing else.

This tool never modifies the official artifacts. It derives a copy of the checksum-pinned official
``persistence-tck-spec-tests-3.2.1.jar`` in which exactly those two resources lose exactly that block (byte-exact
five lines, as in the upstream diff), keeping the 3.2 namespace, schema version, every other resource, the entry
order and the entry metadata. Any unexpected input (other checksum, block absent, duplicated, reformatted, richer
unit defaults, other namespace/version, signed jar, duplicate or missing entries) is an error, never a silent
no-op. Results obtained with the derived jar are locally patched results, not official certification results.

Commands (run by ../run-official-tck-persistence-3.2.sh):
  prepare --mode patched|official --source JAR --work-dir DIR   prints the spec-tests jar the run must use
  label --provenance FILE --reports DIR                         fixture section of the report; exit 3 on mismatch
  compare BASE_REPORTS NEW_REPORTS                               test identities and status transitions
"""
import argparse
import collections
import glob
import hashlib
import json
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

PATCH_ID = "TCK-BUG-001"
UPSTREAM_ISSUE = "https://github.com/jakartaee/persistence/issues/1175"
UPSTREAM_COMMIT = "1fea05e58151f10954206a15d70b18008043d3d9"
UPSTREAM_COMMIT_URL = "https://github.com/jakartaee/persistence/commit/" + UPSTREAM_COMMIT
REASON = ("unit-wide <delimited-identifiers/> in two 3.2.1 fixtures contradicts the unquoted official DDL and "
          "native SQL of the same tests; upstream removed the block (4.0 line), no 3.2 backport published yet")

# persistence-tck-spec-tests-3.2.1.jar of jakarta-persistence-tck-3.2.1.zip (bundle SHA-256 verified by install-tck.sh).
OFFICIAL_SPEC_TESTS_SHA256 = "a6ad07d4442aace8630348f7aae990d31d79a31692e14ed463f482c58b364024"
DERIVED_NAME = "persistence-tck-spec-tests-3.2.1-tck-bug-001.jar"
PROVENANCE = "fixture-provenance.json"
MODES = ("patched", "official")
DEFAULT_MODE = "patched"

ORM_NS = "https://jakarta.ee/xml/ns/persistence/orm"
METADATA = f"{{{ORM_NS}}}persistence-unit-metadata"
DEFAULTS = f"{{{ORM_NS}}}persistence-unit-defaults"
DELIMITED = f"{{{ORM_NS}}}delimited-identifiers"

# The exact lines removed by the upstream commit.
BLOCK = (b"    <persistence-unit-metadata>\n"
         b"        <persistence-unit-defaults>\n"
         b"            <delimited-identifiers/>\n"
         b"        </persistence-unit-defaults>\n"
         b"    </persistence-unit-metadata>\n")

Resource = collections.namedtuple("Resource", "original_sha256 patched_sha256")

RESOURCES = {
    "ee/jakarta/tck/persistence/core/annotations/nativequery/orm.xml": Resource(
        "fdf87f63ebd0724b81a691a823affbe1978ec54e4cb2cbc5cb3337dd165a72ed",
        "0afff5fbe2f646e33868106c7f9b4ef6e2c22bcc0980a324861905eab6995126"),
    "ee/jakarta/tck/persistence/core/entitytest/apitests/orm.xml": Resource(
        "648107772ca15e1b6e937cb39b31e6eb1ef8c4866ff3a1bca94acd34eaa5f39d",
        "b01999cff62cc08375292043b67cab20a8a191038720d36c2c576da0985dc767"),
}

SIGNATURE = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$", re.IGNORECASE)


class PatchError(Exception):
    pass


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def _file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _parse(name, data):
    try:
        return ET.fromstring(data)
    except ET.ParseError as e:
        raise PatchError(f"{name}: not well-formed XML ({e})") from e


def _check_root(name, root):
    if root.tag != f"{{{ORM_NS}}}entity-mappings":
        raise PatchError(f"{name}: root {root.tag} is not entity-mappings of namespace {ORM_NS}")
    if root.get("version") != "3.2":
        raise PatchError(f"{name}: expected orm schema version 3.2, found {root.get('version')!r}")


def patch_resource(name, data, expected=Resource(None, None)):
    """Returns data without the upstream block; raises PatchError for any input other than the expected one."""
    if expected.original_sha256 and _sha256(data) != expected.original_sha256:
        raise PatchError(f"{name}: original SHA-256 {_sha256(data)} differs from the pinned "
                         f"{expected.original_sha256}")
    if b"<!DOCTYPE" in data:
        raise PatchError(f"{name}: DOCTYPE declarations are refused")
    count = data.count(BLOCK)
    if count == 0:
        if b"persistence-unit-metadata" not in data:
            raise PatchError(f"{name}: the delimited-identifiers block is absent: already removed "
                             f"(already patched input?)")
        raise PatchError(f"{name}: exact upstream delimited-identifiers block not found: unexpected content")
    if count > 1:
        raise PatchError(f"{name}: {count} occurrences of the delimited-identifiers block, expected 1")

    root = _parse(name, data)
    _check_root(name, root)
    metadata = list(root.iter(METADATA))
    direct = [child for child in root if child.tag == METADATA]
    if len(metadata) != 1 or len(direct) != 1:
        raise PatchError(f"{name}: persistence-unit-metadata must be one direct child of the root entity-mappings")
    defaults = list(direct[0])
    if (len(defaults) != 1 or defaults[0].tag != DEFAULTS or len(defaults[0]) != 1
            or defaults[0][0].tag != DELIMITED or len(defaults[0][0]) or (defaults[0][0].text or "").strip()):
        raise PatchError(f"{name}: persistence-unit-metadata holds more than <delimited-identifiers/>")

    patched = data.replace(BLOCK, b"")
    after = _parse(name, patched)
    _check_root(name, after)
    if after.attrib != root.attrib or list(after.iter(METADATA)):
        raise PatchError(f"{name}: unexpected structural change")
    kept = [ET.tostring(child) for child in root if child.tag != METADATA]
    if kept != [ET.tostring(child) for child in after]:
        raise PatchError(f"{name}: mappings other than the unit metadata changed")
    if expected.patched_sha256 and _sha256(patched) != expected.patched_sha256:
        raise PatchError(f"{name}: patched SHA-256 {_sha256(patched)} differs from the pinned "
                         f"{expected.patched_sha256}")
    return patched


def _copy_info(info):
    copy = zipfile.ZipInfo(info.filename, info.date_time)
    copy.compress_type = info.compress_type
    copy.comment = info.comment
    copy.extra = info.extra
    copy.create_system = info.create_system
    copy.external_attr = info.external_attr
    copy.internal_attr = info.internal_attr
    return copy


def derive_jar(source, output, *, source_sha256=OFFICIAL_SPEC_TESTS_SHA256, resources=RESOURCES):
    """Writes output from source, patching only `resources`; returns the provenance (JSON-compatible)."""
    source, output = pathlib.Path(source), pathlib.Path(output)
    actual = _file_sha256(source)
    if source_sha256 and actual != source_sha256:
        raise PatchError(f"{source}: SHA-256 {actual} is not the pinned official {source_sha256}")
    with zipfile.ZipFile(source) as jar:
        infos = jar.infolist()
        names = [info.filename for info in infos]
        duplicates = sorted(n for n, c in collections.Counter(names).items() if c > 1)
        if duplicates:
            raise PatchError(f"{source}: duplicate entries {duplicates}")
        signatures = [n for n in names if SIGNATURE.match(n)]
        if signatures:
            raise PatchError(f"{source}: signed jar ({signatures}); a patched copy would break the signature")
        missing = sorted(set(resources) - set(names))
        if missing:
            raise PatchError(f"{source}: missing resources {missing}")
        contents = [jar.read(info) for info in infos]
        comment = jar.comment
    patched = {name: patch_resource(name, contents[names.index(name)], expected)
               for name, expected in resources.items()}

    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + ".partial")
    try:
        with zipfile.ZipFile(partial, "w") as out:
            out.comment = comment
            for info, data in zip(infos, contents):
                out.writestr(_copy_info(info), patched.get(info.filename, data), compress_type=info.compress_type)
        with zipfile.ZipFile(partial) as check:
            if check.testzip() is not None or check.namelist() != names or check.comment != comment:
                raise PatchError(f"{partial}: derived jar does not mirror {source}")
            for info, data in zip(infos, contents):
                written = check.getinfo(info.filename)
                if (written.date_time, written.compress_type, written.external_attr) != \
                        (info.date_time, info.compress_type, info.external_attr):
                    raise PatchError(f"{partial}: metadata of {info.filename} changed")
                if check.read(written) != patched.get(info.filename, data):
                    raise PatchError(f"{partial}: content of {info.filename} changed")
        os.replace(partial, output)
    finally:
        if partial.exists():
            partial.unlink()

    return {
        "patch": PATCH_ID,
        "mode": "patched",
        "modified": True,
        "reason": REASON,
        "upstream": {"issue": UPSTREAM_ISSUE, "commit": UPSTREAM_COMMIT, "commit_url": UPSTREAM_COMMIT_URL},
        "source": {"path": str(source.resolve()), "sha256": actual},
        "jar": {"path": str(output.resolve()), "sha256": _file_sha256(output)},
        "entries": len(names),
        "resources": [{"name": name, "original_sha256": _sha256(contents[names.index(name)]),
                       "patched_sha256": _sha256(patched[name]), "removed": BLOCK.decode("ascii")}
                      for name in sorted(resources)],
    }


def prepare(mode, source, work_dir, *, source_sha256=OFFICIAL_SPEC_TESTS_SHA256, resources=RESOURCES):
    """Returns (jar to run, provenance) and writes the provenance in work_dir."""
    if mode not in MODES:
        raise PatchError(f"unknown fixture mode {mode!r}; expected one of {', '.join(MODES)}")
    source, work_dir = pathlib.Path(source), pathlib.Path(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    derived = work_dir / DERIVED_NAME
    if mode == "patched":
        provenance = derive_jar(source, derived, source_sha256=source_sha256, resources=resources)
        jar = derived
    else:
        actual = _file_sha256(source)
        if source_sha256 and actual != source_sha256:
            raise PatchError(f"{source}: SHA-256 {actual} is not the pinned official {source_sha256}")
        if derived.exists():
            derived.unlink()
        jar = source
        provenance = {"patch": None, "mode": "official", "modified": False,
                      "source": {"path": str(source.resolve()), "sha256": actual},
                      "jar": {"path": str(source.resolve()), "sha256": actual}}
    (work_dir / PROVENANCE).write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    return jar, provenance


def _class_paths(reports):
    for report in sorted(glob.glob(os.path.join(reports, "**", "TEST-*.xml"), recursive=True)):
        for prop in ET.parse(report).getroot().iter("property"):
            if prop.get("name") == "java.class.path":
                yield report, (prop.get("value") or "").split(os.pathsep)


def label(provenance, reports):
    """Fixture section of the report, and whether every report ran on exactly the prepared spec-tests jar."""
    expected = os.path.realpath(provenance["jar"]["path"])
    lines = []
    if provenance["modified"]:
        lines.append(f"Fixtures   : PATCHED locally ({provenance['patch']}) - not an official TCK result, "
                     f"not a certification claim")
        lines.append(f"  upstream : {provenance['upstream']['issue']} commit {provenance['upstream']['commit']}")
        lines.append(f"  reason   : {provenance['reason']}")
        for resource in provenance["resources"]:
            lines.append(f"  resource : {resource['name']} {resource['original_sha256']} -> "
                         f"{resource['patched_sha256']}")
        lines.append(f"  derived  : {provenance['jar']['path']} sha256={provenance['jar']['sha256']}")
    else:
        lines.append("Fixtures   : UNMODIFIED official spec-tests jar")
    lines.append(f"  official : {provenance['source']['path']} sha256={provenance['source']['sha256']}")

    ok, checked = True, 0
    for report, entries in _class_paths(reports):
        checked += 1
        spec = [os.path.realpath(e) for e in entries
                if e and ("persistence-tck-spec-tests" in os.path.basename(e) or os.path.realpath(e) == expected)]
        if spec != [expected]:
            ok = False
            lines.append(f"ERROR      : {os.path.basename(report)} ran with spec-tests {spec}, expected [{expected}]")
    if checked == 0:
        ok = False
        lines.append("ERROR      : no report carries java.class.path; the fixture jar used is unproven")
    elif ok:
        lines.append(f"  verified : {checked} report(s) ran with exactly this spec-tests jar on the class path")
    return lines, ok


def _statuses(reports):
    result = {}
    for report in sorted(glob.glob(os.path.join(reports, "**", "TEST-*.xml"), recursive=True)):
        execution = os.path.basename(os.path.dirname(report))
        for case in ET.parse(report).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                status = "fail"
            elif case.find("skipped") is not None:
                status = "skipped"
            else:
                status = "pass"
            result[(execution, case.get("classname") or "", case.get("name"))] = status
    return result


def compare(base, new):
    before, after = _statuses(base), _statuses(new)
    common = sorted(set(before) & set(after))
    count = lambda statuses: {s: sum(1 for v in statuses.values() if v == s) for s in ("pass", "fail", "skipped")}
    return {
        "base_counts": count(before),
        "new_counts": count(after),
        "added": sorted(set(after) - set(before)),
        "missing": sorted(set(before) - set(after)),
        "newly_passing": [k for k in common if before[k] != "pass" and after[k] == "pass"],
        "regressions": [k for k in common if before[k] == "pass" and after[k] != "pass"],
        "other_changes": [k for k in common if before[k] != after[k]
                          and "pass" not in (before[k], after[k])],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("prepare")
    p.add_argument("--mode", default=DEFAULT_MODE)
    p.add_argument("--source", required=True)
    p.add_argument("--work-dir", required=True)
    l = commands.add_parser("label")
    l.add_argument("--provenance", required=True)
    l.add_argument("--reports", required=True)
    c = commands.add_parser("compare")
    c.add_argument("base")
    c.add_argument("new")
    c.add_argument("--json")
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            jar, _ = prepare(args.mode, args.source, args.work_dir)
            print(jar.resolve())
        elif args.command == "label":
            lines, ok = label(json.loads(pathlib.Path(args.provenance).read_text()), args.reports)
            print("\n".join(lines))
            return 0 if ok else 3
        else:
            result = compare(args.base, args.new)
            if args.json:
                pathlib.Path(args.json).write_text(json.dumps(result, indent=2) + "\n")
            print(f"base: {result['base_counts']}  new: {result['new_counts']}")
            for key in ("added", "missing", "newly_passing", "regressions", "other_changes"):
                print(f"{key}: {len(result[key])}")
                for execution, classname, name in result[key]:
                    print(f"  {execution} {classname}#{name}")
    except PatchError as e:
        print(f"{PATCH_ID}: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
