"""Regression tests for tag preparation and recoverable release publishing."""
import contextlib
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

import release


class ReleaseAPI:
    """Model GitHub's asset name uniqueness and failure responses in memory."""

    base = "https://api.github.test/repos/owner/repo"
    upload_url = "https://uploads.github.test/repos/owner/repo/releases/1/assets"

    def __init__(self, existing=True):
        self.release = {"id": 1, "tag_name": "v1.2.3", "draft": not existing,
                        "upload_url": self.upload_url + "{?name,label}"}
        self.assets = {}
        self.calls = []
        self.errors = []
        self.next_id = 1
        self.fail_upload = False
        self.fail_rename = False
        self.fail_backup = False
        self.fail_rollback = False
        self.fail_cleanup = False
        self.upload_state = "uploaded"
        self.upload_digest = None
        self.upload_size = None
        self.exists = True
        self.remote_sha = "abc123"
        if existing:
            self.add_asset("XposedClipLinkRouter-1.2.3.apk", b"previous APK")

    def add_asset(self, name, data):
        asset = {"id": self.next_id, "name": name, "state": "uploaded",
                 "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
        self.assets[self.next_id] = asset
        self.next_id += 1
        return asset.copy()

    def error(self, url, status, message):
        error = urllib.error.HTTPError(url, status, message, {}, io.BytesIO())
        self.errors.append(error)
        return error

    def __call__(self, method, url, payload=None, content_type="application/json"):
        self.calls.append((method, url, payload))
        parsed = urllib.parse.urlsplit(url)
        query = urllib.parse.parse_qs(parsed.query)
        path = parsed.path.removeprefix("/repos/owner/repo")
        if method == "GET" and path == "/git/ref/tags/v1.2.3":
            return {"object": {"type": "commit", "sha": self.remote_sha}}
        if method == "GET" and path == "/releases":
            return [self.release.copy()] if self.exists else []
        if method == "POST" and path == "/releases":
            self.exists = True
            self.release.update(payload)
            return self.release.copy()
        if method == "GET" and path == "/releases/1/assets":
            start = (int(query["page"][0]) - 1) * 100
            return [asset.copy() for asset in list(self.assets.values())[start:start + 100]]
        if method == "POST" and url.startswith(self.upload_url + "?"):
            if self.fail_upload:
                raise self.error(url, 502, "Bad Gateway")
            name = query["name"][0]
            if any(asset["name"] == name for asset in self.assets.values()):
                raise self.error(url, 422, "Duplicate asset")
            asset = self.add_asset(name, payload)
            self.assets[asset["id"]]["state"] = self.upload_state
            if self.upload_digest is not None:
                self.assets[asset["id"]]["digest"] = self.upload_digest
            if self.upload_size is not None:
                self.assets[asset["id"]]["size"] = self.upload_size
            return self.assets[asset["id"]].copy()
        if path.startswith("/releases/assets/"):
            asset_id = int(path.rsplit("/", 1)[1])
            if method == "DELETE":
                if self.fail_cleanup:
                    raise self.error(url, 503, "Service Unavailable")
                del self.assets[asset_id]
                return None
            if method == "PATCH":
                if self.fail_rename and asset_id != 1 and payload["name"] == "XposedClipLinkRouter-1.2.3.apk":
                    raise self.error(url, 503, "Service Unavailable")
                if asset_id == 1 and ((self.fail_backup and payload["name"].startswith("backup-"))
                                      or (self.fail_rollback and payload["name"] == "XposedClipLinkRouter-1.2.3.apk")):
                    raise self.error(url, 503, "Service Unavailable")
                if any(asset["id"] != asset_id and asset["name"] == payload["name"]
                       for asset in self.assets.values()):
                    raise self.error(url, 422, "Duplicate asset")
                self.assets[asset_id].update(payload)
                return self.assets[asset_id].copy()
        if method == "PATCH" and path == "/releases/1":
            self.release.update(payload)
            return self.release.copy()
        raise AssertionError("Unexpected API call: " + method + " " + url)


class PublishTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        (self.directory / "tag.txt").write_text("v1.2.3", encoding="utf-8")
        (self.directory / "notes.md").write_text("- A change\n", encoding="utf-8")
        self.apk = self.directory / "XposedClipLinkRouter-1.2.3.apk"
        self.apk.write_bytes(b"new verified APK")
        env = {"CLIPLINK_RELEASE_DIR": str(self.directory), "GITHUB_REF_NAME": "v1.2.3",
               "GITHUB_API_URL": "https://api.github.test", "GITHUB_REPOSITORY": "owner/repo"}
        environment = patch.dict(os.environ, env)
        environment.start()
        self.addCleanup(environment.stop)
        git = patch.object(release, "git", return_value=b"abc123\n")
        git.start()
        self.addCleanup(git.stop)

    def publish(self, api):
        try:
            with patch.object(release, "request", side_effect=api), contextlib.redirect_stdout(io.StringIO()):
                release.publish()
        finally:
            for error in api.errors:
                error.close()

    def test_upload_failure_preserves_previous_download(self):
        api = ReleaseAPI()
        api.fail_upload = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)
        self.assertFalse(any(method in ("DELETE", "PATCH") for method, _, _ in api.calls))

    def test_replacement_upload_is_verified_before_old_asset_is_deleted(self):
        api = ReleaseAPI()
        self.publish(api)
        self.assertNotIn(1, api.assets)
        self.assertEqual([asset["name"] for asset in api.assets.values()], [self.apk.name])
        self.assertEqual(next(iter(api.assets.values()))["size"], self.apk.stat().st_size)
        upload = next(i for i, (method, url, _) in enumerate(api.calls)
                      if method == "POST" and url.startswith(api.upload_url))
        deletion = next(i for i, (method, _, _) in enumerate(api.calls) if method == "DELETE")
        self.assertLess(upload, deletion)
        self.assertFalse(api.release["draft"])
        self.assertEqual(api.release["body"], "- A change\n")

    def test_rename_failure_restores_previous_download(self):
        api = ReleaseAPI()
        api.fail_rename = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_incomplete_upload_does_not_replace_previous_download(self):
        api = ReleaseAPI()
        api.upload_state = "starter"
        with self.assertRaises(release.SafeError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_truncated_upload_does_not_replace_previous_download(self):
        api = ReleaseAPI()
        api.upload_size = 0
        with self.assertRaises(release.SafeError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_backup_failure_keeps_both_downloads(self):
        api = ReleaseAPI()
        api.fail_backup = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)
        self.assertEqual(len(api.assets), 2)

    def test_rollback_failure_keeps_previous_apk_under_backup_name(self):
        api = ReleaseAPI()
        api.fail_rename = api.fail_rollback = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertTrue(api.assets[1]["name"].startswith("backup-"))
        self.assertEqual(len(api.assets), 2)
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_cleanup_failure_keeps_new_download_and_backup(self):
        api = ReleaseAPI()
        api.fail_cleanup = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertTrue(api.assets[1]["name"].startswith("backup-"))
        self.assertEqual(api.assets[2]["name"], self.apk.name)

    def test_wrong_upload_digest_does_not_replace_previous_download(self):
        api = ReleaseAPI()
        api.upload_digest = "sha256:" + "0" * 64
        with self.assertRaises(release.SafeError):
            self.publish(api)
        self.assertEqual(api.assets[1]["name"], self.apk.name)

    def test_first_upload_publishes_draft_after_upload(self):
        api = ReleaseAPI(existing=False)
        self.publish(api)
        self.assertEqual([asset["name"] for asset in api.assets.values()], [self.apk.name])
        self.assertEqual(api.calls[-1][:2], ("PATCH", api.base + "/releases/1"))
        self.assertFalse(api.release["draft"])

    def test_new_release_stays_draft_when_upload_fails(self):
        api = ReleaseAPI(existing=False)
        api.exists = False
        api.fail_upload = True
        with self.assertRaises(urllib.error.HTTPError):
            self.publish(api)
        self.assertTrue(api.exists)
        self.assertTrue(api.release["draft"])
        self.assertEqual(api.assets, {})

    def test_new_release_is_created_and_published(self):
        api = ReleaseAPI(existing=False)
        api.exists = False
        self.publish(api)
        self.assertTrue(api.exists)
        self.assertFalse(api.release["draft"])
        self.assertEqual(api.assets[1]["name"], self.apk.name)

    def test_existing_asset_on_second_page_is_replaced(self):
        api = ReleaseAPI(existing=False)
        for index in range(100):
            api.add_asset("other-" + str(index), b"other data")
        previous = api.add_asset(self.apk.name, b"previous APK")
        self.publish(api)
        self.assertNotIn(previous["id"], api.assets)
        self.assertEqual(sum(asset["name"] == self.apk.name for asset in api.assets.values()), 1)
        self.assertEqual(len(api.assets), 101)

    def test_immutable_release_fails_before_mutating_assets(self):
        api = ReleaseAPI()
        api.release["immutable"] = True
        with self.assertRaisesRegex(release.SafeError, "immutable"):
            self.publish(api)
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))

    def test_moved_tag_fails_before_mutating_release(self):
        api = ReleaseAPI()
        api.remote_sha = "different"
        with self.assertRaisesRegex(release.SafeError, "Remote tag"):
            self.publish(api)
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))


class PrepareTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        previous = Path.cwd()
        os.chdir(self.directory)
        self.addCleanup(os.chdir, previous)
        environment = patch.dict(os.environ, {"GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v1.2.3",
                                              "RUNNER_TEMP": str(self.directory),
                                              "GITHUB_ENV": str(self.directory / "env")})
        environment.start()
        self.addCleanup(environment.stop)
        release.git("init", "--initial-branch=main")
        release.git("config", "user.name", "Release Tests")
        release.git("config", "user.email", "release-tests@example.invalid")
        # Ignore any personal signing or hooks configured on the test machine.
        release.git("config", "commit.gpgSign", "false")
        release.git("config", "tag.gpgSign", "false")
        release.git("config", "core.hooksPath", str(self.directory / "no-hooks"))
        self.commit("Initial commit")

    def commit(self, subject):
        release.git("commit", "--allow-empty", "-m", subject)

    def notes(self):
        with contextlib.redirect_stdout(io.StringIO()):
            release.prepare()
        env = (self.directory / "env").read_text(encoding="utf-8").splitlines()[-1]
        return (Path(env.split("=", 1)[1]) / "notes.md").read_text(encoding="utf-8")

    def test_first_release_lists_all_subjects(self):
        self.commit("Add routing")
        release.git("tag", "v1.2.3")
        self.assertEqual(self.notes(), "- Initial commit\n- Add routing\n")

    def test_annotated_previous_tag_limits_notes(self):
        release.git("tag", "-a", "v1.2.2", "-m", "Previous release")
        self.commit("Fix routing")
        release.git("tag", "v1.2.3")
        self.assertEqual(self.notes(), "- Fix routing\n")

    def test_same_commit_tags_have_no_new_commits(self):
        release.git("tag", "v1.2.2")
        release.git("tag", "v1.2.3")
        self.assertEqual(self.notes(), "此版本没有新的提交\n")

    def test_invalid_tag_fails_without_preparing_files(self):
        with patch.dict(os.environ, {"GITHUB_REF_NAME": "v1.2.3-01"}):
            with self.assertRaisesRegex(release.SafeError, "Invalid release tag"):
                release.prepare()
        self.assertFalse((self.directory / "env").exists())


class DiagnosticsTests(unittest.TestCase):
    def test_api_failure_reports_status_without_response_or_token(self):
        error = urllib.error.HTTPError("https://secret-token@example.invalid", 403,
                                       "secret response", {}, io.BytesIO())
        stderr = io.StringIO()
        with patch.object(release, "publish", side_effect=error), patch.object(release.sys, "argv", ["release.py", "publish"]), contextlib.redirect_stderr(stderr):
            self.assertEqual(release.main(), 1)
        self.assertIn("HTTP 403", stderr.getvalue())
        self.assertNotIn("secret", stderr.getvalue())

    def test_connection_failure_does_not_print_request_details(self):
        stderr = io.StringIO()
        with patch.object(release, "publish", side_effect=urllib.error.URLError("secret details")), patch.object(release.sys, "argv", ["release.py", "publish"]), contextlib.redirect_stderr(stderr):
            self.assertEqual(release.main(), 1)
        self.assertIn("connection failed", stderr.getvalue())
        self.assertNotIn("secret", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
