import hashlib
import io
import json
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from contextlib import redirect_stderr
from pathlib import Path

from shared_release_manifest import merge_manifest, publish_artifact, publish_manifest, read_manifest, write_manifest
from package_local_release import LOCAL_ROOT, package_release, publish_release_metadata
from shared_release_manifest import main as publish_main


APK_BYTES = b"existing Android APK"
ANDROID = {"manifestVersion": 1, "versionCode": 18, "versionName": "4.2.0",
           "apkUrl": "https://cdn.fgogotran.com/app/android/releases/4.2.0/app.apk",
           "apkSha256": hashlib.sha256(APK_BYTES).hexdigest(), "apkSize": len(APK_BYTES), "changelog": ["Android"]}
LOCAL = {"versionCode": 1, "versionName": "v1.0.0", "platform": "windows-x64",
         "downloadMode": "manual", "downloadUrl": "https://1drv.ms/f/public-folder",
         "packageSha256": "b" * 64, "packageSize": 10, "changelog": ["Local"]}
MINIMAL_LOCAL = {key: value for key, value in LOCAL.items()
                 if key not in {"downloadUrl", "packageSha256", "packageSize"}}


def stage_android_apk(output: Path) -> Path:
    apk = output / "app/android/releases/4.2.0/app.apk"
    apk.parent.mkdir(parents=True)
    apk.write_bytes(APK_BYTES)
    return apk


