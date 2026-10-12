#!/usr/bin/env python3
"""Actual Chromium batch creation and parent/child progress through Nginx."""

import json
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from ui_smoke import api, login, BASE, OUTPUT

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        executable_path="/usr/bin/chromium",
        args=["--no-sandbox", "--disable-dev-shm-usage"],
    )
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE)
    login(page, "engineer")
    page.get_by_role("link", name="批量仿真").click()
    page.get_by_role("button", name="创建批次", exact=True).click()
    dialog = page.get_by_role("dialog", name="创建批次")
    dialog.locator(".el-form-item").filter(has_text="公开案例").locator(
        ".el-select"
    ).click()
    page.get_by_role(
        "option", name="STRUCTURE-CASE-CURRENT · BIW_TORSION", exact=True
    ).click()
    dialog.get_by_text("创建批次", exact=True).click()
    quantity = (
        dialog.locator(".el-form-item")
        .filter(has_text="每个案例的任务数")
        .locator("input")
    )
    quantity.fill("4")
    quantity.press("Tab")
    dialog.get_by_role("button", name="创建并逐步提交", exact=True).click()
    page.wait_for_url("**/batches/BATCH-*")
    number = page.url.rsplit("/", 1)[1]
    deadline = time.monotonic() + 90
    while True:
        batch = api("/batches/" + number)
        if batch["progress"] == 100:
            break
        assert time.monotonic() < deadline
        page.wait_for_timeout(500)
    assert (
        batch["status"] == "COMPLETED"
        and batch["counts"]["success"] == 4
        and batch["counts"]["failed"] == 0
    )
    expect(page.locator('[title="COMPLETED"]')).to_be_visible(timeout=10000)
    expect(page.locator(".el-table__row")).to_have_count(4)
    assert all(row["nodeId"] and row["pid"] > 0 for row in batch["children"])
    assert not errors, errors
    path = OUTPUT / "batch-workspace.png"
    page.screenshot(path=str(path), full_page=True)
    print(
        json.dumps(
            {
                "scenario": "M10 real browser batch",
                "batchNo": number,
                "counts": batch["counts"],
                "children": batch["children"],
                "noPageErrors": True,
                "screenshot": str(path.relative_to(Path.cwd())),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    browser.close()
