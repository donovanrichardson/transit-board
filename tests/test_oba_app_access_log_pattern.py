"""
Static tests for oba-app/Dockerfile access-log valve pattern edit.

Reads oba-app/Dockerfile as text (no Docker invocation).
Asserts that the Dockerfile contains a RUN step that:
  1. References server.xml and the replacement token sequence %m %U %H.
  2. Contains a build-failing guard (e.g. grep -q ... || exit 1).
  3. Does not leave %r as the replacement value.
"""
import os
import re
import pytest

DOCKERFILE_PATH = os.path.join(
    os.path.dirname(__file__), "..", "oba-app", "Dockerfile"
)


def _parse_run_instructions(dockerfile_text):
    """Return a list of RUN instruction bodies, joining line continuations."""
    instructions = []
    current = None
    for line in dockerfile_text.splitlines():
        stripped = line.rstrip()
        if current is None:
            m = re.match(r"^RUN\s+(.*)", stripped)
            if m:
                body = m.group(1)
                if body.endswith("\\"):
                    current = body[:-1]
                else:
                    instructions.append(body)
        else:
            # continuation line
            if stripped.endswith("\\"):
                current += "\n" + stripped[:-1]
            else:
                current += "\n" + stripped
                instructions.append(current)
                current = None
    if current is not None:
        instructions.append(current)
    return instructions


def _load_dockerfile():
    with open(DOCKERFILE_PATH) as f:
        return f.read()


def test_run_references_server_xml_and_new_tokens():
    """A single RUN instruction must mention server.xml and %m %U %H."""
    text = _load_dockerfile()
    runs = _parse_run_instructions(text)
    matching = [r for r in runs if "server.xml" in r and "%m %U %H" in r]
    assert matching, (
        "No RUN instruction found that references both server.xml and '%m %U %H'. "
        "The access-log pattern replacement step is missing from oba-app/Dockerfile."
    )


def test_run_contains_build_failing_guard():
    """The server.xml RUN step must contain a guard that exits non-zero if the
    expected old pattern is absent (e.g. grep -q ... || exit 1)."""
    text = _load_dockerfile()
    runs = _parse_run_instructions(text)
    matching = [r for r in runs if "server.xml" in r and "%m %U %H" in r]
    assert matching, "No server.xml RUN step found (prerequisite for guard check)."
    run = matching[0]
    has_guard = ("|| exit 1" in run) or ("||exit 1" in run)
    assert has_guard, (
        "The server.xml RUN step does not contain a build-failing guard "
        "(expected '|| exit 1' to be present)."
    )


def test_run_does_not_leave_percent_r_as_replacement():
    """The replacement value in the server.xml RUN step must not be %r."""
    text = _load_dockerfile()
    runs = _parse_run_instructions(text)
    matching = [r for r in runs if "server.xml" in r and "%m %U %H" in r]
    assert matching, "No server.xml RUN step found (prerequisite for %r check)."
    run = matching[0]
    # The sed replacement string must not write %r back
    # Look for the sed command's replacement side: s/old/new/
    # A naive check: ensure %r does not appear after a sed separator as the replacement
    # We look for sed expressions where %r appears as the replacement value
    # Pattern: s<sep><old><sep><new><sep> — new must not be %r wrapped in &quot;
    assert '&quot;%r&quot;' not in run or _old_pattern_is_only_in_search_side(run), (
        "The RUN step leaves &quot;%r&quot; in the replacement value."
    )


def _old_pattern_is_only_in_search_side(run):
    """Return True if &quot;%r&quot; appears only in the search side of a sed
    expression, not in the replacement side.  A best-effort heuristic."""
    # Find sed s/.../.../ expressions
    # We look for the structure: s|old|new| or s/old/new/
    for sep in ["|", "/", "#", "@"]:
        pattern = rf"s{re.escape(sep)}(.*?){re.escape(sep)}(.*?){re.escape(sep)}"
        for m in re.finditer(pattern, run):
            replacement = m.group(2)
            if "&quot;%r&quot;" in replacement:
                return False
    return True