class SharedManifestTests(unittest.TestCase):
    def test_android_preserves_local_and_unknown_sections(self):
        current = {**ANDROID, "fgogotranLocal": LOCAL, "future": {"preserve": True}}
        candidate = {**ANDROID, "versionCode": 19, "versionName": "4.3.0", "apkSha256": "c" * 64,
                     "fgogotranLocal": {"stale": "must not overwrite origin"}}
        result = merge_manifest(current, candidate, "android")
        self.assertEqual(LOCAL, result["fgogotranLocal"])
        self.assertEqual({"preserve": True}, result["future"])
        self.assertEqual(19, result["versionCode"])
        self.assertEqual(18, current["versionCode"])

    def test_local_preserves_all_android_fields(self):
        result = merge_manifest(ANDROID, {"fgogotranLocal": LOCAL}, "local")
        self.assertEqual(ANDROID, {key: value for key, value in result.items() if key != "fgogotranLocal"})
        self.assertEqual(LOCAL, result["fgogotranLocal"])
        with self.assertRaises(ValueError):
            merge_manifest({}, {"fgogotranLocal": LOCAL}, "local")

    def test_local_accepts_version_only_and_localized_notes_without_artifact_fields(self):
        for value in (
            {"versionCode": 1, "versionName": "v1.0.0"},
            {**MINIMAL_LOCAL, "changelogI18n": {"zh-Hant": ["更新"], "en": ["Updated"]}},
        ):
            with self.subTest(value=value):
                current = {**ANDROID, "fgogotranLocal": value, "future": {"keep": True}}
                result = merge_manifest(current, {"fgogotranLocal": value}, "local")
                self.assertEqual(current, result)
                self.assertIsNot(current["fgogotranLocal"], result["fgogotranLocal"])

    def test_local_same_version_can_gain_its_first_zip_details(self):
        current = {**ANDROID, "fgogotranLocal": MINIMAL_LOCAL}
        result = merge_manifest(current, {"fgogotranLocal": LOCAL}, "local")
        self.assertEqual(LOCAL, result["fgogotranLocal"])
        self.assertNotIn("packageSha256", current["fgogotranLocal"])

    def test_notes_only_refresh_keeps_existing_artifact_identity(self):
        incoming = {**MINIMAL_LOCAL, "changelog": ["Corrected notes"]}
        current = {**ANDROID, "fgogotranLocal": LOCAL}
        result = merge_manifest(current, {"fgogotranLocal": incoming}, "local")
        self.assertEqual({**LOCAL, "changelog": ["Corrected notes"]}, result["fgogotranLocal"])
        self.assertEqual(["Local"], current["fgogotranLocal"]["changelog"])

    def test_new_local_version_does_not_inherit_previous_zip_details(self):
        incoming = {**MINIMAL_LOCAL, "versionCode": 2, "versionName": "v1.0.1"}
        result = merge_manifest({**ANDROID, "fgogotranLocal": LOCAL},
                                {"fgogotranLocal": incoming}, "local")
        self.assertEqual(incoming, result["fgogotranLocal"])

    def test_optional_artifact_fields_and_core_local_fields_are_validated(self):
        invalid = (
            {"downloadUrl": "https://cdn.fgogotran.com/package.zip"},
            {"downloadUrl": None}, {"packageSha256": "not-a-sha256"},
            {"packageSha256": None}, {"packageSize": 0}, {"packageSize": True},
            {"versionCode": True}, {"versionCode": 0}, {"versionName": "1.0.0"},
            {"platform": "linux-x64"}, {"downloadMode": "automatic"},
            {"changelog": "notes"}, {"changelogI18n": {"en": "notes"}},
        )
        for fields in invalid:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                merge_manifest(ANDROID, {"fgogotranLocal": {**MINIMAL_LOCAL, **fields}}, "local")

    def test_local_known_zip_size_and_version_name_cannot_change_at_same_code(self):
        for fields in ({"packageSize": 11}, {"versionName": "v1.0.1"}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                merge_manifest({**ANDROID, "fgogotranLocal": LOCAL},
                               {"fgogotranLocal": {**LOCAL, **fields}}, "local")

    def test_local_downgrade_is_rejected_even_without_artifact_metadata(self):
        current = {**ANDROID, "fgogotranLocal": {**MINIMAL_LOCAL, "versionCode": 2, "versionName": "v1.0.1"}}
        with self.assertRaises(ValueError):
            merge_manifest(current, {"fgogotranLocal": MINIMAL_LOCAL}, "local")

    def test_android_still_requires_zip_independent_apk_identity(self):
        for fields in ({"apkSha256": ""}, {"apkSha256": None}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                merge_manifest(ANDROID, {**ANDROID, **fields}, "android")
        without_hash = {key: value for key, value in ANDROID.items() if key != "apkSha256"}
        with self.assertRaises(ValueError):
            merge_manifest(without_hash, ANDROID, "android")

    def test_downgrades_and_reusing_a_code_for_new_contents_stop(self):
        with self.assertRaises(ValueError):
            merge_manifest(ANDROID, {**ANDROID, "versionCode": 17}, "android")
        with self.assertRaises(ValueError):
            merge_manifest({**ANDROID, "fgogotranLocal": LOCAL},
                           {"fgogotranLocal": {**LOCAL, "packageSha256": "f" * 64}}, "local")

    def test_publication_retries_race_and_preserves_new_origin_other_product(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            candidate = {**ANDROID, "versionCode": 19, "versionName": "4.3.0", "apkSha256": "c" * 64}
            write_manifest(path, candidate)
            calls, uploaded = [], []
            def run(command, **kwargs):
                calls.append(command)
                if command[2] == "get-object":
                    attempt = len([call for call in calls if call[2] == "get-object"])
                    origin = ANDROID if attempt == 1 else {**ANDROID, "fgogotranLocal": LOCAL}
                    Path(command[command.index("--output") - 1]).write_text(json.dumps(origin))
                    return subprocess.CompletedProcess(command, 0, stdout=json.dumps({"ETag": f'"etag-{attempt}"'}))
                self.assertIn("--if-match", command)
                uploaded.append(read_manifest(Path(command[command.index("--body") + 1])))
                if len(uploaded) == 1:
                    return subprocess.CompletedProcess(command, 1, stderr="PreconditionFailed (412)")
                self.assertEqual('"etag-2"', command[command.index("--if-match") + 1])
                return subprocess.CompletedProcess(command, 0, stdout="{}")
            publish_manifest(path, "android", "s3://existing-bucket/prefix", runner=run)
            self.assertEqual(4, len(calls))
            self.assertEqual(LOCAL, uploaded[-1]["fgogotranLocal"])
            self.assertEqual(LOCAL, read_manifest(path)["fgogotranLocal"])

    def test_version_only_local_publication_preserves_android_and_conditional_write(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            incoming = {**MINIMAL_LOCAL, "changelogI18n": {"zh-Hant": ["更新"]}}
            write_manifest(path, {**ANDROID, "fgogotranLocal": incoming})
            origin = {**ANDROID, "fgogotranLocal": MINIMAL_LOCAL, "future": {"keep": True}}
            uploaded = []

            def run(command, **kwargs):
                if command[2] == "get-object":
                    write_manifest(Path(command[command.index("--output") - 1]), origin)
                    return subprocess.CompletedProcess(command, 0, stdout=json.dumps({"ETag": '"origin"'}))
                self.assertIn("--if-match", command)
                self.assertEqual('"origin"', command[command.index("--if-match") + 1])
                uploaded.append(read_manifest(Path(command[command.index("--body") + 1])))
                return subprocess.CompletedProcess(command, 0, stdout="{}")

            result = publish_manifest(path, "local", "s3://existing-bucket", runner=run)
            self.assertEqual([{**origin, "fgogotranLocal": incoming}], uploaded)
            self.assertEqual(uploaded[0], result)

    def test_unknown_s3_error_never_falls_back_to_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            write_manifest(path, ANDROID)
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 1, stderr="AccessDenied")
            with self.assertRaises(RuntimeError):
                publish_manifest(path, "android", "s3://existing-bucket", runner=run)
            self.assertEqual(1, len(calls))

    def test_versioned_artifact_retries_accept_identical_bytes_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            artifact = Path(folder) / "local.zip"
            artifact.write_bytes(b"approved package")
            for remote, succeeds in ((b"approved package", True), (b"different package", False)):
                calls = []
                def run(command, **kwargs):
                    calls.append(command)
                    if command[2] == "put-object":
                        self.assertIn("--if-none-match", command)
                        return subprocess.CompletedProcess(command, 1, stderr="PreconditionFailed (412)")
                    Path(command[command.index("--output") - 1]).write_bytes(remote)
                    return subprocess.CompletedProcess(command, 0, stdout="{}")
                if succeeds:
                    publish_artifact(artifact, "app/local/v1/local.zip", "s3://existing-bucket", runner=run)
                else:
                    with self.assertRaises(RuntimeError):
                        publish_artifact(artifact, "app/local/v1/local.zip", "s3://existing-bucket", runner=run)
                self.assertEqual(2, len(calls))

    def test_local_zip_has_inventory_and_no_user_data_models_environment_or_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            apk = stage_android_apk(output)
            result = package_release(LOCAL_ROOT, output, {**ANDROID, "fgogotranLocal": MINIMAL_LOCAL})
            archive = Path(result["archive"])
            self.assertEqual(apk.parent / "FgoGotranLocal-v1.0.0-windows-x64.zip", archive)
            self.assertEqual(output / "app/android/latest/manifest.json", Path(result["manifest"]))
            self.assertEqual(APK_BYTES, apk.read_bytes())
            self.assertTrue(archive.with_suffix(".zip.sha256").is_file())
            with zipfile.ZipFile(archive) as package:
                names = package.namelist()
                self.assertIn("installed-files.json", names)
                self.assertIn("src/fgogotran_local/version.json", names)
                self.assertNotIn("src/fgogotran_local/portable_update.py", names)
                self.assertNotIn("CHANGELOG.md", names)
                self.assertFalse(any(name.startswith(("user_data/", "models/", ".venv/", "tests/")) for name in names))
                self.assertFalse(any("__pycache__" in name or name.endswith((".gguf", ".pyc", ".log")) for name in names))
                version = json.loads(package.read("src/fgogotran_local/version.json"))
                self.assertEqual({"versionCode": 1, "versionName": "v1.0.0"}, version)
            shared = read_manifest(Path(result["manifest"]))
            self.assertEqual(18, shared["versionCode"])
            self.assertEqual(1, shared["fgogotranLocal"]["versionCode"])
            self.assertEqual("manual", shared["fgogotranLocal"]["downloadMode"])
            self.assertTrue(shared["fgogotranLocal"]["downloadUrl"].startswith("https://1drv.ms/"))
            self.assertNotIn("packageUrl", shared["fgogotranLocal"])
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), shared["fgogotranLocal"]["packageSha256"])
            # Reproducible packaging: a second preparation has identical bytes.
            again = package_release(LOCAL_ROOT, output, shared)
            self.assertEqual(result["release"]["packageSha256"], again["release"]["packageSha256"])

    def test_local_packaging_does_not_replace_shared_manifest_from_stale_base(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            stage_android_apk(output)
            result = package_release(LOCAL_ROOT, output, ANDROID)
            manifest = Path(result["manifest"])
            previous = manifest.read_bytes()
            with self.assertRaisesRegex(ValueError, "output shared manifest differs"):
                package_release(LOCAL_ROOT, output, ANDROID)
            self.assertEqual(previous, manifest.read_bytes())

    def test_local_packaging_requires_the_matching_apk_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            with self.assertRaisesRegex(FileNotFoundError, "Prepare the Android APK"):
                package_release(LOCAL_ROOT, output, ANDROID)
            self.assertEqual([], list(output.iterdir()))

            apk = stage_android_apk(output)
            for altered in (b"short", b"x" * len(APK_BYTES)):
                with self.subTest(apk=altered):
                    apk.write_bytes(altered)
                    with self.assertRaisesRegex(ValueError, "does not match the manifest"):
                        package_release(LOCAL_ROOT, output, ANDROID)
                    self.assertEqual([apk], [path for path in output.rglob("*") if path.is_file()])

    @patch("package_local_release.publish_manifest")
    def test_local_publication_sends_only_manifest_never_zip_or_checksum(self, publish):
        result = {"archive": "packages/v1.0.0/Local.zip", "manifest": "app/android/latest/manifest.json"}
        publish_release_metadata(result, "s3://existing-bucket", aws="aws-test")
        publish.assert_called_once_with(Path(result["manifest"]), "local", "s3://existing-bucket", aws="aws-test")

    def test_packaging_rejects_a_cdn_zip_as_the_local_download_link(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                package_release(LOCAL_ROOT, Path(folder), ANDROID,
                                download_url="https://cdn.fgogotran.com/app/local/package.zip")
            self.assertEqual([], list(Path(folder).iterdir()))

    @patch("shared_release_manifest.publish_manifest")
    @patch("shared_release_manifest.publish_artifact")
    def test_shared_cli_rejects_local_artifact_uploads_before_any_network_call(self, artifact, manifest):
        arguments = ["shared_release_manifest.py", "--manifest", "manifest.json", "--product", "local",
                     "--s3-uri", "s3://existing-bucket", "--artifact-file", "Local.zip",
                     "--artifact-key", "app/local/Local.zip"]
        with patch("sys.argv", arguments), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            publish_main()
        self.assertEqual(2, raised.exception.code)
        artifact.assert_not_called()
        manifest.assert_not_called()


if __name__ == "__main__":
    unittest.main()
