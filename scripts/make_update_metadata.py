#!/usr/bin/env python3
"""为 GitHub Release 生成更新元数据 yiner-update.json 和 SHA256SUMS.txt。

所有字段都从已签名 APK 本身读取（aapt2 dump badging），不手写，避免元数据与安装包不一致。
同时检查：
  - 包名必须是 io.yiner.browser
  - versionName 必须与 tag 一致（tag = "v" + versionName）
  - versionCode 必须大于上一个正式版的 versionCode（如提供 --previous）

用法：
  make_update_metadata.py --apk app-release.apk --aapt2 $ANDROID_HOME/build-tools/x/aapt2 \
      --tag v0.2.0 --out dist [--previous previous-yiner-update.json]
只使用 Python 标准库。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

PACKAGE = "io.yiner.browser"
SCHEMA = 1


def badging(aapt2: str, apk: str) -> dict:
    out = subprocess.run([aapt2, "dump", "badging", apk], check=True, capture_output=True, text=True).stdout
    pkg = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", out, re.M)
    if not pkg or not sdk:
        sys.exit("无法从 aapt2 输出中解析包信息")
    return {
        "packageName": pkg.group(1),
        "versionCode": int(pkg.group(2)),
        "versionName": pkg.group(3),
        "minSdk": int(sdk.group(1)),
    }


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--aapt2", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--previous", help="上一个正式版的 yiner-update.json（可选）")
    a = ap.parse_args()

    info = badging(a.aapt2, a.apk)
    if info["packageName"] != PACKAGE:
        sys.exit(f"包名应为 {PACKAGE}，实际为 {info['packageName']}")
    if a.tag != "v" + info["versionName"]:
        sys.exit(f"tag {a.tag} 与 versionName {info['versionName']} 不一致（应为 v{info['versionName']}）")
    if not re.fullmatch(r"[0-9A-Za-z._-]+", info["versionName"]):
        sys.exit("versionName 含有不允许的字符")

    if a.previous and os.path.exists(a.previous):
        with open(a.previous, encoding="utf-8") as f:
            prev = json.load(f)
        prev_code = int(prev.get("versionCode", 0))
        if info["versionCode"] <= prev_code:
            sys.exit(f"versionCode {info['versionCode']} 必须大于上一个正式版的 {prev_code}")
        print(f"上一个正式版 versionCode={prev_code}，本次 {info['versionCode']}")

    os.makedirs(a.out, exist_ok=True)
    apk_name = f"yiner-browser-{info['versionName']}.apk"
    apk_out = os.path.join(a.out, apk_name)
    shutil.copyfile(a.apk, apk_out)
    digest = sha256(apk_out)
    size = os.path.getsize(apk_out)

    meta = {
        "schema": SCHEMA,
        **info,
        "apk": apk_name,
        "sha256": digest,
        "size": size,
    }
    meta_path = os.path.join(a.out, "yiner-update.json")
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")

    with open(os.path.join(a.out, "SHA256SUMS.txt"), "w", encoding="utf-8") as f:
        f.write(f"{digest}  {apk_name}\n")
        f.write(f"{sha256(meta_path)}  yiner-update.json\n")

    print(json.dumps(meta, ensure_ascii=False))


if __name__ == "__main__":
    main()
