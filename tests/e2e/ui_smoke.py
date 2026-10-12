#!/usr/bin/env python3
"""Real Chromium → Nginx → backend → Solver → report acceptance (no API stubs)."""

import base64
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
import time
import urllib.request
import zipfile

from playwright.sync_api import sync_playwright, expect

BASE = os.getenv("CAE_UI_URL", "http://127.0.0.1:8088")
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / ".local/acceptance/m9"
OUTPUT.mkdir(parents=True, exist_ok=True)


def api(path, user="engineer"):
    request = urllib.request.Request(
        BASE + "/api/v1" + path,
        headers={
            "Authorization": "Basic "
            + base64.b64encode(f"{user}:{user}123".encode()).decode()
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def login(page, user):
    print("[M9] login " + user, file=sys.stderr, flush=True)
    if page.get_by_role("button", name="退出", exact=True).count():
        page.get_by_role("button", name="退出", exact=True).click()
    page.get_by_role("button", name=f"{user} / {user}123", exact=True).click()
    page.get_by_role("button", name="登录", exact=True).click()
    expect(page.get_by_role("heading", name="计算与结果，一目了然。")).to_be_visible()


def choose_case(page, entry):
    page.get_by_role("link", name="＋ 创建任务").click()
    expect(page.get_by_role("heading", name="创建仿真任务")).to_be_visible()
    selector = (
        page.locator(".el-form-item")
        .filter(has_text="公开案例 / 工况")
        .locator(".el-select")
    )
    selector.click()
    page.get_by_role(
        "option",
        name=entry["caseId"]
        + " · "
        + entry["conditionCode"]
        + " / Subcase "
        + str(entry["subcase"]),
        exact=True,
    ).click()


def wait_success(page, number):
    print("[M9] await " + number, file=sys.stderr, flush=True)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        job = api("/jobs/" + number)
        if job["status"] == "SUCCESS":
            expect(page.locator('.job-summary [title="SUCCESS"]')).to_be_visible(
                timeout=15000
            )
            return job
        if job["status"].endswith("FAILED"):
            raise AssertionError(job)
        page.wait_for_timeout(500)
    raise AssertionError("Job did not complete " + number)


def verify_detail(page, job, entry):
    page.get_by_role("tab", name="Solver 日志", exact=True).click()
    expect(page.locator(".log-panel")).to_contain_text("SUCCESS")
    page.get_by_role("tab", name="指标与图片", exact=True).click()
    expect(page.locator(".image-grid img")).to_have_count(4, timeout=20000)
    assert page.locator(".image-grid img").evaluate_all(
        "images => images.every(image => image.complete && image.naturalWidth === 1200 && image.naturalHeight === 720)"
    )
    metrics = api("/jobs/" + job["jobNo"] + "/metrics?parserVersion=v1")
    assert len(metrics) == 7 and all(
        row["conditionCode"] == entry["conditionCode"]
        and row["subcase"] == entry["subcase"]
        for row in metrics
    )
    page.screenshot(path=str(OUTPUT / (job["jobNo"] + "-metrics.png")), full_page=True)
    page.get_by_role("tab", name="报告与工件", exact=True).click()
    with page.expect_download() as downloaded:
        page.get_by_role("button", name="下载 PPTX", exact=True).first.click()
    download = downloaded.value
    path = OUTPUT / download.suggested_filename
    download.save_as(path)
    with zipfile.ZipFile(path) as archive:
        assert (
            len(
                [
                    name
                    for name in archive.namelist()
                    if name.startswith("ppt/slides/slide") and name.endswith(".xml")
                ]
            )
            == 5
        )
        assert (
            len([name for name in archive.namelist() if name.startswith("ppt/media/")])
            == 4
        )
    return {
        "jobNo": job["jobNo"],
        "executionId": job["currentExecutionId"],
        "metrics": len(metrics),
        "images": 4,
        "pptx": str(path.relative_to(ROOT)),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def main():
    catalog = api("/cases")
    current = next(row for row in catalog if row["caseId"] == "STRUCTURE-CASE-CURRENT")
    modal = next(
        row
        for row in catalog
        if row["conditionCode"] == "BIW_MODAL" and row["subcase"] == 2
    )
    evidence = {"scenario": "M9 real browser acceptance", "jobs": [], "checks": {}}
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=shutil.which("chromium"),
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        context = browser.new_context(
            viewport={"width": 1440, "height": 1000}, accept_downloads=True
        )
        page = context.new_page()
        errors = []
        streams = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on(
            "response",
            lambda response: streams.append(response.status)
            if "/events/stream" in response.url
            else None,
        )
        page.goto(BASE)
        login(page, "engineer")
        expect(page.locator(".kpi-grid .el-card")).to_have_count(6)
        expect(
            page.locator(".kpi-grid .el-card").last.locator(".metric-number")
        ).to_contain_text("3")
        page.screenshot(path=str(OUTPUT / "dashboard.png"), full_page=True)
        choose_case(page, current)
        accepted = {}

        def lose_response(route):
            response = route.fetch()
            assert response.status == 202
            accepted.update(response.json())
            accepted["key"] = route.request.headers["idempotency-key"]
            route.abort("failed")

        page.route("**/api/v1/jobs", lose_response, times=1)
        page.get_by_role("button", name="创建并提交", exact=True).click()
        expect(page.locator(".el-alert")).to_be_visible()
        assert accepted["jobNo"]
        page.get_by_role("button", name="创建并提交", exact=True).click()
        page.wait_for_url("**/jobs/" + accepted["jobNo"])
        job = wait_success(page, accepted["jobNo"])
        evidence["jobs"].append(verify_detail(page, job, current))
        original_report_sha = evidence["jobs"][0]["sha256"]
        page.get_by_role("tab", name="指标与图片", exact=True).click()
        page.get_by_role("button", name="使用 v2 重解析", exact=True).click()
        expect(page.locator(".image-grid .el-card").first).to_contain_text(
            "v2 / Post", timeout=30000
        )
        expect(
            page.locator(".detail-tabs .el-tab-pane")
            .filter(has=page.locator(".image-grid"))
            .locator(".el-table__row")
        ).to_have_count(7)
        assert len(api("/jobs/" + job["jobNo"] + "/executions")) == 1
        # A browser download of v1 remains byte-identical after v2 starts.
        page.get_by_role("tab", name="报告与工件", exact=True).click()
        with page.expect_download() as downloaded:
            page.locator(".el-table__row").filter(has_text="v1").get_by_role(
                "button", name="下载 PPTX", exact=True
            ).first.click()
        stable = OUTPUT / (job["jobNo"] + "-v1-after-v2.pptx")
        downloaded.value.save_as(stable)
        assert hashlib.sha256(stable.read_bytes()).hexdigest() == original_report_sha
        evidence["checks"]["uiV2ReparsePreservesV1AndExecution"] = True
        evidence["checks"]["acceptedResponseLostSameJob"] = True
        assert len(api("/jobs/" + job["jobNo"] + "/executions")) == 1
        assert not page.get_by_role("button", name="Review", exact=True).count()
        page.get_by_role("link", name="总览").click()
        choose_case(page, modal)
        page.get_by_role("button", name="创建并提交", exact=True).click()
        page.wait_for_url("**/jobs/CAE-*")
        number = page.url.rsplit("/", 1)[1]
        modal_job = wait_success(page, number)
        evidence["jobs"].append(verify_detail(page, modal_job, modal))
        page.get_by_role("link", name="计算资源").click()
        expect(page.locator(".nodes-grid > .el-card")).to_have_count(3)
        license_card = page.locator(".el-card").filter(
            has=page.get_by_role("heading", name="License Token", exact=True)
        )
        expect(license_card.locator(".el-table__row")).to_have_count(4)
        for pool in api("/license-pools"):
            license_row = license_card.locator(".el-table__row").filter(
                has_text=pool["feature"]
            )
            expect(license_row.locator("td").nth(3)).to_have_text(
                str(pool["inUseTokens"])
            )
        evidence["checks"]["licenseUsageMatchesRealApi"] = True
        assert page.get_by_role("button", name="暂停新分配", exact=True).count() == 0
        evidence["checks"]["engineerHidesAdminControls"] = True
        login(page, "lead")
        page.get_by_role("link", name="报告中心").click()
        row = page.locator(".el-table__row").filter(has_text=number).first
        row.get_by_role("button", name="Review", exact=True).click()
        expect(row).to_contain_text("已审核")
        assert not row.get_by_role("button", name="Approve", exact=True).count()
        # The chooser and comparison table use server-compatible baselines and exact values.
        chosen = evidence["jobs"][0]["jobNo"]
        compatible = api("/jobs/" + chosen + "/baselines", "lead")
        assert compatible
        baseline = compatible[0]["jobNo"]
        page.get_by_role("button", name="生成草稿", exact=True).click()
        report_dialog = page.get_by_role("dialog", name="生成新报告草稿")
        report_dialog.locator(".el-form-item").filter(has_text="已完成任务").locator(
            ".el-select"
        ).click()
        page.get_by_role("option", name=chosen + " · BIW_TORSION", exact=True).click()
        report_dialog.locator(".el-form-item").filter(has_text="同工况基线").locator(
            ".el-select"
        ).click()
        page.get_by_role("option", name=baseline, exact=True).click()
        expected_comparison = api(
            "/jobs/" + chosen + "/comparison?baselineJobNo=" + baseline, "lead"
        )
        expect(report_dialog.locator(".el-table__row")).to_have_count(7)
        for comparison in expected_comparison["comparisons"]:
            comparison_row = report_dialog.locator(".el-table__row").filter(
                has_text=comparison["metricCode"]
            )
            expect(comparison_row).to_contain_text(comparison["deltaPercent"] or "N/A")
        report_dialog.get_by_role("button", name="生成 DRAFT", exact=True).click()
        expect(report_dialog).not_to_be_visible()
        deadline = time.monotonic() + 90
        while True:
            versions = api("/jobs/" + chosen + "/reports")
            if any(
                row["parserVersion"] == "v1"
                and row["reportVersion"] == 2
                and row["baselineJobNo"] == baseline
                and row["reportStatus"] == "DRAFT"
                for row in versions
            ):
                break
            assert time.monotonic() < deadline, versions
            current_job = api("/jobs/" + chosen)
            assert not current_job["status"].endswith("FAILED"), current_job
            page.wait_for_timeout(500)
        assert len(api("/jobs/" + chosen + "/executions")) == 1
        evidence["checks"]["explicitBaselineAndNewDraftNoSolver"] = {
            "jobNo": chosen,
            "baseline": baseline,
        }
        login(page, "admin")
        page.get_by_role("link", name="报告中心").click()
        row = page.locator(".el-table__row").filter(has_text=number).first
        row.get_by_role("button", name="Approve", exact=True).click()
        expect(row).to_contain_text("已批准")
        expect(page.get_by_role("heading", name="报告模板", exact=True)).to_be_visible()
        evidence["checks"]["manualRoleReviewAndApproval"] = True
        page.get_by_role("link", name="计算资源").click()
        card = page.locator(".nodes-grid > .el-card").first
        card.get_by_role("button", name="暂停新分配", exact=True).click()
        expect(card.get_by_role("button", name="恢复调度", exact=True)).to_be_visible()
        card.get_by_role("button", name="恢复调度", exact=True).click()
        expect(
            card.get_by_role("button", name="暂停新分配", exact=True)
        ).to_be_visible()
        page.get_by_role("button", name="编辑", exact=True).first.click()
        expect(page.get_by_role("dialog", name="编辑资源档位")).to_be_visible()
        page.get_by_role("button", name="返回", exact=True).click()
        evidence["checks"]["adminDrainAndProfileDialog"] = True
        page.set_viewport_size({"width": 390, "height": 844})
        page.get_by_role("link", name="总览").click()
        expect(page.locator(".kpi-grid .el-card")).to_have_count(6)
        assert page.evaluate(
            "document.documentElement.scrollWidth <= window.innerWidth + 1"
        )
        expect(
            page.locator(".kpi-grid .el-card").last.locator(".metric-number")
        ).to_contain_text("3")
        page.evaluate("window.scrollTo(0,0)")
        page.wait_for_timeout(1200)
        page.screenshot(path=str(OUTPUT / "mobile-dashboard.png"))
        evidence["checks"]["mobile390px"] = True
        assert streams and all(code == 200 for code in streams), streams
        assert not errors, errors
        evidence["checks"]["authenticatedSse200"] = len(streams)
        evidence["checks"]["noPageErrors"] = True
        assert page.evaluate("localStorage.length + sessionStorage.length") == 0
        evidence["checks"]["noPersistentCredentials"] = True
        context.close()
        browser.close()
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
