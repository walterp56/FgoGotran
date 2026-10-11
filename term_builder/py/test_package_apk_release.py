"""Offline release-guard tests. Only isolated fixtures are ever written.

Optional real Gradle policy check (no APK build): set
ANALYTICS_GRADLE_FIXTURE_WRAPPER to the installed repository gradlew script.
The real owner-controlled analytics-release.properties is NEVER created/edited.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import package_apk_release as release


def manifest(*metadata: str) -> str:
    return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.fgogotran"><application>' + "".join(metadata) + '</application></manifest>')


def marker(value: str = "true") -> str:
    return f'<meta-data android:name="{release.ANALYTICS_MARKER}" android:value="{value}" />'


class AnalyticsApkGuardTest(unittest.TestCase):
    def test_literal_on_is_required(self):
        release.require_analytics_enabled_manifest(manifest(marker()))
        for xml in (manifest(), manifest(marker("false")), manifest(marker("1")),
                    manifest(marker("TRUE")), manifest(marker("@bool/enabled")),
                    manifest(marker(), marker()),
                    manifest(f'<meta-data android:name="{release.ANALYTICS_MARKER}" '
                             'android:value="true" android:resource="@bool/enabled" />'),
                    '<manifest/>', '<unrelated><application/></unrelated>', '<invalid'):
            with self.subTest(xml=xml), self.assertRaises(RuntimeError):
                release.require_analytics_enabled_manifest(xml)

    @patch.object(release, "find_apkanalyzer", return_value=Path("sdk/apkanalyzer"))
    @patch.object(release.subprocess, "run")
    def test_reads_the_actual_apk_not_the_current_owner_setting(self, run, find):
        run.return_value = subprocess.CompletedProcess([], 0, stdout=manifest(marker()))
        apk = Path("fixture.apk")
        release.verify_apk_analytics(apk)
        find.assert_called_once_with(None)
        self.assertEqual(["sdk" + os.sep + "apkanalyzer", "manifest", "print", str(apk)], run.call_args.args[0])
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(30, run.call_args.kwargs["timeout"])
        self.assertNotIn("shell", run.call_args.kwargs)

    @patch.object(release, "find_apkanalyzer", return_value=Path("sdk/apkanalyzer"))
    @patch.object(release.subprocess, "run")
    def test_tool_failure_timeout_or_unreadable_output_stops(self, run, _find):
        for error in (OSError("missing java"), subprocess.CalledProcessError(1, "apkanalyzer"),
                      subprocess.TimeoutExpired("apkanalyzer", 30)):
            run.side_effect = error
            with self.subTest(error=error), self.assertRaises(RuntimeError):
                release.verify_apk_analytics(Path("fixture.apk"))
        run.side_effect = None
        run.return_value = subprocess.CompletedProcess([], 0, stdout="unreadable")
        with self.assertRaises(RuntimeError):
            release.verify_apk_analytics(Path("fixture.apk"))

    def test_missing_explicit_tool_stops(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileNotFoundError):
                release.find_apkanalyzer(Path(temp) / "missing-apkanalyzer")

    @patch.object(release.shutil, "which", return_value=None)
    @patch.object(release.Path, "is_file", return_value=False)
    @patch.object(release.Path, "glob", return_value=[])
    def test_missing_installed_tool_stops(self, _glob, _is_file, _which):
        with self.assertRaisesRegex(RuntimeError, "apkanalyzer was not found"):
            release.find_apkanalyzer()

    @patch.object(release, "verify_apk_analytics", side_effect=RuntimeError("OFF"))
    def test_failed_check_never_touches_release_outputs(self, verify):
        with tempfile.TemporaryDirectory() as temp:
            args, apk = self.fixture(Path(temp))
            args.output.mkdir()
            sentinel = args.output / "existing-release.txt"
            sentinel.write_text("unchanged", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "OFF"):
                release.package_apk_release(args)
            verify.assert_called_once_with(apk.resolve(), None)
            self.assertEqual([sentinel], list(args.output.rglob("*")))
            self.assertEqual("unchanged", sentinel.read_text(encoding="utf-8"))

    @patch.object(release, "verify_apk_analytics")
    def test_verified_packaging_preserves_apk_and_localized_manifest(self, _verify):
        with tempfile.TemporaryDirectory() as temp:
            args, apk = self.fixture(Path(temp))
            result = release.package_apk_release(args)
            packaged = Path(result["releaseDir"]) / "FgoGotran-test.apk"
            self.assertEqual(apk.read_bytes(), packaged.read_bytes())
            output = json.loads(Path(result["manifest"]).read_text(encoding="utf-8"))
            self.assertEqual(["CN"], output["changelog"])
            self.assertEqual({"zh-Hans": ["CN"], "zh-Hant": ["TW"], "en": ["EN"]}, output["changelogI18n"])
            self.assertEqual(release.sha256_file(apk), output["apkSha256"])

    def test_powershell_failure_exits_before_publishing_and_skipverify_cannot_bypass(self):
        script = (release.REPO_ROOT / "scripts/release-app.ps1").read_text(encoding="utf-8")
        invocation = script[script.index("    & $PythonExe @Args"):]
        self.assertLess(invocation.index("if ($LASTEXITCODE -ne 0)"), invocation.index("Publish-AppRelease -OutputRoot"))
        self.assertIn("exit $LASTEXITCODE", invocation)
        self.assertNotIn("SkipVerify", invocation)

    @staticmethod
    def fixture(root: Path):
        apk_dir = root / "input"
        apk_dir.mkdir()
        apk = apk_dir / "fixture.apk"
        apk.write_bytes(b"signed-apk-fixture-not-a-real-apk")
        (apk_dir / "output-metadata.json").write_text(json.dumps({"elements": [{
            "versionName": "test", "versionCode": 1, "outputFile": apk.name,
        }]}), encoding="utf-8")
        return argparse.Namespace(apk_dir=apk_dir, apk=None, output=root / "output", version_name=None,
                                  version_code=None, release_slug=None, app_prefix="app/android", apk_name=None,
                                  min_sdk=30, minimum_android=None, base_url="https://example.invalid",
                                  changelog=["CN"], changelog_hant=["TW"], changelog_en=["EN"]), apk


@unittest.skipUnless(os.environ.get("ANALYTICS_GRADLE_FIXTURE_WRAPPER"), "optional isolated Gradle policy check")
class AnalyticsGradlePolicyTest(unittest.TestCase):
    def test_actual_build_policy_using_only_temporary_owner_file_fixtures(self):
        wrapper = Path(os.environ["ANALYTICS_GRADLE_FIXTURE_WRAPPER"]).resolve()
        source = (release.REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        policy = source[source.index("// OWNER-CONTROLLED:"):source.index("\nandroid {")]
        with tempfile.TemporaryDirectory(prefix="analytics-build-policy-") as temp:
            root = Path(temp)
            # Only the filename changes. The parser/provider code under test is the actual source.
            policy = policy.replace('file("analytics-release.properties")', 'file("$caseName.properties")')
            fixture = 'import java.io.StringReader\nimport java.util.Properties\n'
            fixture += 'fun Project.fixtureEnabled(caseName: String): Boolean {\n' + policy + '\nreturn ownerAnalyticsEnabled\n}\n'
            for name, content, expected in (("missing", None, False), ("empty", "", False),
                                            ("false", "awsUsageAnalyticsEnabled=false", False),
                                            ("true", "awsUsageAnalyticsEnabled=true", True),
                                            ("trimmed", "awsUsageAnalyticsEnabled= true  ", True),
                                            ("invalid", "awsUsageAnalyticsEnabled=yes", None),
                                            ("uppercase", "awsUsageAnalyticsEnabled=TRUE", None),
                                            ("blank", "awsUsageAnalyticsEnabled=", None)):
                if content is not None:
                    (root / f"{name}.properties").write_text(content, encoding="utf-8")
                if expected is None:
                    fixture += f'check(runCatching {{ fixtureEnabled("{name}") }}.exceptionOrNull() is GradleException)\n'
                else:
                    fixture += f'check(fixtureEnabled("{name}") == {str(expected).lower()})\n'
            fixture += 'tasks.register("verifyAnalyticsPolicy") { doLast { println("ANALYTICS_POLICY_FIXTURES_OK") } }\n'
            (root / "settings.gradle.kts").write_text('rootProject.name = "analytics-policy-fixture"\n', encoding="utf-8")
            (root / "build.gradle.kts").write_text(fixture, encoding="utf-8")
            result = subprocess.run([str(wrapper), "--offline", "--no-daemon", "--console=plain", "-p", str(root),
                                     "verifyAnalyticsPolicy"], cwd=root, capture_output=True, encoding="utf-8",
                                    errors="replace", timeout=180)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("ANALYTICS_POLICY_FIXTURES_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
