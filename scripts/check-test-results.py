"""Fail CI when required database integration tests were silently skipped."""
import pathlib
import sys
import xml.etree.ElementTree as ET

directory = pathlib.Path(sys.argv[1])
required = ["com.devvault.workspace.WorkspaceControllerIT", "com.devvault.devvault.DevvaultApplicationTests"]
for name in required:
    path = directory / f"TEST-{name}.xml"
    root = ET.parse(path).getroot()
    if int(root.attrib["tests"]) == 0 or any(int(root.attrib.get(key, "0")) for key in ("skipped", "failures", "errors")):
        raise SystemExit(f"Required integration suite did not pass completely: {name}")
print("Database integration suites passed without skipped tests.")
