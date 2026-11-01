import { cp, mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";

const [uiRoot, outputRoot] = process.argv.slice(2);
if (!uiRoot || !outputRoot) {
  throw new Error(
    "Usage: node collect-ui-license-files.mjs <ui-root> <output-root>",
  );
}

const sbomPath = path.join(outputRoot, "ui-sbom.cdx.json");
const windows = process.platform === "win32";
const npm = spawnSync(
  windows ? process.env.ComSpec || "cmd.exe" : "npm",
  windows
    ? ["/d", "/s", "/c", "npm.cmd sbom --omit=dev --sbom-format=cyclonedx"]
    : ["sbom", "--omit=dev", "--sbom-format=cyclonedx"],
  { cwd: uiRoot, encoding: "utf8", maxBuffer: 20 * 1024 * 1024 },
);
if (npm.error) throw npm.error;
if (npm.status !== 0) {
  throw new Error(`npm SBOM generation failed: ${npm.stderr || npm.stdout}`);
}

await mkdir(outputRoot, { recursive: true });
await writeFile(sbomPath, npm.stdout, "utf8");
const sbom = JSON.parse(await readFile(sbomPath, "utf8"));
if (sbom.bomFormat !== "CycloneDX" || !Array.isArray(sbom.components)) {
  throw new Error(`Not a CycloneDX SBOM with components: ${sbomPath}`);
}

const destinationRoot = path.join(outputRoot, "ui");
const status = [];
for (const component of sbom.components) {
  if (!component.name || !component.version) {
    throw new Error("SBOM contains a component without a name or version.");
  }

  const packagePath = path.join(
    uiRoot,
    "node_modules",
    ...component.name.split("/"),
  );
  const componentFolder = `${component.name.replaceAll("/", "+")}@${component.version}`;
  const destination = path.join(destinationRoot, componentFolder);
  const licenseIds = (component.licenses || [])
    .map((entry) => entry.license?.id || entry.license?.name || entry.expression)
    .filter(Boolean);
  let packageEntries;
  try {
    packageEntries = await readdir(packagePath, { withFileTypes: true });
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    status.push(`${component.name}@${component.version}: package not found at ${packagePath}; inspect SBOM metadata.`);
    continue;
  }
  const licenseFiles = packageEntries
    .filter(
      (entry) =>
        entry.isFile() &&
        /^(LICENSE|NOTICE|COPYING)(\.(txt|md|html|rtf))?$/i.test(entry.name),
    )
    .map((entry) => entry.name);

  if (licenseFiles.length === 0) {
    status.push(
      `${component.name}@${component.version}: no top-level license/notice file; ` +
        `SBOM license=${licenseIds.join(" OR ") || "UNDECLARED"}.`,
    );
    continue;
  }

  await mkdir(destination, { recursive: true });
  for (const filename of licenseFiles) {
    await cp(path.join(packagePath, filename), path.join(destination, filename));
  }
  status.push(
    `${component.name}@${component.version}: license=${licenseIds.join(" OR ") || "UNDECLARED"}; ` +
      `copied ${licenseFiles.join(", ")}.`,
  );
}

if (status.length === 0) {
  throw new Error("The production UI SBOM contains no components.");
}

await mkdir(outputRoot, { recursive: true });
await writeFile(
  path.join(outputRoot, "ui-license-file-status.txt"),
  `${status.join("\n")}\n`,
  "utf8",
);
