#!/usr/bin/env python3
"""Tag releases without writing credentials or release notes to the Actions log."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

# SemVer core plus optional prerelease; build metadata is intentionally unsupported.
VERSION = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?\Z")


class SafeError(Exception):
    """Only fixed, deliberately public diagnostics may be printed."""


def valid_tag(tag):
    match = VERSION.fullmatch(tag)
    if not match:
        return False
    return not match[4] or all(not (part.isdigit() and len(part) > 1 and part[0] == "0") for part in match[4].split("."))


def git(*args):
    return subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def prepare():
    tag = os.environ["GITHUB_REF_NAME"]
    if os.environ.get("GITHUB_REF_TYPE") != "tag" or not valid_tag(tag):
        raise SafeError("Invalid release tag; expected vMAJOR.MINOR.PATCH with an optional SemVer prerelease suffix.")
    commit = git("rev-parse", "--verify", "HEAD^{commit}").strip()
    if git("rev-parse", "--verify", "refs/tags/" + tag + "^{commit}").strip() != commit:
        raise SafeError("The checked-out commit does not match the release tag.")
    notes_path = "docs/releases/" + tag + ".md"
    try:
        notes = git("show", commit.decode("ascii") + ":" + notes_path).decode("utf-8").strip()
    except (subprocess.CalledProcessError, UnicodeDecodeError):
        raise SafeError("Add a UTF-8 release note under docs/releases/ for this tag before publishing.") from None
    if not notes:
        raise SafeError("The release note must not be empty.")
    directory = Path(tempfile.mkdtemp(prefix="cliplink-release-", dir=os.environ["RUNNER_TEMP"]))
    try:
        (directory / "notes.md").write_text(notes + "\n", encoding="utf-8")
        (directory / "tag.txt").write_text(tag, encoding="utf-8")
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
            env_file.write("CLIPLINK_RELEASE_DIR=" + str(directory) + "\n")
    except BaseException:
        shutil.rmtree(directory, ignore_errors=True)
        raise


def build():
    directory = Path(os.environ["CLIPLINK_RELEASE_DIR"])
    tag = (directory / "tag.txt").read_text(encoding="utf-8")
    names = ("CLIPLINK_KEYSTORE_BASE64", "CLIPLINK_STORE_PASSWORD", "CLIPLINK_KEY_ALIAS", "CLIPLINK_KEY_PASSWORD")
    if any(not os.environ.get(name, "").strip() for name in names):
        raise SafeError("Release signing configuration is incomplete.")
    try:
        key_data = base64.b64decode(os.environ["CLIPLINK_KEYSTORE_BASE64"], validate=True)
        if not key_data:
            raise ValueError()
    except (ValueError, TypeError):
        raise SafeError("Release keystore is not valid Base64.") from None
    keystore = directory / "release.keystore"
    keystore.write_bytes(key_data)
    keystore.chmod(0o600)
    env = os.environ.copy()
    env.pop("CLIPLINK_KEYSTORE_BASE64", None)
    env["CLIPLINK_KEYSTORE_FILE"] = str(keystore)
    env["CLIPLINK_VERSION_NAME"] = tag[1:]
    with (directory / "build.log").open("wb") as log:
        result = subprocess.run(["bash", "gradlew", "--no-daemon", ":app:verifyReleaseConfiguration", ":core:test", ":app:testReleaseUnitTest", ":app:lintRelease", ":app:assembleRelease"], env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SafeError("Gradle verification or release build failed; check the project configuration and signing credentials.")
    outputs = list(Path("app/build/outputs/apk/release").glob("*.apk"))
    if len(outputs) != 1:
        raise SafeError("Expected exactly one release APK.")
    apk = directory / ("XposedClipLinkRouter-" + tag[1:] + ".apk")
    shutil.copyfile(outputs[0], apk)
    sdk = Path(os.environ["ANDROID_HOME"])
    result = subprocess.run([str(sdk / "build-tools/35.0.0/apksigner"), "verify", str(apk)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env={k: v for k, v in env.items() if k not in names})
    if result.returncode:
        raise SafeError("Release APK signature verification failed.")
    print("Release tests, build, and APK signature verification passed.")


def request(method, url, payload=None, content_type="application/json"):
    token = os.environ["GH_TOKEN"]
    headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    data = None
    if payload is not None:
        headers["Content-Type"] = content_type
        data = json.dumps(payload).encode("utf-8") if content_type == "application/json" else payload
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=120) as response:
        body = response.read()
        return json.loads(body) if body else None


def upload_apk(base, release, apk):
    if release.get("immutable"):
        raise SafeError("This release is immutable; publish changes using a new version tag.")
    # List all pages, so a rerun cannot miss a same-named asset.
    page = 1
    matching_assets = []
    while True:
        assets = request("GET", base + "/releases/" + str(release["id"]) + "/assets?per_page=100&page=" + str(page))
        matching_assets.extend(asset for asset in assets if asset["name"] == apk.name)
        if len(assets) < 100:
            break
        page += 1

    # GitHub requires unique asset names. Keep the current download available
    # until the replacement has finished uploading and passed verification.
    suffix = uuid.uuid4().hex
    upload_name = "pending-" + suffix + "-" + apk.name if matching_assets else apk.name
    upload_url = release["upload_url"].split("{", 1)[0] + "?" + urllib.parse.urlencode({"name": upload_name})
    data = apk.read_bytes()
    uploaded = request("POST", upload_url, data, "application/vnd.android.package-archive")
    digest = "sha256:" + hashlib.sha256(data).hexdigest()
    if (uploaded.get("state") != "uploaded" or uploaded.get("size") != len(data)
            or (uploaded.get("digest") is not None and uploaded["digest"] != digest)):
        raise SafeError("Uploaded APK verification failed; no existing APK was replaced.")

    if not matching_assets:
        return
    renamed = []
    try:
        for asset in matching_assets:
            backup_name = "backup-" + suffix + "-" + str(asset["id"]) + "-" + apk.name
            # Record before sending: a request can succeed on GitHub even if
            # the connection drops before the response reaches the runner.
            renamed.append(asset)
            request("PATCH", base + "/releases/assets/" + str(asset["id"]), {"name": backup_name})
        request("PATCH", base + "/releases/assets/" + str(uploaded["id"]), {"name": apk.name})
    except Exception:
        for asset in reversed(renamed):
            try:
                request("PATCH", base + "/releases/assets/" + str(asset["id"]), {"name": apk.name})
            except Exception as exc:
                # During an API outage, retain both APKs for recovery rather
                # than deleting an asset whose rename may have succeeded.
                if isinstance(exc, urllib.error.HTTPError):
                    exc.close()
        raise
    for asset in matching_assets:
        request("DELETE", base + "/releases/assets/" + str(asset["id"]))


def publish():
    directory = Path(os.environ["CLIPLINK_RELEASE_DIR"])
    tag = (directory / "tag.txt").read_text(encoding="utf-8")
    if not valid_tag(tag) or tag != os.environ["GITHUB_REF_NAME"]:
        raise SafeError("Release tag changed during the workflow.")
    version = tag[1:]
    apk = directory / ("XposedClipLinkRouter-" + version + ".apk")
    if not apk.is_file():
        raise SafeError("Verified release APK is missing.")
    notes = (directory / "notes.md").read_text(encoding="utf-8")
    base = os.environ["GITHUB_API_URL"] + "/repos/" + os.environ["GITHUB_REPOSITORY"]
    ref = request("GET", base + "/git/ref/tags/" + urllib.parse.quote(tag, safe=""))["object"]
    for _ in range(16):
        if ref["type"] != "tag":
            break
        ref = request("GET", base + "/git/tags/" + ref["sha"])["object"]
    if ref["type"] != "commit" or ref["sha"] != git("rev-parse", "HEAD").decode("ascii").strip():
        raise SafeError("Remote tag no longer points to the checked-out commit.")
    release = None
    page = 1
    while True:
        releases = request("GET", base + "/releases?per_page=100&page=" + str(page))
        release = next((item for item in releases if item["tag_name"] == tag), None)
        if release is not None or len(releases) < 100:
            break
        page += 1
    if release is None:
        release = request("POST", base + "/releases", {"tag_name": tag, "name": tag, "draft": True, "prerelease": "-" in tag})
    upload_apk(base, release, apk)
    request("PATCH", base + "/releases/" + str(release["id"]), {"name": tag, "body": notes, "prerelease": "-" in tag, "draft": False})
    print("Release and signed APK published.")


def main():
    command = sys.argv[1] if len(sys.argv) == 2 else ""
    try:
        {"prepare": prepare, "build": build, "publish": publish}[command]()
    except SafeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except urllib.error.HTTPError as exc:
        print("GitHub API request failed (HTTP " + str(exc.code) + "); check repository permissions, release settings, and service availability.", file=sys.stderr)
        exc.close()
        return 1
    except urllib.error.URLError:
        print("GitHub API connection failed; check service availability and retry.", file=sys.stderr)
        return 1
    except Exception:
        print("Release " + (command if command in ("prepare", "build", "publish") else "operation") + " failed; inspect the configuration and retry.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
